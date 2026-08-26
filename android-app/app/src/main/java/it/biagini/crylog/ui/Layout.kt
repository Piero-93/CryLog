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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * La scala delle distanze.
 *
 * Tre valori e non di piu': fra sezioni, dentro una sezione, fra due righe che
 * si appartengono. Quando ogni distanza vale dodici, niente e' raggruppato e
 * tutto ha lo stesso peso — che era il difetto di queste schermate.
 */
object Space {
    val Section = 24.dp
    val Item = 12.dp
    val Tight = 6.dp
    val Screen = 20.dp
}

/**
 * Il blocco che risponde a "sta funzionando adesso?".
 *
 * È l'unico elemento colorato della schermata, e sta in cima con dentro
 * l'azione che ne cambia lo stato. Prima quella risposta era una card piccola
 * in mezzo ad altre, e il pulsante per accendere il monitoraggio stava dopo
 * nove regolazioni: bisognava scorrere tutta la pagina per usare l'app.
 *
 * Il colore si anima perché il passaggio da "in ascolto" a "non risponde"
 * capita mentre non stai guardando, e uno stacco netto si nota; una
 * transizione la si vede accadere.
 */
@Composable
fun HeroCard(
    title: String,
    status: String,
    tone: HeroTone,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val container by animateColorAsState(tone.container(), label = "hero")
    val onContainer = tone.onContainer()

    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(Space.Item),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.Item),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(status, style = MaterialTheme.typography.bodyLarge)
                }
            }
            content()
        }
    }
}

/** Come sta la cosa che l'eroe descrive, in termini di colore. */
enum class HeroTone {
    /** Spento, in attesa: neutro, non è né buono né cattivo. */
    IDLE,

    /** In corso, esito non ancora noto. */
    PENDING,

    /** Funziona davvero. */
    GOOD,

    /** Non funziona, e qualcuno deve saperlo. */
    BAD,
    ;

    @Composable
    fun container(): Color = when (this) {
        IDLE -> MaterialTheme.colorScheme.surfaceContainerHigh
        PENDING -> MaterialTheme.colorScheme.secondaryContainer
        GOOD -> MaterialTheme.colorScheme.tertiaryContainer
        BAD -> MaterialTheme.colorScheme.errorContainer
    }

    @Composable
    fun onContainer(): Color = when (this) {
        IDLE -> MaterialTheme.colorScheme.onSurface
        PENDING -> MaterialTheme.colorScheme.onSecondaryContainer
        GOOD -> MaterialTheme.colorScheme.onTertiaryContainer
        BAD -> MaterialTheme.colorScheme.onErrorContainer
    }
}

/**
 * Un gruppo di impostazioni sotto un titolo.
 *
 * Il titolo e' piccolo e in colore secondario: deve dire di cosa si parla
 * senza competere con quello che c'e' dentro.
 */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Item)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

/**
 * Una sezione che sta chiusa finché non serve.
 *
 * Sensibilità, durata minima, cooldown: si toccano una volta e poi mai più, ma
 * aperte occupavano l'intera schermata e spingevano fuori quello che si guarda
 * ogni giorno.
 */
@Composable
fun CollapsibleSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "chevron")

    Column(
        modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(Space.Item),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = Space.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Tight),
        ) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp).rotate(turn),
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (open) content()
    }
}

/** Le righe di una sezione, raccolte in una superficie sola invece che sparse. */
@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.Section),
            content = content,
        )
    }
}

/** Un interruttore con la sua etichetta, e la spiegazione solo se serve. */
@Composable
fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.Item),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Una riga di impostazione con un controllo qualsiasi sotto l'etichetta.
 *
 * Serve a slider e selettori, che non stanno accanto al testo come un
 * interruttore: senza questo ognuno si scriveva la propria intestazione e ne
 * uscivano tre stili diversi per la stessa cosa.
 */
@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Tight)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (trailing != null) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        content()
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Un avviso che non si affida al colore soltanto.
 *
 * Gli errori di permesso erano testo rosso su fondo normale: chi non distingue
 * i colori non li vedeva come errori. Qui c'è anche il contenitore, e la forma
 * dice quanto è grave prima ancora che il testo venga letto.
 */
@Composable
fun NoticeCard(
    text: String,
    severe: Boolean = false,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (severe) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (severe) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(Space.Item)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.Item)) {
                Icon(
                    imageVector = if (severe) Icons.Default.WarningAmber else Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            action?.invoke()
        }
    }
}


