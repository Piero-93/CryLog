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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {

    @Test
    fun `il testo digitato diventa maiuscolo`() {
        assertEquals("QAQQMYD2", PairingCode.sanitize("qaqqmyd2"))
    }

    @Test
    fun `separatori e spazi vengono ignorati`() {
        assertEquals("QAQQMYD2", PairingCode.sanitize("QAQQ-MYD2"))
        assertEquals("QAQQMYD2", PairingCode.sanitize(" QAQQ MYD2 "))
    }

    @Test
    fun `le confusioni tipiche di lettura vengono risolte`() {
        // I e L somigliano a 1, O a 0, U a V: l'alfabeto le esclude apposta.
        assertEquals("1100", PairingCode.sanitize("ILO0"))
        assertEquals("V", PairingCode.sanitize("U"))
    }

    @Test
    fun `i caratteri fuori alfabeto vengono scartati`() {
        assertEquals("AB12", PairingCode.sanitize("A!B@1#2\$"))
    }

    @Test
    fun `non si superano mai gli otto caratteri`() {
        assertEquals(8, PairingCode.sanitize("ABCDEFGHIJKLMNOP").length)
        assertEquals("ABCDEFGH", PairingCode.sanitize("ABCDEFGHJKMN"))
    }

    @Test
    fun `il codice viene formattato in due gruppi per lHub`() {
        assertEquals("QAQQ-MYD2", PairingCode.format("QAQQMYD2"))
    }

    @Test
    fun `un codice incompleto non viene spezzato prematuramente`() {
        assertEquals("QAQ", PairingCode.format("QAQ"))
        assertEquals("QAQQ", PairingCode.format("QAQQ"))
        assertEquals("QAQQ-M", PairingCode.format("QAQQM"))
    }

    @Test
    fun `il codice e completo solo a otto caratteri`() {
        assertFalse(PairingCode.isComplete(""))
        assertFalse(PairingCode.isComplete("QAQQMYD"))
        assertTrue(PairingCode.isComplete("QAQQMYD2"))
    }

    @Test
    fun `quello che si digita male arriva comunque corretto allHub`() {
        val digitatoMale = "qaqq myd2"
        assertEquals("QAQQ-MYD2", PairingCode.format(PairingCode.sanitize(digitatoMale)))
    }
}
