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

/**
 * Il codice generato dall'Hub: otto caratteri Crockford base32, mostrati come
 * due gruppi di quattro.
 *
 * L'alfabeto esclude I, L, O e U proprio perché si confondono leggendo o
 * dettando un codice: qui le confusioni tipiche vengono risolte invece di
 * essere respinte come errore.
 */
object PairingCode {

    const val LENGTH = 8

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Riduce quello che l'utente digita ai soli caratteri validi, in maiuscolo. */
    fun sanitize(input: String): String = input
        .uppercase()
        .map { char ->
            when (char) {
                'I', 'L' -> '1'
                'O' -> '0'
                'U' -> 'V'
                else -> char
            }
        }
        .filter { it in ALPHABET }
        .take(LENGTH)
        .joinToString("")

    /** Forma con cui il codice viaggia verso l'Hub. */
    fun format(sanitized: String): String =
        if (sanitized.length > 4) "${sanitized.take(4)}-${sanitized.drop(4)}" else sanitized

    fun isComplete(sanitized: String): Boolean = sanitized.length == LENGTH
}
