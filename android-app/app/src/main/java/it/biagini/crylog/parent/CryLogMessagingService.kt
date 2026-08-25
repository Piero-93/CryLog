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

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import it.biagini.crylog.core.Role
import it.biagini.crylog.hub.DeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Riceve le push quando l'app è chiusa o il telefono dorme.
 *
 * È l'unico percorso che sopravvive al Doze: senza, di notte il Parent Node
 * non saprebbe nulla finché qualcuno non riapre l'app — cioè proprio quando
 * servirebbe di più.
 */
class CryLogMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob())

    override fun onMessageReceived(message: RemoteMessage) {
        val store = DeviceStore(this)
        if (store.role != Role.PARENT) return

        when (message.data["type"]) {
            "noise" -> {
                val eventId = message.data["eventId"] ?: return

                // Se il WebSocket ha già consegnato questo evento, la push è
                // arrivata seconda e non deve far vibrare una seconda volta.
                if (!SeenEvents.markSeen(eventId)) {
                    Log.d(TAG, "evento $eventId già visto, push ignorata")
                    return
                }

                AlertNotifier(this).notifyNoise(eventId)
                Alerter(this, scope).alert(
                    vibrate = store.vibrateOnAlert,
                    flash = store.flashOnAlert,
                )
            }

            // Più grave di un rumore: da questo momento nessuno sta
            // sorvegliando, e senza push il genitore lo scoprirebbe domattina.
            "nursery-offline" -> {
                val nurseryId = message.data["nurseryId"] ?: return
                if (!SeenEvents.markSeen("offline:$nurseryId")) return

                AlertNotifier(this).notifyNurseryGone(message.data["reason"])
                Alerter(this, scope).alert(
                    vibrate = store.vibrateOnAlert,
                    flash = store.flashOnAlert,
                )
            }

            else -> Log.d(TAG, "push di tipo sconosciuto, ignorata")
        }
    }

    /**
     * Il token cambia da solo: reinstallazione, ripristino da backup, pulizia
     * dei dati. Va salvato subito, e l'app lo consegnerà all'Hub appena
     * riesce a parlargli.
     */
    override fun onNewToken(token: String) {
        DeviceStore(this).pendingFcmToken = token
        Log.i(TAG, "nuovo token FCM, in attesa di consegnarlo all'Hub")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "CryLogPush"
    }
}
