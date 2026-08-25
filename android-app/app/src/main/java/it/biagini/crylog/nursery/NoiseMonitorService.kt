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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import it.biagini.crylog.MainActivity
import it.biagini.crylog.R
import it.biagini.crylog.core.ConnectionState
import it.biagini.crylog.core.HubProtocol
import it.biagini.crylog.core.NoiseDetector
import it.biagini.crylog.core.RmsNoiseDetector
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

    override fun onCreate() {
        super.onCreate()
        store = DeviceStore(this)
        client = HubClient(scope)

        scope.launch {
            client.state.collect { NoiseMonitor.setConnection(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        detector = RmsNoiseDetector(
            thresholdDb = store.noiseThresholdDb,
            minDurationMs = store.noiseMinDurationMs,
            cooldownMs = store.noiseCooldownMs,
        )

        var blocks = 0
        val source = AudioSource { samples, length ->
            val event = detector.analyze(samples, length, System.currentTimeMillis())
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

    override fun onDestroy() {
        audio?.stop()
        audio = null
        NoiseMonitor.setArmed(false)
        client.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

        fun start(context: Context) {
            val intent = Intent(context, NoiseMonitorService::class.java)
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
