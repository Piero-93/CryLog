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
 * Quale Nursery Node ascolta un Parent Node, quando ce n'è più di uno.
 *
 * Il Parent seguiva l'ultimo che si annunciava online. Con un Nursery Node solo
 * non si vede; con due accesi il Parent cambiava stanza da solo e senza dirlo,
 * che su un baby monitor non è una comodità mancante ma un guasto: si può stare
 * ad ascoltare la stanza sbagliata credendo di sorvegliare l'altra.
 *
 * La regola **non** è "il primo e basta per sempre". Sarebbe peggio del difetto:
 * chi sostituisce il telefono della cameretta si ritroverebbe un Parent
 * agganciato a un identificativo che sull'Hub non esiste più, sordo a quello
 * nuovo e senza un modo di dirglielo.
 *
 * La regola è **non cambiare stanza mentre se ne sta ascoltando una viva**.
 */
object NurseryChoice {

    /**
     * Se l'annuncio di [incomingId] debba diventare il Nursery Node ascoltato.
     *
     * [preferredId] è quello scelto finora, [watchingLive] dice se quel
     * preferito risulta collegato in questo momento: se non lo è, non c'è
     * nessuna stanza viva da cui allontanarsi, e seguire l'unico disponibile
     * è la cosa giusta invece che restare sordi.
     */
    fun shouldAdopt(preferredId: String?, incomingId: String, watchingLive: Boolean): Boolean = when {
        preferredId == incomingId -> true
        preferredId == null -> true
        else -> !watchingLive
    }

    /** Se l'uscita di scena di [incomingId] riguardi il Nursery Node ascoltato. */
    fun concerns(preferredId: String?, incomingId: String): Boolean = preferredId == incomingId
}
