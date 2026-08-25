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
 * Avvisa che dopo un riavvio il monitoraggio è fermo.
 *
 * Non lo fa ripartire, perché non può: da Android 14 un servizio in foreground
 * di tipo microfono non si avvia da BOOT_COMPLETED, ed è una restrizione
 * ragionevole — un'app che riaccende il microfono da sola, in silenzio, è
 * proprio ciò che la piattaforma vuole impedire.
 *
 * Il rischio vero però resta: il telefono in cameretta si riavvia di notte per
 * un aggiornamento e smette di sorvegliare senza dirlo a nessuno. Una notifica
 * insistente è il massimo che si possa fare, ed è molto meglio del silenzio.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val store = DeviceStore(context)
        if (store.role != Role.NURSERY || !store.armed) return

        Log.i(TAG, "riavvio con monitoraggio armato: avviso l'utente")
        notifyStopped(context)
    }

    private fun notifyStopped(context: Context) {
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
                .setContentText(context.getString(R.string.stopped_text))
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

    companion object {
        private const val TAG = "CryLogBoot"
        private const val CHANNEL_ID = "monitoring-stopped"
        const val NOTIFICATION_ID = 2

        /** Chiamato quando il monitoraggio riprende: l'avviso non serve più. */
        fun clearNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
