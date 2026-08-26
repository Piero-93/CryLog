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

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Chiede il permesso di suonare anche con il Non disturbare acceso.
 *
 * Gli avvisi di CryLog dichiarano `setBypassDnd`, ma Android lo concede solo a
 * chi ha l'accesso alle politiche di notifica: senza, la richiesta viene
 * ignorata in silenzio e l'avviso "nessuno sta monitorando" resta muto proprio
 * di notte, che è quando serve.
 *
 * **Va concesso prima**, non dopo: il bypass si fissa quando il canale viene
 * creato, e un canale che esiste già non si può aggiornare. Per questo la card
 * sta in cima e non aspetta il primo allarme perso.
 */
@Composable
fun DndAccessCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = context.getSystemService(NotificationManager::class.java)

    var granted by remember { mutableStateOf(manager?.isNotificationPolicyAccessGranted ?: true) }

    // Il permesso si concede in un'altra app, quindi non arriva nessun
    // risultato: si ricontrolla al rientro.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = manager?.isNotificationPolicyAccessGranted ?: true
    }

    if (granted) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Con il Non disturbare acceso gli avvisi restano muti.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                        )
                    }
                },
            ) {
                Text("Consenti gli avvisi")
            }
        }
    }
}
