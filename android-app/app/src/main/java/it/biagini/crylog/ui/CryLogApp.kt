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

package it.biagini.crylog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.biagini.crylog.MainViewModel
import it.biagini.crylog.UiState
import it.biagini.crylog.core.ConnectionState
import it.biagini.crylog.core.HubMessage
import it.biagini.crylog.core.PairingCode
import it.biagini.crylog.core.Role

@Composable
fun CryLogApp(
    viewModel: MainViewModel,
    state: UiState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is UiState.ChoosingRole -> RoleSelection(viewModel::selectRole, modifier)

        is UiState.Pairing -> {
            // Senza questo il tasto indietro chiuderebbe l'app: le schermate sono
            // stati del ViewModel, non destinazioni di navigazione.
            BackHandler(enabled = !state.inProgress) { viewModel.changeRole() }
            PairingForm(state, viewModel::pair, viewModel::changeRole, modifier)
        }

        is UiState.Session -> when (state.role) {
            Role.NURSERY -> NurseryScreen(
                viewModel = viewModel,
                deviceName = state.deviceName,
                onUnpair = viewModel::changeRole,
                modifier = modifier,
            )

            Role.PARENT -> SessionScreen(
                viewModel = viewModel,
                state = state,
                onReconnect = viewModel::connect,
                onChangeRole = viewModel::changeRole,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun RoleSelection(onSelectRole: (Role) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("CryLog", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Che ruolo ha questo telefono?",
            style = MaterialTheme.typography.bodyLarge,
        )

        RoleCard(
            title = "Nursery Node",
            description = "Sta nella cameretta. Ascolta e avvisa quando sente un rumore.",
            onClick = { onSelectRole(Role.NURSERY) },
        )
        RoleCard(
            title = "Parent Node",
            description = "Sta con te. Riceve le notifiche e potrà aprire lo stream.",
            onClick = { onSelectRole(Role.PARENT) },
        )

        Text(
            "Si può cambiare in seguito, rifacendo il pairing.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RoleCard(title: String, description: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PairingForm(
    state: UiState.Pairing,
    onPair: (String, String, String) -> Unit,
    onChangeRole: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hubUrl by rememberSaveable { mutableStateOf(state.hubUrl.ifBlank { "https://crylog." }) }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable {
        mutableStateOf(if (state.role == Role.NURSERY) "Cameretta" else "Telefono")
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Collega all'Hub", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Ruolo scelto: ${if (state.role == Role.NURSERY) "Nursery Node" else "Parent Node"}",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = hubUrl,
            onValueChange = { hubUrl = it },
            label = { Text("Indirizzo dell'Hub") },
            supportingText = { Text("es. https://crylog.tuo-tailnet.ts.net") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Codice di pairing", style = MaterialTheme.typography.bodyMedium)
        PairingCodeField(value = code, onValueChange = { code = it })
        Text(
            "otto caratteri, generati dall'Hub",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nome del dispositivo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.error != null) {
            Text(
                pairingError(state.error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = { onPair(hubUrl, PairingCode.format(code), name) },
            enabled = !state.inProgress && PairingCode.isComplete(code) && hubUrl.isNotBlank() && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.inProgress) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
            } else {
                Text("Collega")
            }
        }

        TextButton(onClick = onChangeRole) { Text("Cambia ruolo") }
    }
}

/** L'Hub risponde con codici sintetici: qui diventano qualcosa di azionabile. */
private fun pairingError(code: String): String = when (code) {
    "unknown_code" -> "Codice non riconosciuto. Controlla di averlo copiato bene."
    "already_used" -> "Codice già usato. Ogni codice vale per un solo dispositivo: generane un altro."
    "expired" -> "Codice scaduto. I codici durano dieci minuti: generane uno nuovo."
    "invalid_code_format" -> "Il codice deve essere di otto caratteri."
    "invalid_name" -> "Il nome non può essere vuoto."
    else -> "Pairing fallito: $code"
}

@Composable
private fun SessionScreen(
    viewModel: MainViewModel,
    state: UiState.Session,
    onReconnect: () -> Unit,
    onChangeRole: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingUnpair by rememberSaveable { mutableStateOf(false) }
    var vibrate by rememberSaveable { mutableStateOf(viewModel.vibrateOnAlert) }
    var flash by rememberSaveable { mutableStateOf(viewModel.flashOnAlert) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (state.role == Role.NURSERY) "Nursery Node" else "Parent Node",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(state.deviceName, style = MaterialTheme.typography.bodyLarge)

        ConnectionBanner(state.connection, onReconnect)

        HorizontalDivider()

        Text("Avvisi", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Vibrazione", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = vibrate,
                onCheckedChange = { vibrate = it; viewModel.setVibrate(it) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Lampeggio del flash", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Utile a telefono silenzioso o a faccia in giù",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = flash,
                onCheckedChange = { flash = it; viewModel.setFlash(it) },
            )
        }
        OutlinedButton(onClick = viewModel::testAlert) { Text("Prova l'avviso") }

        HorizontalDivider()

        Text("Eventi", style = MaterialTheme.typography.titleMedium)

        if (state.events.isEmpty()) {
            Text(
                "Nessun evento ricevuto.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.events) { event -> EventRow(event) }
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { confirmingUnpair = true }) { Text("Scollega questo dispositivo") }
    }

    if (confirmingUnpair) {
        AlertDialog(
            onDismissRequest = { confirmingUnpair = false },
            title = { Text("Scollegare il dispositivo?") },
            text = {
                Text(
                    "Il token viene cancellato e servirà un nuovo codice di pairing " +
                        "dall'Hub per ricollegarsi.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingUnpair = false
                    onChangeRole()
                }) { Text("Scollega") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnpair = false }) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun ConnectionBanner(connection: ConnectionState, onReconnect: () -> Unit) {
    val (label, showRetry) = when (connection) {
        ConnectionState.Connected -> "Connesso all'Hub" to false
        ConnectionState.Connecting -> "Connessione in corso..." to false
        ConnectionState.Disconnected -> "Non connesso" to true
        is ConnectionState.Failed -> "Connessione fallita: ${connection.reason}" to true
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connection is ConnectionState.Connected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (showRetry) {
                OutlinedButton(onClick = onReconnect) { Text("Riprova") }
            }
        }
    }
}

@Composable
private fun EventRow(event: HubMessage) {
    val text = when (event) {
        is HubMessage.Noise -> {
            val level = event.peakDb?.let { " (%.0f dB)".format(it) }.orEmpty()
            "Rumore da ${event.nurseryName}$level"
        }
        is HubMessage.NurseryOnline -> "${event.nurseryName} è online"
        is HubMessage.NurseryOffline -> when (event.reason) {
            "timeout" -> "${event.nurseryName} non risponde più"
            else -> "${event.nurseryName} si è disconnesso"
        }
        is HubMessage.Welcome -> "Sessione avviata come ${event.name}"
        is HubMessage.Failure -> "Errore dall'Hub: ${event.code}"
        is HubMessage.Unsupported -> "Messaggio non riconosciuto: ${event.type}"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
