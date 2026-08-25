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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class NoiseDetectorTest {

    private val silence = ShortArray(1024)

    /** Onda sinusoidale a un'ampiezza data, come frazione del fondo scala. */
    private fun tone(amplitude: Double, size: Int = 1024): ShortArray =
        ShortArray(size) { i ->
            (sin(2 * Math.PI * i / 32) * amplitude * Short.MAX_VALUE).toInt().toShort()
        }

    @Test
    fun `il silenzio digitale non diverge`() {
        assertEquals(RmsNoiseDetector.SILENCE_DB, RmsNoiseDetector.levelDb(silence, silence.size), 0.001)
    }

    @Test
    fun `un buffer vuoto non fa esplodere il calcolo`() {
        assertEquals(RmsNoiseDetector.SILENCE_DB, RmsNoiseDetector.levelDb(ShortArray(0), 0), 0.001)
        assertEquals(RmsNoiseDetector.SILENCE_DB, RmsNoiseDetector.levelDb(silence, 0), 0.001)
    }

    @Test
    fun `il fondo scala e vicino a zero dB`() {
        val level = RmsNoiseDetector.levelDb(tone(1.0), 1024)
        assertTrue("atteso vicino a 0 dBFS, ottenuto $level", level > -4.0 && level <= 0.0)
    }

    @Test
    fun `dimezzare l ampiezza toglie circa sei dB`() {
        val loud = RmsNoiseDetector.levelDb(tone(0.5), 1024)
        val half = RmsNoiseDetector.levelDb(tone(0.25), 1024)
        assertEquals(6.0, loud - half, 0.5)
    }

    @Test
    fun `il silenzio non genera eventi`() {
        val detector = RmsNoiseDetector()
        repeat(100) { i ->
            assertNull(detector.analyze(silence, silence.size, i * 100L))
        }
    }

    @Test
    fun `un rumore breve non genera eventi`() {
        val detector = RmsNoiseDetector(minDurationMs = 1_500)
        val loud = tone(0.5)

        // Mezzo secondo di rumore forte: un tonfo, non un pianto.
        assertNull(detector.analyze(loud, loud.size, 0))
        assertNull(detector.analyze(loud, loud.size, 500))
        assertNull(detector.analyze(silence, silence.size, 600))
        assertNull(detector.analyze(silence, silence.size, 2_000))
    }

    @Test
    fun `un rumore prolungato genera un evento`() {
        val detector = RmsNoiseDetector(minDurationMs = 1_500)
        val loud = tone(0.5)

        assertNull(detector.analyze(loud, loud.size, 0))
        assertNull(detector.analyze(loud, loud.size, 1_000))

        val event = detector.analyze(loud, loud.size, 1_600)
        assertNotNull(event)
        assertEquals(0L, event!!.startedAt)
        assertTrue("la durata deve coprire la soglia", event.durationMs >= 1_500)
        assertTrue("il picco deve essere sopra soglia", event.peakDb > -35.0)
    }

    @Test
    fun `il rumore deve essere continuo, non a sprazzi`() {
        val detector = RmsNoiseDetector(minDurationMs = 1_500)
        val loud = tone(0.5)

        detector.analyze(loud, loud.size, 0)
        // Un attimo di silenzio azzera il conteggio.
        detector.analyze(silence, silence.size, 800)
        assertNull(detector.analyze(loud, loud.size, 1_600))
        assertNull(detector.analyze(loud, loud.size, 2_000))

        assertNotNull("il conteggio riparte dal secondo rumore", detector.analyze(loud, loud.size, 3_200))
    }

    @Test
    fun `un pianto lungo non genera una raffica di eventi`() {
        val detector = RmsNoiseDetector(minDurationMs = 1_000, cooldownMs = 30_000)
        val loud = tone(0.5)

        detector.analyze(loud, loud.size, 0)
        assertNotNull(detector.analyze(loud, loud.size, 1_100))

        // Il bambino continua a piangere per mezzo minuto.
        var extra = 0
        for (t in 2_000L..30_000L step 500) {
            if (detector.analyze(loud, loud.size, t) != null) extra++
        }
        assertEquals("durante la pausa non deve arrivare nulla", 0, extra)
    }

    @Test
    fun `passata la pausa un nuovo rumore viene segnalato`() {
        val detector = RmsNoiseDetector(minDurationMs = 1_000, cooldownMs = 10_000)
        val loud = tone(0.5)

        detector.analyze(loud, loud.size, 0)
        assertNotNull(detector.analyze(loud, loud.size, 1_100))

        assertNull(detector.analyze(loud, loud.size, 5_000))

        detector.analyze(loud, loud.size, 12_000)
        assertNotNull(detector.analyze(loud, loud.size, 13_100))
    }

    @Test
    fun `una soglia piu alta ignora i rumori moderati`() {
        val sensitive = RmsNoiseDetector(thresholdDb = -40.0, minDurationMs = 500)
        val strict = RmsNoiseDetector(thresholdDb = -6.0, minDurationMs = 500)
        val moderate = tone(0.1)

        sensitive.analyze(moderate, moderate.size, 0)
        assertNotNull(sensitive.analyze(moderate, moderate.size, 600))

        strict.analyze(moderate, moderate.size, 0)
        assertNull(strict.analyze(moderate, moderate.size, 600))
    }

    @Test
    fun `il picco riportato e il piu alto del periodo, non l ultimo`() {
        val detector = RmsNoiseDetector(minDurationMs = 1_000)

        detector.analyze(tone(0.8), 1024, 0)
        detector.analyze(tone(0.2), 1024, 500)
        val event = detector.analyze(tone(0.2), 1024, 1_100)

        assertNotNull(event)
        val quiet = RmsNoiseDetector.levelDb(tone(0.2), 1024)
        assertTrue(
            "atteso il picco di 0.8 (${event!!.peakDb}), non il livello finale ($quiet)",
            event.peakDb > quiet + 5,
        )
    }

    @Test
    fun `il livello corrente segue l ultimo blocco analizzato`() {
        val detector = RmsNoiseDetector()

        detector.analyze(silence, silence.size, 0)
        assertEquals(RmsNoiseDetector.SILENCE_DB, detector.currentLevelDb, 0.001)

        detector.analyze(tone(0.5), 1024, 100)
        assertTrue(detector.currentLevelDb > -20.0)
    }

    @Test
    fun `reset dimentica anche la pausa in corso`() {
        val detector = RmsNoiseDetector(minDurationMs = 1_000, cooldownMs = 30_000)
        val loud = tone(0.5)

        detector.analyze(loud, loud.size, 0)
        assertNotNull(detector.analyze(loud, loud.size, 1_100))

        detector.reset()

        detector.analyze(loud, loud.size, 2_000)
        assertNotNull("dopo il reset la pausa non vale più", detector.analyze(loud, loud.size, 3_100))
    }

    @Test
    fun `la sensibilita massima corrisponde alla soglia piu bassa`() {
        assertEquals(NoiseSensitivity.MOST_SENSITIVE_DB, NoiseSensitivity.toThresholdDb(1.0), 0.001)
        assertEquals(NoiseSensitivity.LEAST_SENSITIVE_DB, NoiseSensitivity.toThresholdDb(0.0), 0.001)
    }

    @Test
    fun `alzare la sensibilita abbassa la soglia`() {
        // Il cursore va a destra, la soglia scende: e la ragione per cui esiste
        // questa conversione invece di esporre la soglia grezza.
        val poco = NoiseSensitivity.toThresholdDb(0.2)
        val molto = NoiseSensitivity.toThresholdDb(0.8)
        assertTrue("piu sensibile deve significare soglia piu bassa", molto < poco)
    }

    @Test
    fun `la conversione e reversibile`() {
        for (percent in 0..100) {
            val sensitivity = percent / 100.0
            val roundTrip = NoiseSensitivity.fromThresholdDb(NoiseSensitivity.toThresholdDb(sensitivity))
            assertEquals(sensitivity, roundTrip, 0.001)
        }
    }

    @Test
    fun `valori fuori scala vengono riportati nei limiti`() {
        assertEquals(NoiseSensitivity.MOST_SENSITIVE_DB, NoiseSensitivity.toThresholdDb(5.0), 0.001)
        assertEquals(NoiseSensitivity.LEAST_SENSITIVE_DB, NoiseSensitivity.toThresholdDb(-3.0), 0.001)
        assertEquals(1.0, NoiseSensitivity.fromThresholdDb(-200.0), 0.001)
        assertEquals(0.0, NoiseSensitivity.fromThresholdDb(50.0), 0.001)
    }

    @Test
    fun `la percentuale mostrata segue il cursore`() {
        assertEquals(0, NoiseSensitivity.asPercent(0.0))
        assertEquals(50, NoiseSensitivity.asPercent(0.5))
        assertEquals(100, NoiseSensitivity.asPercent(1.0))
    }

    @Test
    fun `una breve pausa non azzera il conteggio`() {
        val detector = RmsNoiseDetector(minDurationMs = 1_000, releaseMs = 400)
        val loud = tone(0.5)

        // Un pianto respira: fra un grido e l altro ci sono attimi di silenzio.
        detector.analyze(loud, loud.size, 0)
        detector.analyze(silence, silence.size, 200)
        detector.analyze(loud, loud.size, 400)
        detector.analyze(silence, silence.size, 700)

        assertNotNull(
            "le pause piu brevi del rilascio non devono azzerare",
            detector.analyze(loud, loud.size, 1_100),
        )
    }

    @Test
    fun `una pausa lunga azzera il conteggio`() {
        val detector = RmsNoiseDetector(minDurationMs = 1_000, releaseMs = 400)
        val loud = tone(0.5)

        detector.analyze(loud, loud.size, 0)
        detector.analyze(silence, silence.size, 500)
        detector.analyze(silence, silence.size, 900)

        assertNull(detector.analyze(loud, loud.size, 1_100))
    }

    @Test
    fun `un rumore di mezzo secondo basta con le impostazioni predefinite`() {
        val detector = RmsNoiseDetector()
        val loud = tone(0.5)

        // Un colpo di tosse: corto, ma non deve passare inosservato.
        detector.analyze(loud, loud.size, 0)
        detector.analyze(loud, loud.size, 300)
        assertNotNull(detector.analyze(loud, loud.size, 600))
    }
}