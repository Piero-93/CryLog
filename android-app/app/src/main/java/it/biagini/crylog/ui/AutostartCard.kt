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

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import it.biagini.crylog.hub.DeviceStore

/**
 * Avvisa che su Xiaomi serve l'avvio automatico, o l'app non riceve niente.
 *
 * MIUI rifiuta di avviare il processo di un'app ferma per un broadcast — nei
 * log: `process is not permitted to auto start` — e una notifica push viene
 * consegnata esattamente cosi'. Il risultato e' che ad app chiusa **non arriva
 * nessun avviso**, in silenzio, che e' il modo peggiore in cui questo sistema
 * puo' fallire.
 *
 * Non c'e' modo di leggere quel permesso da un'API pubblica, quindi non si puo'
 * dire se sia gia' concesso: si puo' solo chiederlo una volta e lasciare che
 * l'utente lo tolga di mezzo. Per lo stesso motivo la card compare solo sui
 * telefoni dove il problema esiste, invece di allarmare tutti gli altri.
 */
@Composable
fun AutostartCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = DeviceStore(context)

    var dismissed by rememberSaveable { mutableStateOf(store.autostartNoticeSeen) }
    if (dismissed || !needsAutostart()) return

    NoticeCard(
        text = "Su questo telefono gli avvisi ad app chiusa arrivano solo se CryLog " +
            "ha l'avvio automatico. Senza, non ricevi nulla e non te ne accorgi.",
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Tight)) {
            OutlinedButton(onClick = { openAutostartSettings(context) }) {
                Text("Apri le impostazioni")
            }
            TextButton(
                onClick = {
                    store.autostartNoticeSeen = true
                    dismissed = true
                },
            ) {
                Text("Fatto")
            }
        }
    }
}

/** I produttori che spengono le app di loro iniziativa. */
private fun needsAutostart(): Boolean {
    val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
    return listOf("xiaomi", "redmi", "poco").any { it in brand }
}

/**
 * Apre la schermata dell'avvio automatico, o quella dell'app se non esiste.
 *
 * Il componente e' interno a MIUI e puo' sparire con un aggiornamento: se non
 * si apre, meglio le impostazioni dell'app che un errore.
 */
private fun openAutostartSettings(context: android.content.Context) {
    val miui = Intent().setComponent(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(miui) }
        .recoverCatching { context.startActivity(fallback) }
}
