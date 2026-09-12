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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.biagini.crylog.core.NurseryInfo

/**
 * Quale cameretta ascoltare, quando ce n'è più di una.
 *
 * **Compare solo con due o più Nursery Node accoppiati.** Con uno solo non c'è
 * niente da scegliere, e un menu con una voce sola è rumore: la regola di
 * adozione lo prende da sé.
 *
 * Esiste perché senza di questo la scelta si poteva fare soltanto dalla pagina
 * web dell'Hub, e due client che si comportano in modo diverso davanti alla
 * stessa situazione sono il modo più efficace di rendere incomprensibile un
 * sistema.
 */
@Composable
fun NurseryPicker(
    nurseries: List<NurseryInfo>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (nurseries.size < 2) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Quale cameretta", style = MaterialTheme.typography.titleMedium)
            Text(
                "Gli avvisi e l'ascolto riguardano quella scelta. " +
                    "Funzione sperimentale: provata finora con una cameretta sola.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            nurseries.forEach { nursery ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(nursery.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = nursery.id == selectedId,
                        // Il click sta sulla riga intera: un bersaglio piccolo
                        // al buio, con un bambino in braccio, non si prende.
                        onClick = null,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(nursery.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (nursery.online) "collegato" else "non collegato",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
