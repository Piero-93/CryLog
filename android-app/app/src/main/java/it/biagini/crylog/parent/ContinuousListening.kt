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

import it.biagini.crylog.core.ConnectionState
import it.biagini.crylog.core.HubMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Quello che il servizio dell'ascolto continuo fa sapere al resto dell'app.
 *
 * Mentre l'ascolto continuo è acceso la connessione all'Hub appartiene al
 * servizio, non al ViewModel: deve reggere con l'app chiusa. L'Hub tiene una
 * sessione sola per dispositivo, quindi aprirne una seconda dalla UI
 * scollegherebbe proprio quella che deve restare in piedi. La UI legge da qui.
 */
object ContinuousListening {

    /** Come sta andando l'ascolto, in termini che hanno senso per chi dorme. */
    enum class Health {
        /** Sessione non ancora aperta. */
        STARTING,

        /** Arriva audio: è l'unico stato che significa davvero "sto sentendo". */
        LISTENING,

        /** L'audio si è fermato e si sta ritentando. */
        RECOVERING,

        /** Non si recupera: da qui parte l'allarme. */
        LOST,

        /** Un'altra app si è presa l'audio, per esempio una telefonata. */
        PAUSED,
    }

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Da quando l'ascolto è acceso, per il cronometro della notifica. */
    private val _since = MutableStateFlow(0L)
    val since: StateFlow<Long> = _since.asStateFlow()

    private val _health = MutableStateFlow(Health.STARTING)
    val health: StateFlow<Health> = _health.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    /**
     * I messaggi dell'Hub, ripubblicati per la UI.
     *
     * Il ViewModel costruisce la stessa schermata di sempre, solo leggendo da
     * qui invece che da una connessione propria.
     */
    private val _messages = MutableSharedFlow<HubMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<HubMessage> = _messages.asSharedFlow()

    internal fun setActive(value: Boolean, since: Long = 0L) {
        _active.value = value
        _since.value = since
        if (!value) {
            _health.value = Health.STARTING
            _connection.value = ConnectionState.Disconnected
        }
    }

    internal fun setHealth(value: Health) {
        _health.value = value
    }

    internal fun setConnection(state: ConnectionState) {
        _connection.value = state
    }

    internal fun publish(message: HubMessage) {
        _messages.tryEmit(message)
    }
}
