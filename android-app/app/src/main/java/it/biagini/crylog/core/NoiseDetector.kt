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

package it.biagini.crylog.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Un evento di rumore riconosciuto.
 *
 * [peakDb] e [startedAt] sono quelli dell'inizio del rumore, non del momento in
 * cui l'evento viene emesso: fra i due passa la durata minima richiesta.
 */
data class NoiseEvent(
    val startedAt: Long,
    val peakDb: Double,
    val durationMs: Long,
)

/**
 * Trasforma un flusso di campioni audio in eventi.
 *
 * Esiste come interfaccia perché la soglia è la versione ingenua del problema:
 * non distingue un pianto da un camion che passa. Sostituirla con un
 * classificatore non deve toccare né la cattura audio né il resto del sistema.
 */
interface NoiseDetector {

    /** Livello dell'ultimo blocco analizzato, in dBFS. Serve alla UI per il livello dal vivo. */
    val currentLevelDb: Double

    /** Restituisce un evento quando il rumore supera la soglia abbastanza a lungo. */
    fun analyze(samples: ShortArray, length: Int, atMs: Long): NoiseEvent?

    /** Dimentica lo stato accumulato, ad esempio quando il monitoraggio riparte. */
    fun reset()
}

/**
 * Traduce fra quello che l'utente regola e quello che il rilevatore usa.
 *
 * Sono grandezze inverse: una soglia bassa scatta con poco rumore, quindi è
 * *alta* sensibilità. Esporre la soglia grezza in un cursore chiamato
 * "sensibilità" lo farebbe funzionare al contrario di ogni altro cursore.
 */
object NoiseSensitivity {

    /** Sensibilità massima: basta un fruscio. */
    const val MOST_SENSITIVE_DB = -60.0

    /** Sensibilità minima: serve un rumore forte. */
    const val LEAST_SENSITIVE_DB = -5.0

    private const val SPAN = LEAST_SENSITIVE_DB - MOST_SENSITIVE_DB

    /** Da 0 (sente poco) a 1 (sente molto) verso la soglia in dBFS. */
    fun toThresholdDb(sensitivity: Double): Double =
        LEAST_SENSITIVE_DB - sensitivity.coerceIn(0.0, 1.0) * SPAN

    fun fromThresholdDb(thresholdDb: Double): Double =
        ((LEAST_SENSITIVE_DB - thresholdDb) / SPAN).coerceIn(0.0, 1.0)

    fun asPercent(sensitivity: Double): Int = (sensitivity.coerceIn(0.0, 1.0) * 100).toInt()
}

/**
 * Rilevatore a soglia sul livello RMS.
 *
 * Tre parametri, e ognuno esiste per un errore preciso:
 * - [thresholdDb] separa il rumore dal fondo della stanza;
 * - [minDurationMs] evita che un colpo secco — una porta, un tonfo — diventi un
 *   allarme;
 * - [cooldownMs] evita che un pianto lungo generi decine di notifiche, che è il
 *   modo più veloce per insegnare a un genitore a ignorarle.
 */
class RmsNoiseDetector(
    private val thresholdDb: Double = DEFAULT_THRESHOLD_DB,
    private val minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val releaseMs: Long = DEFAULT_RELEASE_MS,
) : NoiseDetector {

    override var currentLevelDb: Double = SILENCE_DB
        private set

    private var aboveSince: Long? = null
    private var lastAboveAt: Long = 0
    private var peakDb: Double = SILENCE_DB
    private var lastEventAt: Long? = null

    override fun analyze(samples: ShortArray, length: Int, atMs: Long): NoiseEvent? {
        val level = levelDb(samples, length)
        currentLevelDb = level

        if (level < thresholdDb) {
            // Un pianto respira: pretendere che ogni singolo blocco stia sopra
            // soglia azzererebbe il conteggio a ogni inspirazione.
            if (aboveSince != null && atMs - lastAboveAt >= releaseMs) {
                aboveSince = null
                peakDb = SILENCE_DB
            }
            return null
        }

        lastAboveAt = atMs

        // In pausa dopo un evento: il rumore continua, ma non ha più nulla da dire.
        if (lastEventAt?.let { atMs - it < cooldownMs } == true) {
            aboveSince = null
            return null
        }

        val start = aboveSince ?: atMs.also { aboveSince = it }
        peakDb = max(peakDb, level)

        val duration = atMs - start
        if (duration < minDurationMs) return null

        lastEventAt = atMs
        aboveSince = null
        val event = NoiseEvent(startedAt = start, peakDb = peakDb, durationMs = duration)
        peakDb = SILENCE_DB
        return event
    }

    override fun reset() {
        aboveSince = null
        lastAboveAt = 0
        peakDb = SILENCE_DB
        lastEventAt = null
        currentLevelDb = SILENCE_DB
    }

    companion object {
        /**
         * Soglia relativa al fondo scala, non dB SPL: senza calibrare il
         * microfono non c'è modo di conoscere la pressione sonora reale.
         * Zero è il massimo che il microfono può registrare.
         */
        const val DEFAULT_THRESHOLD_DB = -35.0

        /**
         * Mezzo secondo: abbastanza da scartare un tonfo isolato, poco abbastanza
         * da non perdere un colpo di tosse o l'inizio di un pianto.
         */
        const val DEFAULT_MIN_DURATION_MS = 500L

        /** Quanto il rumore resta "in corso" dopo essere sceso sotto soglia. */
        const val DEFAULT_RELEASE_MS = 400L

        const val DEFAULT_COOLDOWN_MS = 30_000L

        /** Silenzio digitale: il logaritmo divergerebbe, quindi si taglia qui. */
        const val SILENCE_DB = -100.0

        fun levelDb(samples: ShortArray, length: Int): Double {
            if (length <= 0) return SILENCE_DB

            var sumOfSquares = 0.0
            for (i in 0 until length) {
                val sample = samples[i].toDouble()
                sumOfSquares += sample * sample
            }

            val rms = sqrt(sumOfSquares / length)
            if (rms <= 0.0) return SILENCE_DB

            return max(SILENCE_DB, 20 * log10(rms / Short.MAX_VALUE.toDouble()))
        }
    }
}
