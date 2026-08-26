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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import it.biagini.crylog.MainActivity
import it.biagini.crylog.R
import it.biagini.crylog.core.Role
import it.biagini.crylog.hub.DeviceStore

/**
 * Si occupa dei due modi in cui il Nursery Node smette di sorvegliare senza che
 * nessuno l'abbia chiesto.
 *
 * **Dopo un riavvio** non può farlo ripartire: da Android 14 un servizio in
 * foreground di tipo microfono non si avvia da BOOT_COMPLETED, ed è una
 * restrizione ragionevole — un'app che riaccende il microfono da sola, in
 * silenzio, è proprio ciò che la piattaforma vuole impedire. Resta la notifica.
 *
 * **Dopo un aggiornamento dell'app** vale la stessa regola, e la ragione e' la
 * stessa: il vincolo non riguarda BOOT_COMPLETED ma RECORD_AUDIO, che e' un
 * permesso concesso solo mentre l'app e' in uso. Da un processo in background
 * il servizio non parte, qualunque broadcast lo abbia svegliato. Provarci non
 * lascia nemmeno un errore da gestire: startForegroundService riesce e mette
 * l'avvio in coda, poi il servizio muore con una SecurityException e Android
 * mette il riavvio in castigo per mezz'ora. Peggio che non fare niente.
 *
 * Sostituire il pacchetto termina comunque il processo, quindi senza questo
 * avviso il telefono in cameretta resterebbe spento in silenzio: durante i test
 * e' successo per otto minuti senza che niente lo dicesse.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = DeviceStore(context)
        if (store.role != Role.NURSERY || !store.armed) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "riavvio con monitoraggio armato: avviso l'utente")
                notifyStopped(context, R.string.stopped_text_reboot)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "aggiornamento con monitoraggio armato: avviso l'utente")
                notifyStopped(context, R.string.stopped_text_interrupted)
            }
        }
    }

    companion object {
        private const val TAG = "CryLogBoot"
        private const val CHANNEL_ID = "monitoring-stopped"
        const val NOTIFICATION_ID = 2

        /** Chiamato quando il monitoraggio riprende: l'avviso non serve più. */
        fun clearNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }

        /**
         * Dice che la sorveglianza si è fermata senza che nessuno l'abbia
         * chiesto.
         *
         * Il silenzio ambiguo è il modo peggiore in cui questo sistema può
         * fallire: un telefono che ha smesso di ascoltare e non lo dice è
         * peggio di un telefono spento, che almeno si vede.
         */
        fun notifyInterrupted(context: Context) {
            notifyStopped(context, R.string.stopped_text_interrupted)
        }

            private fun notifyStopped(context: Context, textRes: Int) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.channel_stopped),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = context.getString(R.string.channel_stopped_description)
                        enableVibration(true)
                        setBypassDnd(true)
                    },
                )
            }

            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )

            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.stopped_title))
                    .setContentText(context.getString(textRes))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(open)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    // Resta finché non la si tocca: un avviso che sparisce da solo
                    // non serve a nulla se il telefono si è riavviato alle tre.
                    .setOngoing(true)
                    .build(),
            )
        }
    }
}
