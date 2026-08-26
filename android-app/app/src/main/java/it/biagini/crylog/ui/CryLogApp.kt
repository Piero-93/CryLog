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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import it.biagini.crylog.R
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import it.biagini.crylog.MainViewModel
import it.biagini.crylog.UiState
import it.biagini.crylog.core.ConnectionState
import it.biagini.crylog.core.HubMessage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.biagini.crylog.core.PairingCode
import it.biagini.crylog.core.RmsNoiseDetector
import it.biagini.crylog.parent.ContinuousListening
import it.biagini.crylog.parent.RemoteVideo
import it.biagini.crylog.parent.StreamLevel
import it.biagini.crylog.core.TransportState
import it.biagini.crylog.core.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HearingDisabled
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.LocalContentColor

@Composable
fun CryLogApp(
    viewModel: MainViewModel,
    state: UiState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is UiState.ChangingRole -> {
            BackHandler(enabled = !state.inProgress) { viewModel.cancelRoleChange() }
            RoleForm(
                title = "Cambia ruolo",
                subtitle = "Il dispositivo resta accoppiato, non serve un codice nuovo.",
                initialRole = state.current,
                initialName = state.name,
                inProgress = state.inProgress,
                error = state.error,
                confirmLabel = "Applica",
                onConfirm = viewModel::applyRoleChange,
                onBack = viewModel::cancelRoleChange,
                backLabel = "Annulla",
                modifier = modifier,
            )
        }

        is UiState.Connect -> ConnectForm(state, viewModel::submitConnection, modifier)

        is UiState.ChoosingRole -> {
            BackHandler(enabled = !state.inProgress) { viewModel.backToConnect() }
            RoleForm(
                title = "Che ruolo ha questo telefono?",
                subtitle = "Lo si può cambiare quando vuoi, senza rifare il pairing.",
                initialRole = null,
                initialName = "",
                inProgress = state.inProgress,
                error = state.error,
                confirmLabel = "Collega",
                onConfirm = viewModel::pair,
                onBack = { viewModel.backToConnect() },
                backLabel = "Indietro",
                modifier = modifier,
            )
        }

        is UiState.Session -> when (state.role) {
            Role.NURSERY -> NurseryScreen(
                viewModel = viewModel,
                deviceName = state.deviceName,
                onUnpair = viewModel::unpair,
                modifier = modifier,
            )

            Role.PARENT -> SessionScreen(
                viewModel = viewModel,
                state = state,
                onReconnect = viewModel::connect,
                onChangeRole = viewModel::changeRole,
                onUnpair = viewModel::unpair,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun RoleCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    selected: Boolean? = null,
) {
    // Quando c'e' una scelta in corso la carta la mostra da se': un pallino
    // acceso e il colore che cambia dicono in un colpo d'occhio quello che una
    // riga "Scelto: ..." sotto costringeva a leggere.
    val chosen = selected == true

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (chosen) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (chosen) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected != null) {
                RadioButton(selected = chosen, onClick = onClick)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Indirizzo dell'Hub e codice, insieme.
 *
 * Si chiedono nella stessa schermata perché si leggono dalla stessa pagina:
 * chi sta configurando ha davanti l'indirizzo e il codice appena generato.
 * L'indirizzo però è permanente e il codice usa e getta, quindi quando l'Hub è
 * già noto sta in una riga sola e il campo torna solo se serve cambiarlo.
 */
@Composable
private fun ConnectForm(
    state: UiState.Connect,
    onSubmit: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hubUrl by rememberSaveable { mutableStateOf(state.hubUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    var editingHub by rememberSaveable { mutableStateOf(state.hubUrl.isBlank()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(Space.Section),
    ) {
        AppHeader(title = "CryLog", subtitle = "", connection = null)

        Column(verticalArrangement = Arrangement.spacedBy(Space.Tight)) {
            Text("Collega all'Hub", style = MaterialTheme.typography.headlineSmall)
            Text(
                "L'Hub è il servizio che hai in casa: mette in contatto i due telefoni " +
                    "e tiene l'audio dentro la tua rete.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (editingHub) {
            OutlinedTextField(
                value = hubUrl,
                onValueChange = { hubUrl = it },
                label = { Text("Indirizzo dell'Hub") },
                placeholder = {
                    // Piu' chiaro del testo normale: a piena opacita' sembrava un
                    // indirizzo gia' scritto, e non si capiva se andasse cancellato.
                    Text(
                        "https://crylog.tuo-tailnet.ts.net",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    )
                },
                supportingText = { Text("Si chiede una volta sola") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.Item),
            ) {
                Text(
                    hubUrl,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { editingHub = true }) { Text("Cambia") }
            }
        }

        Section("Codice di pairing") {
            PairingCodeField(value = code, onValueChange = { code = it })
            Text(
                "Otto caratteri. Li generi dalla pagina dell'Hub.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.Item)) {
            if (state.error != null) {
                NoticeCard(text = pairingError(state.error), severe = true)
            }

            Button(
                onClick = { onSubmit(hubUrl, PairingCode.format(code)) },
                enabled = !state.checking && hubUrl.isNotBlank() && PairingCode.isComplete(code),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Avanti")
                }
            }
        }
    }
}

/**
 * Che telefono è questo, e come si chiama.
 *
 * Una schermata sola per due momenti: la scelta iniziale e il cambio
 * successivo. Il ruolo viene per ultimo perché l'Hub lo accetta nella
 * richiesta di pairing — basta mandargli tutto insieme alla fine invece di
 * deciderlo all'inizio — e perché il nome predefinito dipende da lui.
 */
@Composable
private fun RoleForm(
    title: String,
    subtitle: String,
    initialRole: Role?,
    initialName: String,
    inProgress: Boolean,
    error: String?,
    confirmLabel: String,
    onConfirm: (Role, String) -> Unit,
    onBack: () -> Unit,
    backLabel: String,
    modifier: Modifier = Modifier,
) {
    var role by rememberSaveable { mutableStateOf(initialRole) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var nameTouched by rememberSaveable { mutableStateOf(initialName.isNotBlank()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(Space.Section),
    ) {
        AppHeader(title = "CryLog", subtitle = "", connection = null)

        Column(verticalArrangement = Arrangement.spacedBy(Space.Tight)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.Item)) {
            RoleCard(
                title = "Nursery Node",
                description = "Resta nella cameretta e avvisa quando sente un rumore.",
                onClick = {
                    role = Role.NURSERY
                    // Il nome scritto a mano non si tocca: sovrascriverlo
                    // butterebbe via quello che l'utente ha appena deciso.
                    if (!nameTouched) name = "Cameretta"
                },
                selected = role == Role.NURSERY,
            )
            RoleCard(
                title = "Parent Node",
                description = "Resta con te e riceve gli avvisi.",
                onClick = {
                    role = Role.PARENT
                    if (!nameTouched) name = "Telefono"
                },
                selected = role == Role.PARENT,
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it; nameTouched = true },
            label = { Text("Nome del dispositivo") },
            placeholder = { Text("Come lo vedrai negli avvisi") },
            singleLine = true,
            enabled = role != null,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(Space.Item)) {
            if (error != null) {
                NoticeCard(text = pairingError(error), severe = true)
            }

            Button(
                onClick = { role?.let { onConfirm(it, name.trim()) } },
                enabled = !inProgress && role != null && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(confirmLabel)
                }
            }

            TextButton(onClick = onBack) { Text(backLabel) }
        }
    }
}

private fun pairingError(code: String): String = when (code) {
    "unknown_code" -> "Codice non riconosciuto. Controlla di averlo copiato bene."
    "already_used" -> "Codice già usato. Generane un altro dall'Hub."
    "expired" -> "Codice scaduto. Generane uno nuovo dall'Hub."
    "invalid_code_format" -> "Il codice è di otto caratteri."
    "invalid_name" -> "Il nome non può essere vuoto."
    "invalid_role" -> "Ruolo non riconosciuto dall'Hub."
    "unauthorized" -> "L'Hub non riconosce questo dispositivo. Scollegalo e rifai il pairing."
    else -> "Pairing fallito: $code"
}

@Composable
private fun SessionScreen(
    viewModel: MainViewModel,
    state: UiState.Session,
    onReconnect: () -> Unit,
    onChangeRole: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingUnpair by rememberSaveable { mutableStateOf(false) }
    var wantVideo by rememberSaveable { mutableStateOf(false) }
    var wantTalkBack by rememberSaveable { mutableStateOf(false) }
    var talking by rememberSaveable { mutableStateOf(false) }
    var continuous by rememberSaveable { mutableStateOf(viewModel.continuousListening) }
    var vibrate by rememberSaveable { mutableStateOf(viewModel.vibrateOnAlert) }

    // Il Parent Node non ha mai avuto bisogno del microfono: il permesso si
    // chiede solo a chi sceglie di poter rispondere.
    val context = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micGranted = granted; wantTalkBack = granted }
    var flash by rememberSaveable { mutableStateOf(viewModel.flashOnAlert) }
    var insist by rememberSaveable { mutableStateOf(viewModel.insistOnAlert) }

    Column(
        // La schermata non ci sta in altezza: senza scorrimento la cronologia
        // spingerebbe fuori dallo schermo il pulsante che la segue.
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(Space.Section),
    ) {
        AppHeader(
            title = if (state.role == Role.NURSERY) "Nursery Node" else "Parent Node",
            subtitle = state.deviceName,
            connection = state.connection,
        )

        Column(verticalArrangement = Arrangement.spacedBy(Space.Item)) {
            DndAccessCard()

            // Il banner compare solo quando c'e' qualcosa da fare: a connessione
            // buona lo stato lo dice gia' il pallino della testata, e una card che
            // ripete "tutto bene" ruba spazio a quello che serve davvero.
            if (state.connection !is ConnectionState.Connected) {
                ConnectionBanner(state.connection, onReconnect)
            }
        }

        ContinuousCard(
            enabled = continuous,
            onEnabledChange = { on ->
                continuous = on
                viewModel.setContinuousListening(on)
            },
        )

        if (!continuous) ListenCard(
            nurseryName = state.nurseryName,
            stream = state.stream,
            wantVideo = wantVideo,
            onWantVideoChange = { wantVideo = it },
            onStart = { viewModel.startListening(video = wantVideo, talkBack = wantTalkBack) },
            onStop = { talking = false; viewModel.stopListening() },
            wantTalkBack = wantTalkBack,
            onWantTalkBackChange = { wanted ->
                if (!wanted) {
                    wantTalkBack = false
                } else if (micGranted) {
                    wantTalkBack = true
                } else {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            talking = talking,
            onTalkingChange = { on -> talking = on; viewModel.setTalking(on) },
        )

        CollapsibleSection("Avvisi") {
            SettingsCard {
                SettingSwitch(
                    title = "Vibrazione",
                    checked = vibrate,
                    onCheckedChange = { vibrate = it; viewModel.setVibrate(it) },
                )
                SettingSwitch(
                    title = "Lampeggio del flash",
                    description = "Utile a telefono silenzioso o a faccia in giù",
                    checked = flash,
                    onCheckedChange = { flash = it; viewModel.setFlash(it) },
                )
                SettingSwitch(
                    title = "Ripeti finché non lo vedo",
                    description = "Ogni tre secondi finché non scarti la notifica, " +
                        "al massimo per cinque minuti",
                    checked = insist,
                    onCheckedChange = { insist = it; viewModel.setInsist(it) },
                )
            }

            OutlinedButton(onClick = viewModel::testAlert) { Text("Prova l'avviso") }
        }

        Section("Eventi") {
            if (state.events.isEmpty()) {
                Text(
                    "Nessun evento ricevuto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Colonna semplice e non lazy: gli eventi sono al massimo
                // MainViewModel.MAX_EVENTS, e una lista lazy dentro una colonna che
                // scorre non puo misurarsi.
                Column(verticalArrangement = Arrangement.spacedBy(Space.Tight)) {
                    state.events.forEach { event -> EventRow(event) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.Tight)) {
            TextButton(onClick = onChangeRole) { Text("Cambia ruolo") }
            TextButton(
                onClick = { confirmingUnpair = true },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Scollega") }
        }
    }

    if (confirmingUnpair) {
        AlertDialog(
            onDismissRequest = { confirmingUnpair = false },
            title = { Text("Scollegare il dispositivo?") },
            text = {
                Text(
                    "Il dispositivo viene cancellato dall'Hub. Per ricollegarlo servirà " +
                        "un nuovo codice.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingUnpair = false
                    onUnpair()
                }) { Text("Scollega") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnpair = false }) { Text("Annulla") }
            },
        )
    }
}

/**
 * L'ascolto in diretta.
 *
 * Lo stream parte solo se lo si chiede: finché nessuno ascolta, il Nursery
 * Node non spende nulla per trasmettere e continua soltanto a sorvegliare.
 */
@Composable
private fun ListenCard(
    nurseryName: String?,
    stream: TransportState,
    wantVideo: Boolean,
    onWantVideoChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    wantTalkBack: Boolean,
    onWantTalkBackChange: (Boolean) -> Unit,
    talking: Boolean,
    onTalkingChange: (Boolean) -> Unit,
) {
    if (nurseryName == null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Nessun Nursery Node collegato.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (stream is TransportState.Streaming) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (stream) {
                TransportState.Idle -> {
                    Text("Ascolta $nurseryName", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Audio dal vivo dalla cameretta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Anche il video", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Accende la fotocamera. Al buio si vede poco " +
                                    "e consuma batteria.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = wantVideo, onCheckedChange = onWantVideoChange)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Poter rispondere", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Aggiunge il pulsante per parlare. " +
                                    "Va scelto ora, dopo serve riaprire l'ascolto.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = wantTalkBack, onCheckedChange = onWantTalkBackChange)
                    }

                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text(if (wantVideo) "Guarda e ascolta" else "Ascolta")
                    }
                }

                TransportState.Connecting -> {
                    // Aprire una sessione richiede qualche secondo: senza niente
                    // che si muova sembra che il tocco non sia arrivato.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.Item),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Connessione…", style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Text("Annulla")
                    }
                }

                is TransportState.Streaming -> {
                    Text("In ascolto da $nurseryName", style = MaterialTheme.typography.titleMedium)

                    val hasVideo by RemoteVideo.available.collectAsStateWithLifecycle()
                    if (hasVideo) {
                        VideoView()
                    } else if (wantVideo) {
                        // Avevi chiesto il video e non è arrivato: il Nursery
                        // Node è in modalità solo audio o non ha fotocamera.
                        Text(
                            "Il Nursery Node trasmette solo audio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val history by StreamLevel.history.collectAsStateWithLifecycle()
                    val level by StreamLevel.levelDb.collectAsStateWithLifecycle()

                    // Serve a distinguere una cameretta silenziosa da uno stream
                    // che non porta nulla: due situazioni identiche all'orecchio.
                    LevelChart(history = history, thresholdDb = RmsNoiseDetector.SILENCE_DB)
                    Text(
                        "Livello: %.0f dBFS".format(level),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (wantTalkBack) {
                        FilledTonalButton(
                            onClick = { onTalkingChange(!talking) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (talking) {
                                ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            },
                        ) {
                            Text(if (talking) "Stai parlando — tocca per chiudere" else "Parla")
                        }
                    }

                    Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Text("Interrompi")
                    }
                }

                is TransportState.Failed -> {
                    Text("Connessione non riuscita", style = MaterialTheme.typography.titleMedium)
                    Text(stream.reason, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text("Riprova")
                    }
                }
            }
        }
    }
}

/**
 * L'ascolto continuo: l'unico modo di sapere che il bambino sta bene senza
 * tenere l'app aperta.
 *
 * Mentre e acceso l'ascolto a richiesta sparisce: la sessione e gia aperta, e
 * due pulsanti che fanno la stessa cosa in modo diverso confondono e basta.
 */
@Composable
private fun ContinuousCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val health by ContinuousListening.health.collectAsStateWithLifecycle()
    val since by ContinuousListening.since.collectAsStateWithLifecycle()

    HeroCard(
        title = "Ascolto continuo",
        status = if (!enabled) {
            "Spento: ricevi solo gli avvisi"
        } else {
            when (health) {
                ContinuousListening.Health.STARTING -> "Connessione…"
                ContinuousListening.Health.LISTENING -> "Audio in arrivo dalla cameretta"
                ContinuousListening.Health.RECOVERING -> "Riconnessione…"
                ContinuousListening.Health.LOST -> "Audio interrotto. Controlla il Nursery Node."
                ContinuousListening.Health.PAUSED -> "In pausa, un'altra app sta usando l'audio"
                ContinuousListening.Health.NURSERY_GONE -> "Nursery Node scollegato"
            }
        },
        tone = when {
            !enabled -> HeroTone.IDLE
            health == ContinuousListening.Health.LISTENING -> HeroTone.GOOD
            health == ContinuousListening.Health.LOST ||
                health == ContinuousListening.Health.NURSERY_GONE -> HeroTone.BAD
            else -> HeroTone.PENDING
        },
        icon = when {
            !enabled -> Icons.Default.HearingDisabled
            health == ContinuousListening.Health.LOST ||
                health == ContinuousListening.Health.NURSERY_GONE -> Icons.Default.WarningAmber
            else -> Icons.Default.Hearing
        },
    ) {
        SettingSwitch(
            title = "Tieni l'audio sempre aperto",
            description = "Anche a schermo spento e con l'app chiusa. Tieni il telefono in carica.",
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )

        if (!enabled) return@HeroCard

        val history by StreamLevel.history.collectAsStateWithLifecycle()
        // Distingue una cameretta tranquilla da uno stream che non porta nulla.
        LevelChart(history = history, thresholdDb = RmsNoiseDetector.SILENCE_DB)

        if (since != 0L) {
            // Ricalcolato ogni minuto: un tempo fermo a "0 min" per tutta la
            // notte direbbe l'opposto di quello che deve dire.
            val now by produceState(System.currentTimeMillis(), since) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(60_000L)
                }
            }
            Text("Attivo da ${elapsedSince(since, now)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun elapsedSince(startedAt: Long, now: Long): String {
    val minutes = ((now - startedAt) / 60_000L).coerceAtLeast(0)
    if (minutes < 60) return "$minutes min"
    return "${minutes / 60} h ${minutes % 60} min"
}

@Composable
fun AppHeader(title: String, subtitle: String, connection: ConnectionState?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_art),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
        )

        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (connection != null) ConnectionDot(connection)
    }
}

/** Lo stato dell'Hub ridotto a quello che serve sapere di sfuggita. */
@Composable
private fun ConnectionDot(connection: ConnectionState) {
    val (label, container, content) = when (connection) {
        ConnectionState.Connected -> Triple(
            "Connesso",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        ConnectionState.Connecting -> Triple(
            "Connessione…",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        else -> Triple(
            "Offline",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Surface(color = container, shape = RoundedCornerShape(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ConnectionBanner(connection: ConnectionState, onReconnect: () -> Unit) {
    val (label, showRetry) = when (connection) {
        ConnectionState.Connected -> "Connesso all'Hub" to false
        ConnectionState.Connecting -> "Connessione all'Hub in corso…" to false
        ConnectionState.Disconnected -> "Non connesso all'Hub" to true
        is ConnectionState.Failed -> "Hub non raggiungibile: ${connection.reason}" to true
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (connection) {
                ConnectionState.Connected -> MaterialTheme.colorScheme.tertiaryContainer
                ConnectionState.Connecting -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.errorContainer
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

/** Solo ora e minuti se è di oggi, altrimenti anche il giorno. */
private fun formatMoment(timestamp: Long): String {
    val moment = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    val today = LocalDate.now(ZoneId.systemDefault())

    val pattern = if (moment.toLocalDate() == today) "HH:mm" else "d MMM, HH:mm"
    return moment.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

@Composable
private fun EventRow(event: HubMessage) {
    val moment = when (event) {
        is HubMessage.Noise -> event.startedAt
        is HubMessage.NurseryOnline -> event.at
        is HubMessage.NurseryOffline -> event.lastSeen
        else -> null
    }

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
        // Il signaling e filtrato prima di arrivare qui: e traffico fra i due
        // telefoni, non un evento da mostrare.
        else -> return
    }

    val icon = when (event) {
        is HubMessage.Noise -> Icons.Default.VolumeUp
        is HubMessage.NurseryOnline -> Icons.Default.Hearing
        is HubMessage.NurseryOffline -> Icons.Default.WarningAmber
        else -> Icons.Default.Info
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (event is HubMessage.NurseryOffline) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (moment != null && moment > 0) {
                Text(
                    formatMoment(moment),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
