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
import it.biagini.crylog.core.RmsNoiseDetector
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

    // --- Rilevamento rumore (Nursery Node) ---

    var noiseThresholdDb: Double
        get() = prefs.getFloat(KEY_THRESHOLD, RmsNoiseDetector.DEFAULT_THRESHOLD_DB.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat(KEY_THRESHOLD, value.toFloat()).apply()

    var noiseMinDurationMs: Long
        get() = prefs.getLong(KEY_MIN_DURATION, RmsNoiseDetector.DEFAULT_MIN_DURATION_MS)
        set(value) = prefs.edit().putLong(KEY_MIN_DURATION, value).apply()

    var noiseCooldownMs: Long
        get() = prefs.getLong(KEY_COOLDOWN, RmsNoiseDetector.DEFAULT_COOLDOWN_MS)
        set(value) = prefs.edit().putLong(KEY_COOLDOWN, value).apply()

    /** Se il Nursery Node deve riprendere il monitoraggio da solo dopo un riavvio. */
    var armed: Boolean
        get() = prefs.getBoolean(KEY_ARMED, false)
        set(value) = prefs.edit().putBoolean(KEY_ARMED, value).apply()

    // --- Avvisi (Parent Node) ---

    var flashOnAlert: Boolean
        get() = prefs.getBoolean(KEY_FLASH, false)
        set(value) = prefs.edit().putBoolean(KEY_FLASH, value).apply()

    var vibrateOnAlert: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE, value).apply()

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
        const val KEY_THRESHOLD = "noise_threshold_db"
        const val KEY_MIN_DURATION = "noise_min_duration_ms"
        const val KEY_COOLDOWN = "noise_cooldown_ms"
        const val KEY_ARMED = "armed"
        const val KEY_FLASH = "flash_on_alert"
        const val KEY_VIBRATE = "vibrate_on_alert"
    }
}
