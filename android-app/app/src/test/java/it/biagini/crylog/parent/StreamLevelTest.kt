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

import it.biagini.crylog.core.RmsNoiseDetector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sin

/**
 * Cosa succede a [StreamLevel] quando piu di un sink le spinge dentro audio.
 *
 * E il guasto del sink mai staccato visto dal lato in cui fa danno: un sink
 * rimasto appeso alla traccia di una sessione chiusa continua a consegnare
 * silenzio digitale, e questi test fissano le due conseguenze — il grafico che
 * si svuota e, molto peggio, il timestamp che tiene buono il watchdog.
 *
 * Quello che **non** coprono e lo stacco in se: vive dentro `WebRtcTransport`,
 * intrecciato a `org.webrtc` e a `android.util.Log`, e su JVM non e
 * raggiungibile. Se il difetto tornasse, a fallire sarebbe un test
 * strumentato, non questi.
 */
class StreamLevelTest {

    private val silence = ShortArray(BLOCK_SAMPLES)

    /** Un blocco di audio vero: la cameretta che fa rumore. */
    private val room = ShortArray(BLOCK_SAMPLES) { i ->
        (sin(2 * Math.PI * i / 32) * 0.5 * Short.MAX_VALUE).toInt().toShort()
    }

    /** Lo stato e di un singleton: senza questo i test si sporcano a vicenda. */
    @Before
    fun clean() = StreamLevel.reset()

    private fun push(block: ShortArray) = StreamLevel.push(block, block.size)

    /** Le barre che il grafico non disegna, perche sono a fondo scala. */
    private fun silentBars(): Int =
        StreamLevel.history.value.count { it <= RmsNoiseDetector.SILENCE_DB.toFloat() }

    /**
     * Aspetta che l orologio avanzi di almeno un millisecondo.
     *
     * `System.currentTimeMillis()` su Windows puo stare fermo per parecchi
     * millisecondi: senza aspettare, un confronto fra istanti sarebbe una
     * moneta lanciata in aria.
     */
    private fun tick(): Long {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() == start) Thread.onSpinWait()
        return start
    }

    @Test
    fun `il silenzio digitale conta come audio arrivato`() {
        assertEquals(0L, StreamLevel.lastFrameAtMs)

        push(silence)

        // È la premessa di tutto il guasto: qui non si distingue una cameretta
        // silenziosa da uno stream che non porta piu niente.
        assertNotEquals(0L, StreamLevel.lastFrameAtMs)
        assertEquals(RmsNoiseDetector.SILENCE_DB, StreamLevel.levelDb.value, 0.001)
    }

    @Test
    fun `il timestamp si aggiorna anche sui blocchi che non entrano nel grafico`() {
        // Il primo blocco e quello campionato: da qui in poi, per diciannove
        // blocchi, il grafico resta fermo.
        push(room)
        val drawn = StreamLevel.history.value
        val before = tick()

        repeat(19) { push(silence) }

        assertArrayEquals("il grafico non doveva muoversi", drawn, StreamLevel.history.value, 0f)
        assertTrue(
            "il timestamp si aggiorna prima del filtro uno su venti",
            StreamLevel.lastFrameAtMs > before,
        )
    }

    @Test
    fun `il sink di una sessione chiusa apre buchi nel grafico`() {
        // Un sink solo, quello vivo: ogni barra porta il suono della cameretta.
        repeat(StreamLevel.HISTORY_SIZE * BLOCKS_PER_BAR) { push(room) }
        assertEquals("con un sink solo il grafico e pieno", 0, silentBars())

        StreamLevel.reset()

        // Due sink sulla stessa StreamLevel: quello vivo porta la cameretta,
        // quello rimasto appeso alla sessione che il watchdog ha chiuso porta
        // silenzio. Un blocco su tre basta a bucare il grafico.
        repeat(StreamLevel.HISTORY_SIZE * BLOCKS_PER_BAR) { i ->
            push(if (i % 3 == 2) silence else room)
        }
        assertTrue("attese barre vuote, il grafico e tutto pieno", silentBars() > 0)
    }

    @Test
    fun `il sink di una sessione chiusa basta a tenere buono il watchdog`() {
        // L audio vero arriva, poi la sessione muore.
        push(room)
        val died = tick()

        // Da qui spinge solo il sink rimasto appeso alla traccia morta.
        repeat(50) { push(silence) }

        // `ListenService.watchdog()` legge questo timestamp e ne ricava
        // `flowing`: fresco vuol dire Health.LISTENING e allarme zitto, mentre
        // dal Nursery Node non arriva piu niente.
        assertTrue(
            "un sink morto non deve poter passare per audio che scorre",
            StreamLevel.lastFrameAtMs > died,
        )
    }

    @Test
    fun `il livello mostrato e quello dell ultimo blocco, chiunque lo abbia spinto`() {
        push(room)
        assertTrue("la cameretta fa rumore", StreamLevel.levelDb.value > -40.0)

        push(silence)

        // Il sink morto spinge per ultimo e vince: il numero sullo schermo dice
        // silenzio anche se la cameretta e rumorosa.
        assertEquals(RmsNoiseDetector.SILENCE_DB, StreamLevel.levelDb.value, 0.001)
    }

    @Test
    fun `il reset riporta il watchdog a non aver mai sentito niente`() {
        push(room)
        assertNotEquals(0L, StreamLevel.lastFrameAtMs)

        // È quello che fa `stop()` chiudendo la sessione: senza, il timestamp
        // della sessione vecchia coprirebbe il silenzio di quella nuova.
        StreamLevel.reset()

        assertEquals(0L, StreamLevel.lastFrameAtMs)
        assertEquals(RmsNoiseDetector.SILENCE_DB, StreamLevel.levelDb.value, 0.001)
        assertEquals(StreamLevel.HISTORY_SIZE, silentBars())
    }

    @Test
    fun `dopo il reset il primo blocco torna subito nel grafico`() {
        repeat(5) { push(room) }
        StreamLevel.reset()

        push(room)

        // Il conteggio riparte da zero, quindi la sessione nuova si vede al
        // primo blocco invece che a un punto qualsiasi dei venti.
        assertEquals(StreamLevel.HISTORY_SIZE - 1, silentBars())
    }

    private companion object {
        /** Quanti campioni porta un blocco di WebRTC: 10 ms a 48 kHz, mono. */
        const val BLOCK_SAMPLES = 480

        /** Uno su venti finisce nel grafico, gli altri diciannove no. */
        const val BLOCKS_PER_BAR = 20
    }
}
