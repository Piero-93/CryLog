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

package it.biagini.crylog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.biagini.crylog.core.ConnectionState
import it.biagini.crylog.core.HubMessage
import it.biagini.crylog.core.Role
import it.biagini.crylog.hub.DeviceStore
import it.biagini.crylog.hub.HubClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UiState {

    data object ChoosingRole : UiState

    data class Pairing(
        val role: Role,
        /** Ultimo Hub usato: si ridigita solo la prima volta. */
        val hubUrl: String = "",
        val inProgress: Boolean = false,
        val error: String? = null,
    ) : UiState

    data class Session(
        val role: Role,
        val deviceName: String,
        val connection: ConnectionState,
        val events: List<HubMessage> = emptyList(),
    ) : UiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = DeviceStore(application)
    private val client = HubClient(viewModelScope)

    private val _uiState = MutableStateFlow<UiState>(initialState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            client.state.collect { connection ->
                _uiState.update { current ->
                    if (current is UiState.Session) current.copy(connection = connection) else current
                }
                // Un token rifiutato non si risolve da solo: si torna al pairing.
                if (connection is ConnectionState.Failed && connection.unauthorized) resetPairing()
            }
        }

        viewModelScope.launch {
            client.messages.collect { message ->
                _uiState.update { current ->
                    if (current !is UiState.Session) return@update current
                    current.copy(events = (listOf(message) + current.events).take(MAX_EVENTS))
                }
            }
        }

        if (store.isPaired) connect()
    }

    private fun initialState(): UiState = when {
        store.isPaired -> UiState.Session(
            role = store.role ?: Role.PARENT,
            deviceName = store.deviceName.orEmpty(),
            connection = ConnectionState.Disconnected,
        )
        store.role != null -> UiState.Pairing(store.role!!, store.hubUrl.orEmpty())
        else -> UiState.ChoosingRole
    }

    fun selectRole(role: Role) {
        store.role = role
        _uiState.value = UiState.Pairing(role, store.hubUrl.orEmpty())
    }

    fun pair(hubUrl: String, code: String, name: String) {
        val role = store.role ?: return
        _uiState.value = UiState.Pairing(role, hubUrl, inProgress = true)

        viewModelScope.launch {
            client.pair(hubUrl.trim(), code.trim(), role, name.trim())
                .onSuccess { device ->
                    store.hubUrl = hubUrl.trim()
                    store.deviceId = device.deviceId
                    store.deviceToken = device.token
                    store.deviceName = device.name
                    _uiState.value = UiState.Session(
                        role = device.role,
                        deviceName = device.name,
                        connection = ConnectionState.Disconnected,
                    )
                    connect()
                }
                .onFailure { error ->
                    _uiState.value = UiState.Pairing(
                        role = role,
                        hubUrl = hubUrl,
                        error = error.message ?: "pairing fallito",
                    )
                }
        }
    }

    fun connect() {
        val url = store.hubUrl ?: return
        val token = store.deviceToken ?: return
        client.connect(url, token)
    }

    fun disconnect() = client.disconnect()

    fun resetPairing() {
        client.disconnect()
        store.clearPairing()
        _uiState.value = UiState.Pairing(store.role ?: Role.PARENT, store.hubUrl.orEmpty())
    }

    fun changeRole() {
        client.disconnect()
        store.clearPairing()
        store.role = null
        _uiState.value = UiState.ChoosingRole
    }

    override fun onCleared() {
        client.disconnect()
        super.onCleared()
    }

    private companion object {
        const val MAX_EVENTS = 50
    }
}
