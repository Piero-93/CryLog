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

import it.biagini.crylog.core.ConnectionState
import it.biagini.crylog.core.HubMessage
import it.biagini.crylog.core.HubProtocol
import it.biagini.crylog.core.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PairedDevice(val deviceId: String, val role: Role, val name: String, val token: String)

/**
 * Unico punto di contatto con l'Hub: pairing via REST e sessione WebSocket.
 *
 * La riconnessione automatica è qui e non nella UI perché una connessione che
 * cade non è un errore da mostrare, è una condizione normale su un telefono
 * che cambia rete o si sveglia dal sonno.
 */
class HubClient(private val scope: CoroutineScope) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Rileva un socket morto senza aspettare che sia il sistema operativo a dirlo.
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<HubMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<HubMessage> = _messages.asSharedFlow()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempt = 0

    // Chiudere un socket fa scattare onClosed, che senza questo flag
    // programmerebbe una riconnessione: chiudi, riapri, chiudi, all'infinito.
    private var closedByUs = false

    /** Cresce a ogni tentativo: i callback di un tentativo superato vanno ignorati. */
    private var sessionId = 0

    /**
     * Chiede all'Hub se il codice va bene, senza consumarlo.
     *
     * Serve a far scoprire un codice sbagliato dove il codice si scrive: senza,
     * l'errore arrivava due schermate piu' avanti, su una pagina che il campo
     * da correggere non ce l'ha.
     */
    suspend fun verifyCode(hubUrl: String, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("code", code)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${hubUrl.trimEnd('/')}/pairing-codes/verify")
                .post(body)
                .build()

            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val payload = JSONObject(response.body?.string().orEmpty())
                        error(payload.optString("error", "http_${response.code}"))
                    }
                }
            }
        }

    /**
     * Cambia il ruolo di un dispositivo gia accoppiato.
     *
     * Il token che si ha in mano e' gia' la prova di essere quel dispositivo:
     * obbligare a ricominciare da un codice nuovo aggiungeva passaggi, non
     * sicurezza. L'Hub chiude le connessioni aperte, che portano ancora il
     * ruolo vecchio, e il client si riconnette da solo.
     */
    suspend fun changeRole(hubUrl: String, token: String, role: Role, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("role", role.wireName)
                .put("name", name)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${hubUrl.trimEnd('/')}/device/role")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val payload = JSONObject(response.body?.string().orEmpty())
                        error(payload.optString("error", "http_${response.code}"))
                    }
                }
            }
        }

    suspend fun pair(hubUrl: String, code: String, role: Role, name: String): Result<PairedDevice> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("code", code)
                .put("role", role.wireName)
                .put("name", name)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${hubUrl.trimEnd('/')}/pair")
                .post(body)
                .build()

            runCatching {
                http.newCall(request).execute().use { response ->
                    val payload = JSONObject(response.body?.string().orEmpty())
                    if (!response.isSuccessful) {
                        error(payload.optString("error", "http_${response.code}"))
                    }
                    PairedDevice(
                        deviceId = payload.getString("deviceId"),
                        role = Role.fromWireName(payload.optString("role")) ?: role,
                        name = payload.optString("name", name),
                        token = payload.getString("token"),
                    )
                }
            }
        }

    /**
     * Cronologia dall'Hub.
     *
     * La lista in memoria si svuota a ogni riavvio dell'app: senza questa, la
     * notifica prometterebbe dettagli che non esistono, e un progetto chiamato
     * CryLog non avrebbe alcun registro.
     */
    suspend fun recentEvents(hubUrl: String, token: String, limit: Int = 50): Result<List<HubMessage.Noise>> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${hubUrl.trimEnd('/')}/events?limit=$limit")
                .header("Authorization", "Bearer $token")
                .build()

            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("http_${response.code}")
                    val events = JSONObject(response.body?.string().orEmpty()).getJSONArray("events")

                    List(events.length()) { index ->
                        val item = events.getJSONObject(index)
                        HubMessage.Noise(
                            id = item.getString("id"),
                            nurseryId = item.getString("nurseryId"),
                            nurseryName = item.optString("nurseryName").ifBlank { "?" },
                            startedAt = item.getLong("startedAt"),
                            endedAt = if (item.isNull("endedAt")) null else item.getLong("endedAt"),
                            peakDb = if (item.isNull("peakDb")) null else item.getDouble("peakDb"),
                        )
                    }
                }
            }
        }

    fun connect(hubUrl: String, token: String) {
        disconnect()
        attempt = 0
        openSocket(hubUrl, token)
    }

    private fun openSocket(hubUrl: String, token: String) {
        // Identifica questo tentativo. Confrontare il WebSocket con il campo
        // "socket" non funzionerebbe: l'assegnazione avviene dopo che
        // newWebSocket ritorna, e i callback possono arrivare prima.
        val session = ++sessionId
        closedByUs = false
        _state.value = ConnectionState.Connecting

        val url = hubUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")

        val request = Request.Builder()
            .url("$url/ws")
            .header("Authorization", "Bearer $token")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (session != sessionId) {
                    webSocket.close(1000, "sessione superata")
                    return
                }
                attempt = 0
                _state.value = ConnectionState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                HubProtocol.parse(text)?.let { scope.launch { _messages.emit(it) } }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closedByUs || session != sessionId) return
                _state.value = ConnectionState.Disconnected
                scheduleReconnect(hubUrl, token)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closedByUs || session != sessionId) return
                // 401 significa token non valido: riprovare all'infinito non lo
                // farebbe diventare valido, serve un nuovo pairing.
                val unauthorized = response?.code == 401
                _state.value = ConnectionState.Failed(
                    reason = t.message ?: response?.message ?: "connessione fallita",
                    unauthorized = unauthorized,
                )
                if (!unauthorized) scheduleReconnect(hubUrl, token)
            }
        })
    }

    private fun scheduleReconnect(hubUrl: String, token: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoff = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl minOf(attempt, 5))
            attempt++
            delay(backoff)
            openSocket(hubUrl, token)
        }
    }

    fun send(payload: String): Boolean = socket?.send(payload) ?: false

    fun disconnect() {
        closedByUs = true
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "chiusura richiesta")
        socket = null
        _state.value = ConnectionState.Disconnected
    }

    private companion object {
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
