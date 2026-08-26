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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import it.biagini.crylog.core.RmsNoiseDetector
import it.biagini.crylog.nursery.NoiseMonitor
import androidx.compose.material3.LocalContentColor

/**
 * Livello degli ultimi trenta secondi, con la soglia disegnata sopra.
 *
 * Un numero che cambia dieci volte al secondo non dice se il rumore stia
 * sfiorando la soglia o le stia lontano: il grafico serve a regolare la
 * sensibilità guardando, invece che per tentativi.
 */
@Composable
fun LevelChart(
    history: FloatArray,
    thresholdDb: Double,
    modifier: Modifier = Modifier,
) {
    // Dentro l'eroe il grafico non puo' avere un fondo suo: userebbe un grigio
    // che con il contenitore verde o rosso non c'entra niente. Si appoggia al
    // colore del contenuto, con l'alpha a fare il lavoro.
    val barColor = LocalContentColor.current
    val overColor = MaterialTheme.colorScheme.error
    val thresholdColor = LocalContentColor.current.copy(alpha = 0.45f)
    // Un velo del colore del contenuto, non un grigio fisso: dentro un
    // contenitore verde o rosso un grigio non c'entrerebbe niente.
    val surface = LocalContentColor.current.copy(alpha = 0.08f)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(MaterialTheme.shapes.small)
                .background(surface)
                .padding(4.dp),
        ) {
            val floor = RmsNoiseDetector.SILENCE_DB.toFloat()
            fun toY(db: Float): Float =
                size.height * (1f - ((db - floor) / -floor)).coerceIn(0f, 1f)

            val slot = size.width / history.size
            val barWidth = (slot * 0.7f).coerceAtLeast(1f)

            history.forEachIndexed { index, db ->
                val top = toY(db)
                if (top >= size.height) return@forEachIndexed
                drawRect(
                    color = if (db >= thresholdDb) overColor else barColor,
                    topLeft = Offset(index * slot, top),
                    size = androidx.compose.ui.geometry.Size(barWidth, size.height - top),
                )
            }

            val thresholdY = toY(thresholdDb.toFloat())
            drawLine(
                color = thresholdColor,
                start = Offset(0f, thresholdY),
                end = Offset(size.width, thresholdY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${NoiseMonitor.HISTORY_SECONDS} secondi fa",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "adesso",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

