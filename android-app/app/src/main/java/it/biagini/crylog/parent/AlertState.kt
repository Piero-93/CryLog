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

package it.biagini.crylog.parent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Se l'avviso in corso è stato preso in carico da qualcuno.
 *
 * Un avviso che insiste finché non lo si scarta ha bisogno di sapere quando è
 * stato scartato, e la notifica vive in un processo che non conosce l'[Alerter].
 * Questo è il pezzo di stato condiviso che li mette in comunicazione.
 */
object AlertState {

    /**
     * Se c'e' un avviso che sta insistendo in questo momento.
     *
     * Osservabile e non solo leggibile: la schermata deve poter offrire un modo
     * di zittirlo. Senza, un avviso partito mentre l'app e' gia' aperta non
     * aveva nessun comando che lo fermasse — la notifica che lo avrebbe
     * scartato spariva al primo tocco, e restava solo aspettare il tetto dei
     * cinque minuti.
     */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Un nuovo avviso comincia: da qui in poi insiste. */
    fun arm() {
        _active.value = true
    }

    fun dismiss() {
        _active.value = false
    }

    val isDismissed: Boolean get() = !_active.value
}

/**
 * Ferma l'avviso quando la notifica viene scartata o si tocca "Va bene".
 *
 * Serve un ricevitore e non un semplice callback perché scartare una notifica è
 * un evento del sistema, che arriva da fuori il processo dell'app.
 */
class AlertDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AlertState.dismiss()
    }

    companion object {
        const val ACTION = "it.biagini.crylog.DISMISS_ALERT"

        fun intent(context: Context): Intent =
            Intent(context, AlertDismissReceiver::class.java).setAction(ACTION)
    }
}
