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
 * Un Nursery Node accoppiato all'Hub, come lo vede un Parent Node che deve
 * scegliere quale ascoltare.
 *
 * Gli annunci `nursery-online` dicono solo chi si è appena collegato: per
 * offrire una scelta serve l'elenco completo, compresi quelli spenti, o il
 * menu cambierebbe contenuto a ogni accensione.
 */
data class NurseryInfo(
    val id: String,
    val name: String,
    val online: Boolean,
)
