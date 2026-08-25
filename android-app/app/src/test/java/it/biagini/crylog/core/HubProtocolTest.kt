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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubProtocolTest {

    @Test
    fun `il messaggio di benvenuto viene interpretato`() {
        val message = HubProtocol.parse(
            """{"type":"welcome","deviceId":"abc","role":"nursery","name":"Cameretta","serverTime":1700}""",
        )

        assertEquals(
            HubMessage.Welcome(deviceId = "abc", role = Role.NURSERY, name = "Cameretta", serverTime = 1700),
            message,
        )
    }

    @Test
    fun `un benvenuto con ruolo sconosciuto non viene accettato`() {
        assertNull(HubProtocol.parse("""{"type":"welcome","deviceId":"abc","role":"admin"}"""))
    }

    @Test
    fun `un evento rumore completo viene interpretato`() {
        val message = HubProtocol.parse(
            """{"type":"noise","id":"e1","nurseryId":"n1","nurseryName":"Cameretta","startedAt":1700,"endedAt":1800,"peakDb":72.5}""",
        )

        assertEquals(
            HubMessage.Noise("e1", "n1", "Cameretta", startedAt = 1700, endedAt = 1800, peakDb = 72.5),
            message,
        )
    }

    @Test
    fun `un evento rumore ancora in corso ha i campi opzionali nulli`() {
        val message = HubProtocol.parse(
            """{"type":"noise","id":"e1","nurseryId":"n1","nurseryName":"C","startedAt":1700,"endedAt":null,"peakDb":null}""",
        ) as HubMessage.Noise

        assertNull(message.endedAt)
        assertNull(message.peakDb)
    }

    @Test
    fun `i campi opzionali assenti sono trattati come nulli`() {
        val message = HubProtocol.parse(
            """{"type":"noise","id":"e1","nurseryId":"n1","nurseryName":"C","startedAt":1700}""",
        ) as HubMessage.Noise

        assertNull(message.endedAt)
        assertNull(message.peakDb)
    }

    @Test
    fun `un evento rumore senza startedAt non viene accettato`() {
        assertNull(HubProtocol.parse("""{"type":"noise","id":"e1","nurseryId":"n1"}"""))
    }

    @Test
    fun `gli avvisi di presenza del Nursery Node vengono interpretati`() {
        val online = HubProtocol.parse("""{"type":"nursery-online","nurseryId":"n1","nurseryName":"C","at":10}""")
        assertEquals(HubMessage.NurseryOnline("n1", "C", 10), online)

        val offline = HubProtocol.parse(
            """{"type":"nursery-offline","nurseryId":"n1","nurseryName":"C","lastSeen":10,"reason":"timeout"}""",
        )
        assertEquals(HubMessage.NurseryOffline("n1", "C", 10, "timeout"), offline)
    }

    @Test
    fun `il motivo distingue una disconnessione da un silenzio`() {
        val timeout = HubProtocol.parse(
            """{"type":"nursery-offline","nurseryId":"n1","nurseryName":"C","lastSeen":1,"reason":"timeout"}""",
        ) as HubMessage.NurseryOffline
        val closed = HubProtocol.parse(
            """{"type":"nursery-offline","nurseryId":"n1","nurseryName":"C","lastSeen":1,"reason":"disconnected"}""",
        ) as HubMessage.NurseryOffline

        assertEquals("timeout", timeout.reason)
        assertEquals("disconnected", closed.reason)
    }

    @Test
    fun `un errore dellHub viene interpretato`() {
        assertEquals(HubMessage.Failure("role_not_allowed"), HubProtocol.parse("""{"type":"error","code":"role_not_allowed"}"""))
    }

    @Test
    fun `un tipo sconosciuto non rompe la sessione`() {
        val message = HubProtocol.parse("""{"type":"webrtc-offer","sdp":"..."}""")
        assertEquals(HubMessage.Unsupported("webrtc-offer"), message)
    }

    @Test
    fun `un JSON non valido produce null invece di unaeccezione`() {
        assertNull(HubProtocol.parse("non-json"))
        assertNull(HubProtocol.parse(""))
        assertNull(HubProtocol.parse("[1,2,3]"))
    }

    @Test
    fun `un messaggio senza tipo produce null`() {
        assertNull(HubProtocol.parse("""{"deviceId":"abc"}"""))
    }

    @Test
    fun `un messaggio a cui mancano campi obbligatori produce null`() {
        assertNull(HubProtocol.parse("""{"type":"nursery-offline","nurseryName":"C"}"""))
    }

    @Test
    fun `lheartbeat prodotto e nel formato atteso`() {
        assertEquals("heartbeat", JSONObject(HubProtocol.heartbeat()).getString("type"))
    }

    @Test
    fun `un evento rumore prodotto omette i campi opzionali non valorizzati`() {
        val json = JSONObject(HubProtocol.noise(startedAt = 1700))

        assertEquals("noise", json.getString("type"))
        assertEquals(1700, json.getLong("startedAt"))
        assertTrue("endedAt non deve comparire se assente", !json.has("endedAt"))
        assertTrue("peakDb non deve comparire se assente", !json.has("peakDb"))
    }

    @Test
    fun `un evento rumore prodotto include i campi opzionali valorizzati`() {
        val json = JSONObject(HubProtocol.noise(startedAt = 1700, endedAt = 1800, peakDb = 68.4))

        assertEquals(1800, json.getLong("endedAt"))
        assertEquals(68.4, json.getDouble("peakDb"), 0.001)
    }

    @Test
    fun `quello che produciamo è quello che lHub sa leggere`() {
        // Il round-trip protegge dal caso in cui i due lati divergano in silenzio.
        val produced = JSONObject(HubProtocol.noise(startedAt = 1700, endedAt = 1800, peakDb = 68.4))
        val asHubWouldSee = HubProtocol.parse(
            produced.put("id", "e1").put("nurseryId", "n1").put("nurseryName", "C").toString(),
        )

        assertEquals(HubMessage.Noise("e1", "n1", "C", 1700, 1800, 68.4), asHubWouldSee)
    }

    @Test
    fun `i ruoli si convertono da e verso il formato di trasporto`() {
        assertEquals(Role.NURSERY, Role.fromWireName("nursery"))
        assertEquals(Role.PARENT, Role.fromWireName("parent"))
        assertNull(Role.fromWireName("admin"))
        assertNull(Role.fromWireName(null))
        assertEquals("nursery", Role.NURSERY.wireName)
    }
}
