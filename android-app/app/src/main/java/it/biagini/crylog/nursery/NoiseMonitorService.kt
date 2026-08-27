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

package it.biagini.crylog.nursery

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import it.biagini.crylog.MainActivity
import it.biagini.crylog.R
import it.biagini.crylog.core.ConnectionState
import it.biagini.crylog.core.HubProtocol
import it.biagini.crylog.core.NoiseDetector
import it.biagini.crylog.core.HubMessage
import it.biagini.crylog.core.RmsNoiseDetector
import it.biagini.crylog.core.Role
import it.biagini.crylog.core.StreamTransport
import it.biagini.crylog.transport.WebRtcTransport
import it.biagini.crylog.hub.DeviceStore
import it.biagini.crylog.hub.HubClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Monitoraggio del rumore, in foreground e indipendente da tutto il resto.
 *
 * È deliberatamente separato da qualunque cosa riguardi lo streaming: un baby
 * monitor che avvisa solo mentre qualcuno sta guardando non è un baby monitor.
 * Finché il Nursery Node è armato questo servizio ascolta, anche con l'app
 * chiusa e lo schermo spento.
 */
class NoiseMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var store: DeviceStore
    private lateinit var client: HubClient
    private lateinit var detector: NoiseDetector
    private var audio: AudioSource? = null
    private var transport: StreamTransport? = null

    /**
     * Mentre il genitore parla il rilevamento tace: lo speaker riproduce la sua
     * voce e il microfono la risentirebbe, segnalandola come rumore in
     * cameretta.
     */
    @Volatile private var talkBackActive = false

    /**
     * Se la cattura e la connessione sono gia' in piedi.
     *
     * `onStartCommand` puo' essere richiamato su un servizio gia' avviato, e
     * senza questa guardia rifaceva tutto da capo: fermava la cattura audio,
     * azzerava il rilevatore — perdendo anche il suo cooldown — e riapriva la
     * connessione all'Hub. Dal lato dell'Hub quella riapertura e' un Nursery
     * Node che sparisce e torna, con l'allarme "nessuno sta sorvegliando" che
     * ne consegue: succedeva ogni pochi secondi senza che niente fosse rotto.
     */
    private var running = false

    override fun onCreate() {
        super.onCreate()
        store = DeviceStore(this)
        client = HubClient(scope)

        scope.launch {
            client.state.collect { NoiseMonitor.setConnection(it) }
        }

        // Il trasporto vive qui e non nella UI: una richiesta di ascolto deve
        // trovare risposta anche con l'app chiusa, che è la condizione normale
        // per un telefono lasciato in cameretta.
        transport = WebRtcTransport(
            context = this,
            role = Role.NURSERY,
            sendSignal = { peerId, payload -> client.send(HubProtocol.signal(peerId, payload)) },
            audioOnly = { store.audioOnly },
            onCameraInUse = ::updateServiceType,
            onListeners = NoiseMonitor::setListeners,
            onTalkBack = { active ->
                Log.i(TAG, "talk-back=$active")
                talkBackActive = active
                // Uscendo dalla sospensione il rilevatore ha ancora lo stato di
                // prima: un rumore che risultava "in corso" da dieci secondi
                // supererebbe subito la durata minima e diventerebbe un evento
                // che nessuno ha mai sentito.
                if (!active && ::detector.isInitialized) detector.reset()
            },
        )

        scope.launch {
            client.messages.collect { message ->
                if (message is HubMessage.Signal) {
                    transport?.onSignal(message.from, message.payload)
                }
            }
        }

        scope.launch {
            transport?.state?.collect { NoiseMonitor.setStream(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Solo microfono: dichiarare anche "camera" qui costringerebbe a
        // possedere il permesso fotocamera per il semplice monitoraggio, e
        // Android rifiuterebbe l'avvio del servizio. Il tipo si alza soltanto
        // quando la fotocamera serve davvero.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        // Le regolazioni si applicano a caldo. Fermare e riavviare il servizio
        // faceva cadere il WebSocket, e per l'Hub un Nursery Node che sparisce e
        // torna e' un Nursery Node offline: cambiare la sensibilita' mandava un
        // falso allarme a tutti i Parent, e faceva scattare qui la notifica
        // "non sto sorvegliando". Il rilevatore invece si ricrea davvero,
        // perche' soglia e tempi sono suoi.
        if (intent?.action == ACTION_RELOAD) {
            if (running) {
                detector = buildDetector()
                Log.i(TAG, "impostazioni ricaricate senza interrompere l'ascolto")
                return START_STICKY
            }
        }

        // Gia' in ascolto: non c'e' niente da rifare. Le impostazioni si
        // applicano fermando e riavviando il servizio, e quel percorso passa da
        // onDestroy, che rimette `running` a false.
        if (running) return START_STICKY
        running = true

        // Se comunque restasse una cattura di un avvio precedente va chiusa:
        // due AudioRecord sullo stesso microfono, e solo l'ultimo verrebbe poi
        // fermato.
        audio?.stop()
        audio = null

        detector = buildDetector()

        var blocks = 0
        val source = AudioSource { samples, length ->
            val event = if (talkBackActive) null else detector.analyze(samples, length, System.currentTimeMillis())
            NoiseMonitor.setLevel(detector.currentLevelDb)
            // Un livello ogni due secondi: abbastanza per capire dai log perche
            // un rumore non e stato rilevato, senza inondare logcat.
            if (blocks++ % 20 == 0) {
                Log.d(TAG, "livello %.1f dBFS (soglia %.1f)".format(detector.currentLevelDb, store.noiseThresholdDb))
            }
            if (event != null) {
                NoiseMonitor.recordEvent(event)
                val sent = client.send(
                    HubProtocol.noise(
                        startedAt = event.startedAt,
                        endedAt = event.startedAt + event.durationMs,
                        peakDb = event.peakDb,
                    ),
                )
                Log.i(TAG, "rumore a ${"%.1f".format(event.peakDb)} dB, inviato=$sent")
            }
        }

        if (!source.start()) {
            Log.e(TAG, "cattura audio non avviata, mi fermo")
            stopSelf()
            return START_NOT_STICKY
        }

        audio = source
        NoiseMonitor.setArmed(true)
        // Ripreso il lavoro, l'avviso di sorveglianza ferma non descrive più la
        // realtà. Qui e non in chi chiama: questo è l'unico punto che sa di
        // essere davvero partito.
        BootReceiver.clearNotification(this)

        val url = store.hubUrl
        val token = store.deviceToken
        if (url != null && token != null) {
            client.connect(url, token)
        } else {
            NoiseMonitor.setConnection(ConnectionState.Failed("dispositivo non accoppiato"))
        }

        // START_STICKY: se il sistema ci uccide per memoria, deve riprovare.
        return START_STICKY
    }

    private fun buildDetector(): NoiseDetector = RmsNoiseDetector(
        thresholdDb = store.noiseThresholdDb,
        minDurationMs = store.noiseMinDurationMs,
        cooldownMs = store.noiseCooldownMs,
    )

    override fun onDestroy() {
        running = false
        // Se `armed` è ancora acceso, nessuno ha chiesto di smettere: il
        // servizio sta morendo per conto suo, e va detto.
        if (store.armed) {
            Log.w(TAG, "monitoraggio interrotto senza richiesta: avviso")
            BootReceiver.notifyInterrupted(this)
        }
        audio?.stop()
        audio = null
        scope.launch { transport?.stop() }
        transport = null
        NoiseMonitor.setArmed(false)
        client.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Dichiara ad Android che la fotocamera è in uso, e solo per il tempo in cui
     * lo è davvero.
     *
     * Il tipo va alzato *prima* di aprire la fotocamera e riabbassato quando si
     * chiude: tenerlo sempre alto obbligherebbe a possedere il permesso anche
     * per il solo monitoraggio audio.
     */
    private fun updateServiceType(cameraInUse: Boolean) {
        val allowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        val type = if (cameraInUse && allowed) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        runCatching { startForeground(NOTIFICATION_ID, buildNotification(), type) }
            .onFailure { Log.e(TAG, "tipo del servizio non aggiornato: ${it.message}") }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_monitoring),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.channel_monitoring_description)
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
            Intent(this, NoiseMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.monitoring_title))
            .setContentText(getString(R.string.monitoring_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.monitoring_stop), stop).build(),
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CryLogMonitor"
        private const val CHANNEL_ID = "monitoring"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "it.biagini.crylog.STOP_MONITORING"
        const val ACTION_RELOAD = "it.biagini.crylog.RELOAD_SETTINGS"

        fun start(context: Context) {
            val intent = Intent(context, NoiseMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Applica le regolazioni senza interrompere l'ascolto. */
        fun reload(context: Context) {
            val intent = Intent(context, NoiseMonitorService::class.java)
                .setAction(ACTION_RELOAD)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NoiseMonitorService::class.java))
        }
    }
}
