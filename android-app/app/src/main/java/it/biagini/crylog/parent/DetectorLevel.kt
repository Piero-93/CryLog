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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Il livello che vede il rilevatore del Nursery Node, con la sua soglia.
 *
 * Non e' [StreamLevel]: quello misura l'audio arrivato qui, dopo il codec e
 * l'elaborazione di WebRTC, e non si confronta con la soglia. Un suono sopra
 * la linea che non aveva fatto scattare niente, o il contrario, avrebbe reso
 * il grafico peggio che inutile per regolare la sensibilita'. Questi valori
 * sono gli stessi che il Nursery disegna sul suo schermo.
 */
object DetectorLevel {

    private const val SILENCE = -100f

    private val _history = MutableStateFlow(FloatArray(StreamLevel.HISTORY_SIZE) { SILENCE })
    val history: StateFlow<FloatArray> = _history.asStateFlow()

    private val _levelDb = MutableStateFlow(SILENCE.toDouble())
    val levelDb: StateFlow<Double> = _levelDb.asStateFlow()

    /** Nulla finche' il Nursery Node non ne ha mandata una: un Nursery vecchio non la manda mai. */
    private val _thresholdDb = MutableStateFlow<Double?>(null)
    val thresholdDb: StateFlow<Double?> = _thresholdDb.asStateFlow()

    /** Quando e' arrivato l'ultimo, o zero: un canale che tace non va mostrato come vivo. */
    @Volatile
    var lastAtMs: Long = 0L
        private set

    /** Il Nursery ne manda cinque al secondo, quanti ne servono al grafico. */
    fun push(levelDb: Double, thresholdDb: Double) {
        lastAtMs = System.currentTimeMillis()
        _levelDb.value = levelDb
        _thresholdDb.value = thresholdDb
        val previous = _history.value
        _history.value = FloatArray(previous.size) { i ->
            if (i < previous.size - 1) previous[i + 1] else levelDb.toFloat()
        }
    }

    fun reset() {
        lastAtMs = 0L
        _levelDb.value = SILENCE.toDouble()
        _thresholdDb.value = null
        _history.value = FloatArray(StreamLevel.HISTORY_SIZE) { SILENCE }
    }
}
