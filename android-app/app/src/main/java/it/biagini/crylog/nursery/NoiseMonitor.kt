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

import it.biagini.crylog.core.ConnectionState
import it.biagini.crylog.core.NoiseEvent
import it.biagini.crylog.core.TransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stato osservabile del monitoraggio, condiviso fra il servizio e la UI.
 *
 * È un singleton invece di un binder perché il servizio deve sopravvivere alla
 * UI, non il contrario: quando l'app viene chiusa il monitoraggio continua, e
 * quando la UI torna trova qui lo stato senza dover ristabilire un legame.
 */
object NoiseMonitor {

    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private val _levelDb = MutableStateFlow(-100.0)
    val levelDb: StateFlow<Double> = _levelDb.asStateFlow()

    /**
     * Ultimi trenta secondi di livello, per il grafico.
     *
     * Campionato a 5 Hz invece dei 10 blocchi al secondo che arrivano: la
     * differenza non si vede su uno schermo, e dimezza le ricomposizioni.
     */
    private val _history = MutableStateFlow(FloatArray(HISTORY_SIZE) { SILENCE })
    val history: StateFlow<FloatArray> = _history.asStateFlow()

    private var blocksSeen = 0

    const val HISTORY_SIZE = 150
    const val HISTORY_SECONDS = 30
    private const val SILENCE = -100f

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    /**
     * Quanti Parent Node stanno ascoltando in questo momento.
     *
     * Prima non aveva senso chiederselo: l'ascoltatore era uno o nessuno.
     */
    private val _listeners = MutableStateFlow(0)
    val listeners: StateFlow<Int> = _listeners.asStateFlow()

    internal fun setListeners(count: Int) {
        _listeners.value = count
    }

    private val _stream = MutableStateFlow<TransportState>(TransportState.Idle)
    val stream: StateFlow<TransportState> = _stream.asStateFlow()

    internal fun setStream(state: TransportState) {
        _stream.value = state
    }

    private val _lastEvent = MutableStateFlow<NoiseEvent?>(null)
    val lastEvent: StateFlow<NoiseEvent?> = _lastEvent.asStateFlow()

    private val _eventCount = MutableStateFlow(0)
    val eventCount: StateFlow<Int> = _eventCount.asStateFlow()

    internal fun setArmed(value: Boolean) {
        _armed.value = value
        if (!value) _listeners.value = 0
        if (!value) _levelDb.value = -100.0
    }

    internal fun setLevel(db: Double) {
        _levelDb.value = db

        if (blocksSeen++ % 2 != 0) return
        val previous = _history.value
        _history.value = FloatArray(HISTORY_SIZE) { i ->
            if (i < HISTORY_SIZE - 1) previous[i + 1] else db.toFloat()
        }
    }

    internal fun setConnection(state: ConnectionState) {
        _connection.value = state
    }

    internal fun recordEvent(event: NoiseEvent) {
        _lastEvent.value = event
        _eventCount.value += 1
    }
}
