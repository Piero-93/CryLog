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
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.biagini.crylog.core.ConnectionState
import it.biagini.crylog.core.HubMessage
import it.biagini.crylog.core.HubProtocol
import it.biagini.crylog.core.Role
import it.biagini.crylog.hub.DeviceStore
import it.biagini.crylog.hub.HubClient
import it.biagini.crylog.nursery.BootReceiver
import it.biagini.crylog.nursery.NoiseMonitor
import it.biagini.crylog.nursery.NoiseMonitorService
import it.biagini.crylog.parent.AlertNotifier
import it.biagini.crylog.parent.Alerter
import it.biagini.crylog.parent.SeenEvents
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
    private val alerter = Alerter(application, viewModelScope)

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

                if (connection is ConnectionState.Connected) {
                    deliverFcmToken()
                    loadHistory()
                } else {
                    // Persa la connessione non sappiamo più se qualcuno stia
                    // sorvegliando: meglio nessuna informazione che una vecchia.
                    AlertNotifier(getApplication()).clearWatching()
                }
            }
        }

        viewModelScope.launch {
            client.messages.collect { message ->
                handleAlert(message)
                _uiState.update { current ->
                    if (current !is UiState.Session) return@update current
                    current.copy(events = (listOf(message) + current.events).take(MAX_EVENTS))
                }
            }
        }

        // Con ruolo Nursery la connessione appartiene al servizio: aprirne una
        // seconda qui significherebbe due sessioni per lo stesso dispositivo.
        if (store.isPaired && store.role == Role.PARENT) {
            connect()
            requestFcmToken()
        }

        resumeIfInterrupted()
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
                    requestFcmToken()
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

    // --- Nursery Node ---

    val noiseThresholdDb: Double get() = store.noiseThresholdDb
    val noiseMinDurationMs: Long get() = store.noiseMinDurationMs

    fun arm() {
        store.armed = true
        NoiseMonitorService.start(getApplication())
        BootReceiver.clearNotification(getApplication())
    }

    /**
     * Riprende il monitoraggio se risultava armato ma non sta girando.
     *
     * È il caso del telefono riavviato: il servizio non può ripartire da solo
     * da BOOT_COMPLETED, ma qui l'app è in primo piano e il permesso microfono
     * è già stato concesso, quindi la piattaforma lo consente.
     */
    fun resumeIfInterrupted() {
        if (store.role != Role.NURSERY) return
        if (!store.armed || NoiseMonitor.armed.value) return

        Log.i(TAG, "monitoraggio interrotto da un riavvio: riprendo")
        arm()
    }

    fun disarm() {
        store.armed = false
        NoiseMonitorService.stop(getApplication())
    }

    /**
     * Il rilevatore legge le soglie all'avvio, quindi cambiarle mentre il
     * monitoraggio è attivo richiede di farlo ripartire.
     */
    fun setThreshold(db: Double) {
        store.noiseThresholdDb = db
        restartIfArmed()
    }

    fun setMinDuration(ms: Long) {
        store.noiseMinDurationMs = ms
        restartIfArmed()
    }

    val noiseCooldownMs: Long get() = store.noiseCooldownMs

    fun setCooldown(ms: Long) {
        store.noiseCooldownMs = ms
        restartIfArmed()
    }

    private fun restartIfArmed() {
        if (!store.armed) return
        NoiseMonitorService.stop(getApplication())
        NoiseMonitorService.start(getApplication())
    }

    /**
     * Avvisi per gli eventi che arrivano dal WebSocket.
     *
     * Produce la stessa notifica del percorso push, di proposito: una
     * vibrazione senza nulla in tendina lascia chi è su un'altra schermata a
     * chiedersi perché il telefono si sia mosso. Quale dei due canali abbia
     * portato l'evento non deve fare differenza.
     */
    private fun handleAlert(message: HubMessage) {
        val notifier = AlertNotifier(getApplication())

        when (message) {
            is HubMessage.Noise -> {
                if (!SeenEvents.markSeen(message.id)) return
                notifier.notifyNoise(message.id)
                alerter.alert(vibrate = store.vibrateOnAlert, flash = store.flashOnAlert)
            }

            is HubMessage.NurseryOffline -> {
                notifier.clearWatching()
                if (!SeenEvents.markSeen("offline:${message.nurseryId}")) return
                notifier.notifyNurseryGone(message.reason)
                alerter.alert(vibrate = store.vibrateOnAlert, flash = store.flashOnAlert)
            }

            // Il Nursery Node è tornato: l'allarme non descrive più la realtà,
            // e al suo posto va lo stato di chi sorveglia.
            is HubMessage.NurseryOnline -> {
                SeenEvents.forgetOffline(message.nurseryId)
                notifier.clearNurseryGone()
                notifier.notifyWatching(message.nurseryName, message.at)
            }

            else -> Unit
        }
    }

    /**
     * Carica dall'Hub gli eventi già registrati.
     *
     * Gli eventi arrivati in tempo reale restano in cima: sono gli stessi, ma
     * la cronologia è ordinata e potrebbe non contenere ancora l'ultimo.
     */
    private fun loadHistory() {
        val url = store.hubUrl ?: return
        val token = store.deviceToken ?: return

        viewModelScope.launch {
            client.recentEvents(url, token)
                .onSuccess { history ->
                    _uiState.update { current ->
                        if (current !is UiState.Session) return@update current
                        val liveIds = current.events.mapNotNull { (it as? HubMessage.Noise)?.id }.toSet()
                        current.copy(
                            events = (current.events + history.filter { it.id !in liveIds })
                                .take(MAX_EVENTS),
                        )
                    }
                }
                .onFailure { Log.w(TAG, "cronologia non caricata: ${it.message}") }
        }
    }

    // --- Notifiche push ---

    /**
     * Chiede a Firebase il token corrente.
     *
     * Fallisce senza rumore se il progetto Firebase non è configurato: le push
     * sono opzionali, e l'app deve restare utilizzabile con le sole notifiche
     * in tempo reale.
     */
    private fun requestFcmToken() {
        if (store.role != Role.PARENT) return

        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                store.pendingFcmToken = token
                deliverFcmToken()
            }
        }.onFailure {
            Log.i(TAG, "Firebase non configurato: niente notifiche in background")
        }
    }

    /** Il token vale solo se l'Hub lo conosce, quindi si riprova a ogni connessione. */
    private fun deliverFcmToken() {
        val token = store.pendingFcmToken ?: return
        if (token == store.sentFcmToken) return

        if (client.send(HubProtocol.fcmToken(token))) {
            store.sentFcmToken = token
            Log.i(TAG, "token FCM consegnato all'Hub")
        }
    }

    // --- Parent Node ---

    val vibrateOnAlert: Boolean get() = store.vibrateOnAlert
    val flashOnAlert: Boolean get() = store.flashOnAlert

    fun setVibrate(enabled: Boolean) { store.vibrateOnAlert = enabled }

    fun setFlash(enabled: Boolean) { store.flashOnAlert = enabled }

    /** Fa sentire al genitore com'è l'avviso, prima che serva davvero. */
    fun testAlert() = alerter.alert(vibrate = store.vibrateOnAlert, flash = store.flashOnAlert)

    fun connect() {
        val url = store.hubUrl ?: return
        val token = store.deviceToken ?: return
        client.connect(url, token)
    }

    fun disconnect() = client.disconnect()

    fun resetPairing() {
        stopEverything()
        store.clearPairing()
        _uiState.value = UiState.Pairing(store.role ?: Role.PARENT, store.hubUrl.orEmpty())
    }

    fun changeRole() {
        stopEverything()
        store.clearPairing()
        store.role = null
        _uiState.value = UiState.ChoosingRole
    }

    /**
     * Il servizio sopravvive alla UI per costruzione, quindi va fermato
     * esplicitamente: senza questo resterebbe ad ascoltare con il microfono
     * acceso per un dispositivo che l'app considera ormai scollegato.
     */
    private fun stopEverything() {
        disarm()
        client.disconnect()
    }

    override fun onCleared() {
        client.disconnect()
        super.onCleared()
    }

    private companion object {
        const val MAX_EVENTS = 50
        const val TAG = "CryLogViewModel"
    }
}
