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
import com.google.firebase.messaging.FirebaseMessaging
import it.biagini.crylog.core.HubProtocol
import it.biagini.crylog.hub.DeviceStore
import it.biagini.crylog.hub.HubClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Consegna del token FCM all'Hub.
 *
 * Si manda a ogni sessione, senza ricordare se l'Hub lo abbia gia': l'Hub lo
 * sovrascrive e basta, mentre un "gia' inviato" tenuto qui non ha modo di
 * sapere se l'Hub lo ha davvero salvato. Un invio perso, un nuovo pairing o un
 * database ricreato lasciavano l'Hub senza token per sempre, e da quel momento
 * nessuna push partiva — senza un solo errore da nessuna parte.
 *
 * Sta fuori dal ViewModel perche' la connessione puo' essere di
 * [ListenService], e perche' [CryLogMessagingService] riceve i token nuovi
 * anche ad app chiusa.
 */
object PushToken {

    private const val TAG = "CryLogPush"

    private val _renewed = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Token nuovi arrivati mentre il processo e' vivo: chi ha il socket li consegna subito. */
    val renewed: SharedFlow<String> = _renewed.asSharedFlow()

    fun onRenewed(store: DeviceStore, token: String) {
        store.fcmToken = token
        _renewed.tryEmit(token)
    }

    /**
     * Chiede il token a Firebase e lo manda all'Hub.
     *
     * Se Firebase non risponde si manda quello salvato: e' quasi sempre ancora
     * buono, e un token vecchio all'Hub vale piu' di nessun token.
     */
    fun deliver(store: DeviceStore, client: HubClient) {
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    store.fcmToken = token
                    send(client, token)
                }
                .addOnFailureListener { failure ->
                    Log.w(TAG, "token FCM non ottenuto: ${failure.message}")
                    store.fcmToken?.let { send(client, it) }
                }
        }.onFailure {
            // Le push sono opzionali: un build senza progetto Firebase deve
            // restare utilizzabile con le sole notifiche in tempo reale.
            Log.w(TAG, "Firebase non configurato: niente notifiche in background")
        }
    }

    fun send(client: HubClient, token: String) {
        if (client.send(HubProtocol.fcmToken(token))) {
            Log.i(TAG, "token FCM inviato all'Hub")
        } else {
            Log.w(TAG, "token FCM non inviato, connessione chiusa: riprovo alla prossima")
        }
    }
}
