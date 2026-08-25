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

import org.json.JSONException
import org.json.JSONObject

/** Un messaggio ricevuto dall'Hub sulla connessione WebSocket. */
sealed interface HubMessage {

    data class Welcome(
        val deviceId: String,
        val role: Role,
        val name: String,
        val serverTime: Long,
    ) : HubMessage

    data class Noise(
        val id: String,
        val nurseryId: String,
        val nurseryName: String,
        val startedAt: Long,
        val endedAt: Long?,
        val peakDb: Double?,
    ) : HubMessage

    data class NurseryOnline(
        val nurseryId: String,
        val nurseryName: String,
        val at: Long,
    ) : HubMessage

    data class NurseryOffline(
        val nurseryId: String,
        val nurseryName: String,
        val lastSeen: Long,
        val reason: String,
    ) : HubMessage

    data class Failure(val code: String) : HubMessage

    /**
     * Un tipo che questa versione dell'app non conosce. Non è un errore: un Hub
     * più recente può introdurre messaggi nuovi, e l'app deve limitarsi a
     * ignorarli invece di trattare la connessione come rotta.
     */
    data class Unsupported(val type: String) : HubMessage
}

object HubProtocol {

    /** Restituisce null se il messaggio non è JSON valido o non ha un campo "type". */
    fun parse(raw: String): HubMessage? = try {
        val json = JSONObject(raw)
        when (val type = json.optString("type")) {
            "" -> null

            "welcome" -> Role.fromWireName(json.optString("role"))?.let { role ->
                HubMessage.Welcome(
                    deviceId = json.getString("deviceId"),
                    role = role,
                    name = json.optString("name"),
                    serverTime = json.optLong("serverTime"),
                )
            }

            "noise" -> HubMessage.Noise(
                id = json.getString("id"),
                nurseryId = json.getString("nurseryId"),
                nurseryName = json.optString("nurseryName"),
                startedAt = json.getLong("startedAt"),
                endedAt = json.optLongOrNull("endedAt"),
                peakDb = json.optDoubleOrNull("peakDb"),
            )

            "nursery-online" -> HubMessage.NurseryOnline(
                nurseryId = json.getString("nurseryId"),
                nurseryName = json.optString("nurseryName"),
                at = json.optLong("at"),
            )

            "nursery-offline" -> HubMessage.NurseryOffline(
                nurseryId = json.getString("nurseryId"),
                nurseryName = json.optString("nurseryName"),
                lastSeen = json.optLong("lastSeen"),
                reason = json.optString("reason"),
            )

            "error" -> HubMessage.Failure(json.optString("code"))

            else -> HubMessage.Unsupported(type)
        }
    } catch (_: JSONException) {
        null
    }

    fun heartbeat(): String = JSONObject().put("type", "heartbeat").toString()

    fun noise(startedAt: Long, endedAt: Long? = null, peakDb: Double? = null): String =
        JSONObject()
            .put("type", "noise")
            .put("startedAt", startedAt)
            .apply {
                endedAt?.let { put("endedAt", it) }
                peakDb?.let { put("peakDb", it) }
            }
            .toString()

    fun fcmToken(token: String): String =
        JSONObject().put("type", "fcm-token").put("token", token).toString()
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else optLong(key)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
