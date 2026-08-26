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

    var sensitivity by remember {
        mutableStateOf(NoiseSensitivity.fromThresholdDb(viewModel.noiseThresholdDb).toFloat())
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var confirmingUnpair by remember { mutableStateOf(false) }
    var minDuration by remember { mutableStateOf(viewModel.noiseMinDurationMs) }
    var cooldown by remember { mutableStateOf(viewModel.noiseCooldownMs) }
    var audioOnly by remember { mutableStateOf(viewModel.audioOnly) }
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
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nursery Node", style = MaterialTheme.typography.headlineMedium)
        Text(deviceName, style = MaterialTheme.typography.bodyLarge)

        StatusCard(armed = armed, connection = connection)

        if (armed) {
            Text("Livello attuale", style = MaterialTheme.typography.titleSmall)
            LevelChart(
                history = history,
                thresholdDb = NoiseSensitivity.toThresholdDb(sensitivity.toDouble()),
            )
            LevelMeter(
                levelDb = levelDb,
                thresholdDb = NoiseSensitivity.toThresholdDb(sensitivity.toDouble()),
            )
            Text(
                "%.0f dBFS, scatta a %.0f dBFS".format(
                    levelDb,
                    NoiseSensitivity.toThresholdDb(sensitivity.toDouble()),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (eventCount == 0) {
                    "Ancora nessun rumore."
                } else {
                    "Rumori rilevati in questa sessione: $eventCount"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalDivider()

        Text(
            "Sensibilità: ${NoiseSensitivity.asPercent(sensitivity.toDouble())}%",
            style = MaterialTheme.typography.titleSmall,
        )
        Slider(
            value = sensitivity,
            onValueChange = { sensitivity = it },
            onValueChangeFinished = {
                viewModel.setThreshold(NoiseSensitivity.toThresholdDb(sensitivity.toDouble()))
            },
            valueRange = 0f..1f,
        )
        Text(
            "Più a destra sente di più. Se scattano falsi allarmi, abbassala; se non " +
                "sente il bambino, alzala.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PresetSelector(
            title = "Ignora i rumori brevi",
            description = "Quanto a lungo deve durare un rumore per contare. " +
                "Alzala se una porta che sbatte fa scattare l'avviso.",
            presets = MIN_DURATION_PRESETS,
            selectedMs = minDuration,
            onSelect = { minDuration = it; viewModel.setMinDuration(it) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Solo audio", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "La fotocamera non si accende mai, qualunque cosa chieda un Parent Node.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = audioOnly,
                onCheckedChange = { audioOnly = it; viewModel.setAudioOnly(it) },
            )
        }

        // Il permesso fotocamera si chiede all'avvio del monitoraggio, ma chi
        // aveva già armato prima di questa versione non lo ha mai visto: senza
        // un modo per concederlo dopo, il video resterebbe nero per sempre.
        if (!audioOnly && !cameraGranted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Senza accesso alla fotocamera i Parent Node riceveranno " +
                            "solo audio, anche se chiedono il video.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                    ) {
                        Text("Consenti la fotocamera")
                    }
                }
            }
        }

        PresetSelector(
            title = "Avvisa al massimo ogni",
            description = "Dopo un avviso il rilevamento fa una pausa. Serve a non " +
                "ricevere decine di notifiche durante un pianto lungo.",
            presets = COOLDOWN_PRESETS,
            selectedMs = cooldown,
            onSelect = { cooldown = it; viewModel.setCooldown(it) },
        )

        if (permissionDenied) {
            Text(
                "Senza accesso al microfono il monitoraggio non può funzionare. " +
                    "Concedilo dalle impostazioni dell'app.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = {
                if (armed) {
                    viewModel.disarm()
                } else {
                    val missing = required.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) viewModel.arm() else launcher.launch(missing.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (armed) "Interrompi il monitoraggio" else "Avvia il monitoraggio")
        }

        TextButton(onClick = { confirmingUnpair = true }) { Text("Scollega questo dispositivo") }
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
    val (text, container) = when {
        !armed -> "Non sta ascoltando" to MaterialTheme.colorScheme.surfaceVariant
        connection is ConnectionState.Connected ->
            "In ascolto, collegato all'Hub" to MaterialTheme.colorScheme.secondaryContainer
        connection is ConnectionState.Connecting ->
            "In ascolto, connessione…" to MaterialTheme.colorScheme.surfaceVariant
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
    val overThreshold = levelDb >= thresholdDb

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { fraction.toFloat() },
            modifier = Modifier.weight(1f).height(12.dp),
            color = if (overThreshold) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}
