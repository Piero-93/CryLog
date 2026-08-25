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
import android.content.Context
import android.content.Intent
import it.biagini.crylog.MainActivity
import it.biagini.crylog.R

/**
 * La notifica visibile di un evento di rumore.
 *
 * Il testo è deliberatamente generico: la push porta solo un identificativo,
 * senza nome del Nursery Node né livello, perché quel messaggio passa dai
 * server di Google. Chi apre l'app trova i dettagli, che restano in casa.
 */
class AlertNotifier(private val context: Context) {

    fun notifyNoise(eventId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.alert_title))
            .setContentText(context.getString(R.string.alert_text))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()

        // Un id stabile per evento: se la stessa notifica arriva due volte
        // sostituisce la precedente invece di impilarsi.
        manager.notify(eventId.hashCode(), notification)
    }

    /**
     * Il Nursery Node non risponde più.
     *
     * Resta finché non la si tocca: un avviso che sparisce da solo non serve
     * a nulla se arriva alle tre di notte, e la mancanza di sorveglianza non
     * si risolve da sé come un pianto.
     */
    fun notifyNurseryGone(reason: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)

        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val text = when (reason) {
            "timeout" -> context.getString(R.string.gone_text_timeout)
            else -> context.getString(R.string.gone_text_disconnected)
        }

        manager.notify(
            GONE_NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.gone_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .build(),
        )
    }

    /** Quando il Nursery Node torna, l'allarme non ha più ragione di esistere. */
    fun clearNurseryGone() {
        context.getSystemService(NotificationManager::class.java)?.cancel(GONE_NOTIFICATION_ID)
    }

    /**
     * Chi sta sorvegliando e da quanto.
     *
     * Dice lo stato del **Nursery Node**, non di questo telefono: qui non gira
     * nulla che sorvegli, e una notifica che dicesse "sto monitorando" mentre
     * l'app è ferma rassicurerebbe senza fondamento — il difetto peggiore che
     * un baby monitor possa avere.
     *
     * Il cronometro lo aggiorna Android da solo: nessun servizio necessario.
     */
    fun notifyWatching(nurseryName: String, since: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureStatusChannel(manager)

        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        manager.notify(
            WATCHING_NOTIFICATION_ID,
            Notification.Builder(context, STATUS_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.watching_title, nurseryName))
                .setContentText(context.getString(R.string.watching_text))
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(open)
                .setWhen(since)
                .setUsesChronometer(true)
                .setOngoing(true)
                .setShowWhen(true)
                .build(),
        )
    }

    /** Va tolta appena non si sa più se qualcuno stia sorvegliando. */
    fun clearWatching() {
        context.getSystemService(NotificationManager::class.java)?.cancel(WATCHING_NOTIFICATION_ID)
    }

    private fun ensureStatusChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(STATUS_CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                STATUS_CHANNEL_ID,
                context.getString(R.string.channel_status),
                // Silenziosa: è uno stato, non un avviso. Deve stare lì senza
                // farsi notare.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_status_description)
                setShowBadge(false)
                enableVibration(false)
            },
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_alert),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_alert_description)
                enableVibration(true)
                setBypassDnd(true)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "alert"
        const val GONE_NOTIFICATION_ID = 3
        const val STATUS_CHANNEL_ID = "status"
        const val WATCHING_NOTIFICATION_ID = 4
    }
}
