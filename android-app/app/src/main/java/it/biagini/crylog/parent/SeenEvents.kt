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

/**
 * Ricorda quali eventi sono già stati mostrati.
 *
 * Lo stesso rumore può arrivare due volte: dal WebSocket se l'app è aperta e
 * dalla push se l'Hub la manda comunque. Due vibrazioni per un solo pianto
 * fanno sembrare il sistema rotto, quindi vince chi arriva primo.
 *
 * Sta fuori dal ViewModel perché il servizio di Firebase gira anche con l'app
 * chiusa, quando un ViewModel non esiste.
 */
object SeenEvents {

    private const val CAPACITY = 200

    private val seen = LinkedHashSet<String>()

    /** Restituisce true la prima volta che vede un evento, false alle successive. */
    @Synchronized
    fun markSeen(eventId: String): Boolean {
        if (!seen.add(eventId)) return false

        // Un set che cresce all'infinito su un servizio che vive per giorni
        // diventa un leak: si dimenticano i più vecchi, che non torneranno.
        while (seen.size > CAPACITY) {
            val oldest = seen.first()
            seen.remove(oldest)
        }
        return true
    }

    /**
     * Dimentica che un Nursery Node era sparito.
     *
     * Serve quando torna online: se sparisse di nuovo, il secondo allarme
     * verrebbe scartato come duplicato e il genitore non saprebbe che la
     * sorveglianza si è fermata un'altra volta.
     */
    @Synchronized
    fun forgetOffline(nurseryId: String) {
        seen.remove("offline:$nurseryId")
    }

    @Synchronized
    fun forget() = seen.clear()
}
