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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regola che decide quale Nursery Node ascolta un Parent Node.
 *
 * Esiste separata dal ViewModel e dal servizio proprio per questi test: la
 * stessa decisione veniva presa in due punti, e due copie di una regola sono
 * due regole che prima o poi divergono.
 */
class NurseryChoiceTest {

    private val casa = "nursery-casa"
    private val altro = "nursery-altro"

    @Test
    fun `senza una preferenza si adotta il primo che si annuncia`() {
        assertTrue(NurseryChoice.shouldAdopt(preferredId = null, incomingId = casa, watchingLive = false))
    }

    @Test
    fun `l annuncio del preferito vale sempre, anche mentre lo si ascolta`() {
        assertTrue(NurseryChoice.shouldAdopt(preferredId = casa, incomingId = casa, watchingLive = true))
    }

    @Test
    fun `un altro Nursery Node non porta via una stanza che si sta ascoltando`() {
        // È il difetto che questa regola esiste per chiudere: due Nursery Node
        // accesi, e il Parent seguiva l'ultimo che aveva parlato.
        assertFalse(NurseryChoice.shouldAdopt(preferredId = casa, incomingId = altro, watchingLive = true))
    }

    @Test
    fun `se il preferito non e collegato si segue l unico disponibile`() {
        // Senza questo, sostituire il telefono della cameretta lascerebbe il
        // Parent agganciato a un identificativo che sull'Hub non esiste piu',
        // sordo a quello nuovo e senza un modo di dirglielo.
        assertTrue(NurseryChoice.shouldAdopt(preferredId = casa, incomingId = altro, watchingLive = false))
    }

    @Test
    fun `l uscita di scena riguarda solo il Nursery Node ascoltato`() {
        assertTrue(NurseryChoice.concerns(preferredId = casa, incomingId = casa))
        assertFalse(NurseryChoice.concerns(preferredId = casa, incomingId = altro))
    }

    @Test
    fun `senza preferenza nessuna uscita di scena riguarda questo Parent`() {
        assertFalse(NurseryChoice.concerns(preferredId = null, incomingId = casa))
    }
}
