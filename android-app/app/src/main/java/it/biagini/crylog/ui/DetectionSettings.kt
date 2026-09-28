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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.biagini.crylog.core.NoiseSensitivity

/**
 * Preset invece di millisecondi.
 *
 * I valori grezzi non aiutano nessuno a decidere: la differenza fra 500 e 800
 * millisecondi non si sceglie, si osserva. Le etichette descrivono cosa
 * cambia, i numeri restano dietro.
 */
data class Preset(val label: String, val valueMs: Long)

val MIN_DURATION_PRESETS = listOf(
    Preset("Poco", 200L),
    Preset("Normale", 500L),
    Preset("Molto", 1_500L),
)

val COOLDOWN_PRESETS = listOf(
    Preset("30 s", 30_000L),
    Preset("1 min", 60_000L),
    Preset("5 min", 300_000L),
)

/**
 * Le tre regolazioni del rilevamento, uguali sui due telefoni.
 *
 * Il Parent Node le mostra per il Nursery Node che ascolta: stesse parole e
 * stessi cursori, perche' lo stesso valore deve leggersi allo stesso modo da
 * chi e' in cameretta e da chi e' in salotto.
 */
@Composable
fun DetectionControls(
    sensitivity: Float,
    minDurationMs: Long,
    cooldownMs: Long,
    onSensitivityChange: (Float) -> Unit,
    onSensitivityCommit: () -> Unit,
    onMinDuration: (Long) -> Unit,
    onCooldown: (Long) -> Unit,
) {
    SettingRow(
        title = "Sensibilità",
        trailing = "${NoiseSensitivity.asPercent(sensitivity.toDouble())}%",
        description = "Se scattano falsi allarmi abbassala; se non sente il bambino alzala.",
    ) {
        Slider(
            value = sensitivity,
            onValueChange = onSensitivityChange,
            onValueChangeFinished = onSensitivityCommit,
            valueRange = 0f..1f,
        )
    }

    PresetSelector(
        title = "Ignora i rumori brevi",
        description = "Alzala se una porta che sbatte fa scattare l'avviso.",
        presets = MIN_DURATION_PRESETS,
        selectedMs = minDurationMs,
        onSelect = onMinDuration,
    )

    PresetSelector(
        title = "Avvisa al massimo ogni",
        description = "Evita decine di notifiche durante un pianto lungo.",
        presets = COOLDOWN_PRESETS,
        selectedMs = cooldownMs,
        onSelect = onCooldown,
    )
}

@Composable
fun PresetSelector(
    title: String,
    description: String,
    presets: List<Preset>,
    selectedMs: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Titolo e descrizione li mette SettingRow: prima ogni controllo si
    // scriveva la propria intestazione, e ne uscivano tre stili diversi per la
    // stessa identica cosa.
    SettingRow(title = title, description = description) {
        SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
            presets.forEachIndexed { index, preset ->
                SegmentedButton(
                    selected = preset.valueMs == selectedMs,
                    onClick = { onSelect(preset.valueMs) },
                    shape = SegmentedButtonDefaults.itemShape(index, presets.size),
                ) {
                    Text(preset.label)
                }
            }
        }
    }
}
