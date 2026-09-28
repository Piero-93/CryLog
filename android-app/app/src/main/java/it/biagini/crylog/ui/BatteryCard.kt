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

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Chiede di togliere CryLog dall'ottimizzazione della batteria.
 *
 * Il servizio in foreground non basta: con l'ottimizzazione attiva il sistema
 * puo' congelare il processo a schermo spento, e un Nursery Node congelato e'
 * collegato ma muto — l'Hub se ne accorge solo dopo novanta secondi.
 *
 * A differenza dell'avvio automatico questo permesso si puo' leggere, quindi
 * la card compare solo finche' manca e non va archiviata a mano.
 */
@Composable
fun BatteryCard(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    fun exempt() = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    var granted by remember { mutableStateOf(exempt()) }

    // Si concede in una finestra di sistema, che non restituisce risultati:
    // si ricontrolla al rientro.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = exempt() }

    if (granted) return

    // MIUI ha un risparmio energetico suo, che l'esenzione di Android non
    // spegne e che nessuna API permette di leggere: si puo' solo dirlo.
    val miui = if (isXiaomi()) {
        " Su Xiaomi imposta anche Risparmio batteria su \"Nessuna restrizione\"."
    } else {
        ""
    }

    NoticeCard(text = text + miui, modifier = modifier) {
        OutlinedButton(onClick = { requestExemption(context) }) {
            Text("Consenti")
        }
    }
}

/**
 * La richiesta diretta, o l'elenco delle app se il telefono non la offre.
 *
 * Alcune ROM tolgono la finestra di richiesta: meglio l'elenco da scorrere
 * che un pulsante che non fa niente.
 */
private fun requestExemption(context: android.content.Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.fromParts("package", context.packageName, null))
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    runCatching { context.startActivity(direct) }
        .recoverCatching { context.startActivity(list) }
}
