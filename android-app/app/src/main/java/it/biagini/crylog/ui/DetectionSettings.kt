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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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

@Composable
fun PresetSelector(
    title: String,
    description: String,
    presets: List<Preset>,
    selectedMs: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
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

        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
