/*
 * CryLog — self-hosted baby monitor
 * Copyright (C) 2026 Piero Biagini
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Additional permission under GNU GPL version 3 section 7
 *
 * If you modify this Program, or any covered work, by linking or combining it
 * with Google Play Services and the Firebase SDKs (or modified versions of
 * those libraries), the licensors of this Program grant you additional
 * permission to convey the resulting work. See LICENSE-EXCEPTION.txt.
 */

package it.biagini.crylog.parent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import it.biagini.crylog.MainActivity
import it.biagini.crylog.R
import it.biagini.crylog.core.ConnectionState
import it.biagini.crylog.core.HubMessage
import it.biagini.crylog.core.HubProtocol
import it.biagini.crylog.core.Role
import it.biagini.crylog.core.StreamRequest
import it.biagini.crylog.core.StreamTransport
import it.biagini.crylog.core.TransportState
import it.biagini.crylog.hub.DeviceStore
import it.biagini.crylog.hub.HubClient
import it.biagini.crylog.transport.WebRtcTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Ascolto continuo sul Parent Node.
 *
 * È l'unica parte del sistema che deve reggere una notte intera: audio dalla
 * cameretta a schermo spento, con l'app chiusa. Vive in un servizio in
 * foreground perché un ViewModel muore con l'Activity, e con lui morirebbe
 * l'ascolto.
 *
 * Mentre è acceso la connessione all'Hub appartiene a questo servizio: l'Hub
 * tiene una sessione sola per dispositivo, quindi due connessioni si
 * scalzerebbero a vicenda. La UI legge da [ContinuousListening].
 *
 * Il criterio di salute non è lo stato ICE ma **l'arrivo effettivo di audio**:
 * una sessione "connessa" che non trasporta nulla suona esattamente come una
 * cameretta tranquilla, ed è il modo peggiore in cui questo sistema può
 * fallire. Se l'audio si ferma si ritenta; se non torna, si suona.
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var store: DeviceStore
    private lateinit var client: HubClient
    private lateinit var alerter: Alerter
    private var transport: StreamTransport? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var alarm: MediaPlayer? = null

    /**
     * L'allarme e' stato zittito a mano.
     *
     * Si riarma da solo quando l'audio torna: silenziare deve costare un tocco,
     * non la sorveglianza di tutta la notte.
     */
    private var alarmSilenced = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var startedAt = 0L
    private var nurseryId: String? = null
    private var nurseryName: String? = null

    /** Da quando l'audio non arriva più, o zero se sta arrivando. */
    private var silentSince = 0L

    /**
     * Se l'audio e arrivato almeno una volta.
     *
     * Distingue l'apertura della prima sessione da un recupero: sono la stessa
     * sequenza di tentativi, ma dire "sto riprovando" a chi ha appena acceso
     * l'interruttore sembra un guasto.
     */
    private var everFlowed = false

    /** Quanto aspettare prima del prossimo tentativo, raddoppiando. */
    private var backoffMs = FIRST_RETRY_MS
    private var nextAttemptAt = 0L

    /** Quando è partito il tentativo in corso, per non interromperlo a metà. */
    private var attemptStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        store = DeviceStore(this)
        client = HubClient(scope)
        alerter = Alerter(this, scope)

        transport = WebRtcTransport(
            context = this,
            role = Role.PARENT,
            sendSignal = { peerId, payload -> client.send(HubProtocol.signal(peerId, payload)) },
        )

        scope.launch {
            client.state.collect { state ->
                Log.i(TAG, "Hub (servizio): $state")
                ContinuousListening.setConnection(state)
                if (state is ConnectionState.Connected) {
                    // Riconnessi all'Hub: la vecchia sessione media non esiste
                    // più, va richiesta da capo al prossimo giro di watchdog.
                    silentSince = System.currentTimeMillis()
                    deliverFcmToken()
                }
                updateNotification()
            }
        }

        scope.launch {
            client.messages.collect { message ->
                ContinuousListening.publish(message)
                handle(message)
            }
        }

        scope.launch {
            transport?.state?.collect { updateNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            store.continuousListening = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SILENCE) {
            stopAlarm()
            alarmSilenced = true
            updateNotification()
            return START_STICKY
        }

        val url = store.hubUrl
        val token = store.deviceToken
        if (url == null || token == null) {
            Log.e(TAG, "dispositivo non accoppiato, non parto")
            stopSelf()
            return START_NOT_STICKY
        }

        if (startedAt != 0L) return START_STICKY

        startedAt = System.currentTimeMillis()
        ContinuousListening.setActive(true, startedAt)
        ContinuousListening.setHealth(ContinuousListening.Health.STARTING)

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        acquireWakeLock()
        requestAudioFocus()
        watchNetwork()
        client.connect(url, token)
        scope.launch { watchdog() }

        return START_STICKY
    }

    override fun onDestroy() {
        stopAlarm()
        // In modo bloccante e non in una coroutine: cancellare lo scope subito
        // dopo ucciderebbe la chiusura prima che parta, e il Nursery Node
        // resterebbe a trasmettere verso nessuno. Sono chiamate sincrone.
        runBlocking { transport?.stop() }
        transport = null
        abandonAudioFocus()
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        networkCallback = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        client.disconnect()
        ContinuousListening.setActive(false)
        AlertNotifier(this).clearWatching()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Sorveglianza della sessione ---

    /**
     * Controlla che l'audio stia davvero arrivando, e rimedia se non arriva.
     *
     * Gira anche mentre la sessione è in piedi: è il solo modo di accorgersi di
     * una connessione che regge ma non trasporta nulla.
     */
    private suspend fun watchdog() {
        while (true) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            val peer = nurseryId

            if (peer == null || client.state.value !is ConnectionState.Connected) {
                // Senza Hub, o senza Nursery, non c'è nessuno a cui chiedere la
                // sessione. Non è un guasto dello stream, ed è già raccontato
                // altrove: qui si aspetta e basta.
                silentSince = if (silentSince == 0L) now else silentSince
                continue
            }

            val streaming = transport?.state?.value is TransportState.Streaming
            val lastFrame = StreamLevel.lastFrameAtMs
            val flowing = streaming && lastFrame != 0L && now - lastFrame < SILENCE_LIMIT_MS

            if (flowing) {
                everFlowed = true
                onAudioFlowing()
                continue
            }

            if (silentSince == 0L) silentSince = now
            val silentFor = now - silentSince

            ContinuousListening.setHealth(
                when {
                    silentFor >= ALARM_AFTER_MS -> ContinuousListening.Health.LOST
                    everFlowed -> ContinuousListening.Health.RECOVERING
                    else -> ContinuousListening.Health.STARTING
                },
            )

            // L'allarme e per l'audio che si e interrotto, non per una sessione
            // che non e mai partita: quella la sta guardando chi l'ha accesa.
            if (silentFor >= ALARM_AFTER_MS && everFlowed) startAlarm()

            // Aprire una sessione richiede qualche secondo: piu' del primo
            // intervallo di backoff. Senza questa guardia il watchdog buttava
            // giu' sessioni a un soffio dal riuscire e ricominciava da capo, per
            // sempre. Si aspetta che WebRTC dichiari lui il fallimento.
            val negotiating = transport?.state?.value is TransportState.Connecting &&
                now - attemptStartedAt < ESTABLISH_TIMEOUT_MS

            if (!negotiating && now >= nextAttemptAt) {
                Log.i(TAG, "sessione muta da ${silentFor}ms, riprovo")
                attemptStartedAt = now
                reopenSession(peer)
                nextAttemptAt = now + backoffMs
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_MS)
            }

            updateNotification()
        }
    }

    private fun onAudioFlowing() {
        if (silentSince == 0L && ContinuousListening.health.value == ContinuousListening.Health.LISTENING) {
            return
        }
        silentSince = 0L
        backoffMs = FIRST_RETRY_MS
        nextAttemptAt = 0L
        // L'audio è tornato: il prossimo guasto deve poter suonare di nuovo.
        alarmSilenced = false
        stopAlarm()
        ContinuousListening.setHealth(ContinuousListening.Health.LISTENING)
        updateNotification()
    }

    /**
     * Chiude e riapre la sessione invece di fare un ICE restart.
     *
     * Il Nursery Node rioffre a ogni richiesta, quindi una sessione nuova
     * ottiene lo stesso risultato senza rinegoziare a mano, e ripulisce anche i
     * casi in cui il guasto non è nel trasporto ma nella sorgente.
     */
    private suspend fun reopenSession(peer: String) {
        transport?.stop()
        // Solo audio: al buio il video non mostra nulla e in una notte intera
        // costerebbe la batteria di entrambi i telefoni.
        transport?.start(StreamRequest(peerId = peer, video = false))
            ?.onFailure { Log.e(TAG, "riapertura fallita: ${it.message}") }
    }

    /**
     * Consegna il token FCM se il ViewModel non ha potuto farlo.
     *
     * Con l'ascolto continuo acceso la connessione e qui, e senza questo il
     * token di un'app appena installata resterebbe in sospeso finche qualcuno
     * non spegne l'ascolto — cioe proprio quando le push servono di meno.
     */
    private fun deliverFcmToken() {
        val token = store.pendingFcmToken ?: return
        if (token == store.sentFcmToken) return
        if (client.send(HubProtocol.fcmToken(token))) {
            store.sentFcmToken = token
            Log.i(TAG, "token FCM consegnato all'Hub")
        }
    }

    private fun handle(message: HubMessage) {
        val notifier = AlertNotifier(this)

        when (message) {
            is HubMessage.Noise -> {
                if (!SeenEvents.markSeen(message.id)) return
                notifier.notifyNoise(message.id)
                alerter.alert(vibrate = store.vibrateOnAlert, flash = store.flashOnAlert)
            }

            is HubMessage.NurseryOnline -> {
                Log.i(TAG, "nursery online: ${message.nurseryName}")
                // Riparte subito, senza scontare il backoff accumulato mentre
                // non c'era nessuno da chiamare. E senza contare quel tempo come
                // silenzio, o l'allarme ripartirebbe all'istante.
                backoffMs = FIRST_RETRY_MS
                nextAttemptAt = 0L
                silentSince = System.currentTimeMillis()
                if (ContinuousListening.health.value == ContinuousListening.Health.NURSERY_GONE) {
                    ContinuousListening.setHealth(ContinuousListening.Health.RECOVERING)
                }
                nurseryId = message.nurseryId
                nurseryName = message.nurseryName
                SeenEvents.forgetOffline(message.nurseryId)
                notifier.clearNurseryGone()
                notifier.notifyWatching(message.nurseryName, message.at)
                updateNotification()
            }

            is HubMessage.NurseryOffline -> {
                Log.w(TAG, "nursery offline (${message.reason}): chiudo la sessione")
                // Nessuno sta più sorvegliando: è la cosa più importante che
                // questo sistema possa dire, e va detta suonando.
                ContinuousListening.setHealth(ContinuousListening.Health.NURSERY_GONE)
                startAlarm()
                updateNotification()
                notifier.clearWatching()
                if (SeenEvents.markSeen("offline:${message.nurseryId}")) {
                    notifier.notifyNurseryGone(message.reason)
                    alerter.alert(vibrate = store.vibrateOnAlert, flash = store.flashOnAlert)
                }
                // Sparito il Nursery Node non c'è sessione da riaprire, e
                // insistere riempirebbe i log di tentativi senza destinatario.
                nurseryId = null
                scope.launch { transport?.stop() }
            }

            is HubMessage.Signal -> scope.launch {
                transport?.onSignal(message.from, message.payload)
            }

            is HubMessage.SignalUndelivered -> {
                Log.w(TAG, "signaling non consegnato: ${message.reason}")
            }

            else -> Unit
        }
    }

    // --- Audio, energia, allarme ---

    /**
     * Riprova appena la rete cambia, invece di aspettare il prossimo tentativo.
     *
     * Dopo un minuto di guasto il backoff è a trenta secondi: senza questo, un
     * telefono che rientra nel Wi-Fi di casa resterebbe muto per mezzo minuto
     * buono pur avendo già tutto quello che gli serve.
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (backoffMs == FIRST_RETRY_MS && nextAttemptAt == 0L) return
                Log.i(TAG, "rete disponibile: riprovo subito")
                backoffMs = FIRST_RETRY_MS
                nextAttemptAt = 0L
            }
        }
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { Log.e(TAG, "rete non sorvegliata: ${it.message}") }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /**
     * Chiede l'audio al sistema, e lo restituisce quando serve a qualcun altro.
     *
     * Una telefonata o una sveglia devono poter parlare: l'ascolto si silenzia
     * e riprende da solo. La sessione però resta aperta, perché rinegoziarla a
     * ogni chiamata costerebbe secondi di silenzio vero.
     */
    private fun requestAudioFocus() {
        val manager = getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> setPlayback(true)

                    // Anche la perdita definitiva silenzia soltanto. Chiudere
                    // l'ascolto perche un'altra app ha preso l'audio lascerebbe
                    // un genitore convinto di stare sorvegliando.
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> setPlayback(false)
                }
            }
            .build()

        focusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun setPlayback(enabled: Boolean) {
        scope.launch { transport?.setPlaybackEnabled(enabled) }
        ContinuousListening.setHealth(
            if (enabled) ContinuousListening.Health.LISTENING else ContinuousListening.Health.PAUSED,
        )
        updateNotification()
    }

    private fun abandonAudioFocus() {
        val manager = getSystemService(AudioManager::class.java)
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /**
     * L'allarme di ultima istanza.
     *
     * Suona sul canale delle sveglie, non su quello multimediale: è l'unico che
     * resta udibile con il volume della musica abbassato, che è come sta un
     * telefono sul comodino.
     */
    private fun startAlarm() {
        if (alarm != null || alarmSilenced) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        alarm = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@ListenService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.e(TAG, "allarme non riprodotto: ${it.message}") }.getOrNull()

        alerter.alert(vibrate = true, flash = store.flashOnAlert)
        Log.w(TAG, "audio fermo da oltre ${ALARM_AFTER_MS}ms: allarme")
    }

    private fun stopAlarm() {
        alarm?.runCatching {
            stop()
            release()
        }
        alarm = null
    }

    // --- Notifica ---

    private fun updateNotification() {
        if (startedAt == 0L) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_listening),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.channel_listening_description)
                    setShowBadge(false)
                },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val room = nurseryName
        val title = if (room != null) {
            getString(R.string.listening_title_room, room)
        } else {
            getString(R.string.listening_title)
        }

        val silence = PendingIntent.getService(
            this,
            2,
            Intent(this, ListenService::class.java).setAction(ACTION_SILENCE),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(healthText()))
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(open)

        // Prima azione quella che serve subito: a sveglia che suona, zittirla.
        if (alarm != null) {
            builder.addAction(
                Notification.Action.Builder(null, getString(R.string.listening_silence), silence)
                    .build(),
            )
        }

        return builder
            .addAction(
                Notification.Action.Builder(null, getString(R.string.monitoring_stop), stop).build(),
            )
            // Il tempo trascorso è la prova a colpo d'occhio che l'ascolto non
            // si è fermato durante la notte.
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setOngoing(true)
            .build()
    }

    private fun healthText(): Int = when (ContinuousListening.health.value) {
        ContinuousListening.Health.STARTING -> R.string.listening_starting
        ContinuousListening.Health.LISTENING -> R.string.listening_ok
        ContinuousListening.Health.RECOVERING -> R.string.listening_recovering
        ContinuousListening.Health.LOST -> R.string.listening_lost
        ContinuousListening.Health.PAUSED -> R.string.listening_paused
        ContinuousListening.Health.NURSERY_GONE -> R.string.listening_gone
    }

    companion object {
        private const val TAG = "CryLogListen"
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 5
        private const val WAKE_LOCK_TAG = "crylog:listening"
        const val ACTION_STOP = "it.biagini.crylog.STOP_LISTENING"
        const val ACTION_SILENCE = "it.biagini.crylog.SILENCE_ALARM"

        private const val TICK_MS = 2_000L

        /** Oltre questo silenzio la sessione si considera ferma, non tranquilla. */
        private const val SILENCE_LIMIT_MS = 10_000L

        /** Quanto si insiste prima di svegliare qualcuno. */
        private const val ALARM_AFTER_MS = 30_000L

        /**
         * Quanto si lascia a una sessione per formarsi prima di rinunciarci.
         *
         * WebRTC dichiara da solo il fallimento in una quindicina di secondi:
         * questa soglia gli sta appena oltre, cosi' e' lui a decidere e non un
         * timer che non sa nulla di ICE.
         */
        private const val ESTABLISH_TIMEOUT_MS = 20_000L

        private const val FIRST_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, ListenService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }
    }
}
