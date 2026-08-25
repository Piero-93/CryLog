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

package it.biagini.crylog.hub

import android.content.Context
import it.biagini.crylog.core.Role

/**
 * Configurazione persistente del dispositivo: ruolo, indirizzo dell'Hub e token
 * ottenuto col pairing.
 *
 * SharedPreferences e non DataStore: sono cinque chiavi, e la piattaforma le
 * offre senza aggiungere dipendenze.
 */
class DeviceStore(context: Context) {

    private val prefs = context.getSharedPreferences("crylog", Context.MODE_PRIVATE)

    var hubUrl: String?
        get() = prefs.getString(KEY_HUB_URL, null)
        set(value) = prefs.edit().putString(KEY_HUB_URL, value).apply()

    var role: Role?
        get() = Role.fromWireName(prefs.getString(KEY_ROLE, null))
        set(value) = prefs.edit().putString(KEY_ROLE, value?.wireName).apply()

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var deviceToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var deviceName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    val isPaired: Boolean
        get() = !deviceToken.isNullOrBlank() && !hubUrl.isNullOrBlank() && role != null

    /** Usato quando l'Hub rifiuta il token: l'unica via d'uscita è rifare il pairing. */
    fun clearPairing() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_TOKEN)
            .remove(KEY_NAME)
            .apply()
    }

    private companion object {
        const val KEY_HUB_URL = "hub_url"
        const val KEY_ROLE = "role"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "device_token"
        const val KEY_NAME = "device_name"
    }
}
