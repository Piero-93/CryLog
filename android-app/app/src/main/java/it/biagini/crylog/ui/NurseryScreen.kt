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
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.biagini.crylog.MainViewModel
import it.biagini.crylog.core.ConnectionState
import it.biagini.crylog.core.NoiseSensitivity
import it.biagini.crylog.core.RmsNoiseDetector
import it.biagini.crylog.nursery.NoiseMonitor
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.WarningAmber

@Composable
fun NurseryScreen(
    viewModel: MainViewModel,
    deviceName: String,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val armed by NoiseMonitor.armed.collectAsStateWithLifecycle()
    val levelDb by NoiseMonitor.levelDb.collectAsStateWithLifecycle()
    val connection by NoiseMonitor.connection.collectAsStateWithLifecycle()
    val eventCount by NoiseMonitor.eventCount.collectAsStateWithLifecycle()
    val history by NoiseMonitor.history.collectAsStateWithLifecycle()

    // rememberSaveable e non remember: con `remember` bastava ruotare lo schermo
    // per veder tornare indietro la sensibilità appena regolata.
    var sensitivity by rememberSaveable {
        mutableStateOf(NoiseSensitivity.fromThresholdDb(viewModel.noiseThresholdDb).toFloat())
    }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var confirmingUnpair by rememberSaveable { mutableStateOf(false) }
    var minDuration by rememberSaveable { mutableStateOf(viewModel.noiseMinDurationMs) }
    var cooldown by rememberSaveable { mutableStateOf(viewModel.noiseCooldownMs) }
    var audioOnly by rememberSaveable { mutableStateOf(viewModel.audioOnly) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    val required = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            // Serve solo se un Parent Node chiedera il video: senza, il
            // monitoraggio funziona lo stesso.
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // Senza microfono non c'è monitoraggio; la notifica invece è solo
        // sgradevole da perdere, non fatale.
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            permissionDenied = false
            viewModel.arm()
        } else {
            permissionDenied = true
        }
    }

    Column(
        // Senza scorrimento la parte bassa, pulsanti compresi, resta fuori
        // schermo sui telefoni piu corti.
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(Space.Section),
    ) {
        AppHeader(title = "Nursery Node", subtitle = deviceName, connection = connection)

        // L'eroe: cosa sta succedendo adesso, e il pulsante che lo cambia.
        // Prima quel pulsante era l'ultima cosa della pagina, dopo nove
        // regolazioni che si toccano una volta nella vita.
        val threshold = NoiseSensitivity.toThresholdDb(sensitivity.toDouble())
        HeroCard(
            title = if (armed) "In ascolto" else "Non sta monitorando",
            status = when {
                !armed -> "Il microfono è spento"
                connection is ConnectionState.Connected -> "Gli avvisi arrivano ai Parent Node"
                connection is ConnectionState.Connecting -> "Connessione all'Hub…"
                else -> "Senza Hub gli avvisi non partono"
            },
            tone = when {
                !armed -> HeroTone.IDLE
                connection is ConnectionState.Connected -> HeroTone.GOOD
                connection is ConnectionState.Connecting -> HeroTone.PENDING
                else -> HeroTone.BAD
            },
            icon = when {
                !armed -> Icons.Default.MicOff
                connection is ConnectionState.Connected -> Icons.Default.Hearing
                connection is ConnectionState.Connecting -> Icons.Default.Hearing
                else -> Icons.Default.WarningAmber
            },
        ) {
            if (armed) {
                LevelChart(history = history, thresholdDb = threshold)
                LevelMeter(levelDb = levelDb, thresholdDb = threshold)
                Text(
                    if (eventCount == 0) {
                        "Ancora nessun rumore, soglia a %.0f dBFS".format(threshold)
                    } else {
                        "$eventCount rumori in questa sessione"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (permissionDenied) {
                Text(
                    "Senza microfono il monitoraggio non può funzionare. " +
                        "Concedilo dalle impostazioni dell'app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Fermare la sorveglianza non deve avere lo stesso invito che ha
            // avviarla: pieno per accendere, contornato per spegnere.
            val onClick = {
                if (armed) {
                    viewModel.disarm()
                } else {
                    val missing = required.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) viewModel.arm() else launcher.launch(missing.toTypedArray())
                }
            }

            if (armed) {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Interrompi")
                }
            } else {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Avvia il monitoraggio")
                }
            }
        }

        DndAccessCard()

        CollapsibleSection("Rilevamento") {
            SettingsCard {
                SettingRow(
                    title = "Sensibilità",
                    trailing = "${NoiseSensitivity.asPercent(sensitivity.toDouble())}%",
                    description = "Se scattano falsi allarmi abbassala; se non sente il bambino alzala.",
                ) {
                    Slider(
                        value = sensitivity,
                        onValueChange = { sensitivity = it },
                        onValueChangeFinished = {
                            viewModel.setThreshold(NoiseSensitivity.toThresholdDb(sensitivity.toDouble()))
                        },
                        valueRange = 0f..1f,
                    )
                }

                PresetSelector(
                    title = "Ignora i rumori brevi",
                    description = "Alzala se una porta che sbatte fa scattare l'avviso.",
                    presets = MIN_DURATION_PRESETS,
                    selectedMs = minDuration,
                    onSelect = { minDuration = it; viewModel.setMinDuration(it) },
                )

                PresetSelector(
                    title = "Avvisa al massimo ogni",
                    description = "Evita decine di notifiche durante un pianto lungo.",
                    presets = COOLDOWN_PRESETS,
                    selectedMs = cooldown,
                    onSelect = { cooldown = it; viewModel.setCooldown(it) },
                )
            }
        }

        CollapsibleSection("Video") {
            SettingsCard {
                SettingSwitch(
                    title = "Solo audio",
                    description = "La fotocamera non si accende mai, qualunque cosa chieda un Parent Node.",
                    checked = audioOnly,
                    onCheckedChange = { audioOnly = it; viewModel.setAudioOnly(it) },
                )
            }

            if (!audioOnly && !cameraGranted) {
                NoticeCard(
                    text = "Senza accesso alla fotocamera i Parent Node riceveranno solo audio, " +
                        "anche se chiedono il video.",
                    severe = true,
                ) {
                    OutlinedButton(
                        onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                    ) {
                        Text("Consenti la fotocamera")
                    }
                }
            }
        }

        TextButton(
            onClick = { confirmingUnpair = true },
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("Scollega questo dispositivo") }
    }

    if (confirmingUnpair) {
        AlertDialog(
            onDismissRequest = { confirmingUnpair = false },
            title = { Text("Scollegare il dispositivo?") },
            text = {
                Text(
                    "Il monitoraggio si ferma e il dispositivo viene cancellato dall'Hub. " +
                        "Per ricollegarlo servirà un nuovo codice.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingUnpair = false
                    viewModel.disarm()
                    onUnpair()
                }) { Text("Scollega") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnpair = false }) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun StatusCard(armed: Boolean, connection: ConnectionState) {
    // Verde solo quando il monitoraggio funziona davvero: in ascolto e con
    // l'Hub raggiungibile. In ascolto senza Hub non e' uno stato tranquillo,
    // e' un microfono acceso i cui avvisi non arrivano da nessuna parte.
    val (text, container) = when {
        !armed -> "Non sta ascoltando" to MaterialTheme.colorScheme.surfaceVariant
        connection is ConnectionState.Connected ->
            "In ascolto" to MaterialTheme.colorScheme.tertiaryContainer
        connection is ConnectionState.Connecting ->
            "In ascolto, connessione all'Hub…" to MaterialTheme.colorScheme.secondaryContainer
        else ->
            "In ascolto, ma senza Hub: gli avvisi non partono" to MaterialTheme.colorScheme.errorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LevelMeter(levelDb: Double, thresholdDb: Double) {
    // Da -100..0 dBFS a 0..1, per avere una barra leggibile.
    val fraction = ((levelDb - RmsNoiseDetector.SILENCE_DB) / -RmsNoiseDetector.SILENCE_DB)
        .coerceIn(0.0, 1.0)
    val markAt = ((thresholdDb - RmsNoiseDetector.SILENCE_DB) / -RmsNoiseDetector.SILENCE_DB)
        .coerceIn(0.0, 1.0)
    val over = levelDb >= thresholdDb

    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val fill = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val reading = "Livello %.0f dBFS, soglia %.0f dBFS".format(levelDb, thresholdDb)

    // Disegnato a mano e non con LinearProgressIndicator: quello anima ogni
    // cambio di valore, ed e' pensato per un progresso che avanza, non per un
    // livello che si muove dieci volte al secondo. Ne usciva molle e in ritardo
    // sul suono.
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .semantics { contentDescription = reading },
    ) {
        drawRect(color = track)
        drawRect(color = fill, size = Size(size.width * fraction.toFloat(), size.height))
        val x = size.width * markAt.toFloat()
        drawLine(
            color = mark,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 2f,
        )
    }
}
