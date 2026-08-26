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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.biagini.crylog.core.PairingCode

/**
 * Campo a caselle per il codice di pairing. Un solo campo di testo invisibile
 * regge input e cursore; le caselle sono decorazione, così la selezione e la
 * tastiera si comportano come ci si aspetta.
 */
@Composable
fun PairingCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Il campo tiene anche la posizione del cursore, non solo i caratteri:
    // serve a poter toccare una cifra sbagliata e correggere quella, invece di
    // cancellare tutto quello che viene dopo.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) {
        field = TextFieldValue(value, TextRange(value.length.coerceAtMost(value.length)))
    }

    BasicTextField(
        value = field,
        onValueChange = { updated ->
            val clean = PairingCode.sanitize(updated.text)
            val caret = updated.selection.end.coerceIn(0, clean.length)
            field = TextFieldValue(clean, TextRange(caret))
            onValueChange(clean)
        },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused },
        textStyle = LocalTextStyle.current.copy(color = androidx.compose.ui.graphics.Color.Transparent),
        cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(PairingCode.LENGTH) { index ->
                    if (index == PairingCode.LENGTH / 2) {
                        Text(
                            "-",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                    CodeCell(
                        char = value.getOrNull(index),
                        // La casella attiva è quella dove finirà il prossimo carattere.
                        highlighted = focused &&
                            index == field.selection.end.coerceAtMost(PairingCode.LENGTH - 1),
                        onClick = {
                            // Toccando una cifra la si seleziona: il carattere
                            // successivo digitato prende il suo posto, invece di
                            // infilarsi in coda.
                            val end = (index + 1).coerceAtMost(value.length)
                            field = TextFieldValue(value, TextRange(index.coerceAtMost(value.length), end))
                            focusRequester.requestFocus()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun CodeCell(char: Char?, highlighted: Boolean, onClick: () -> Unit) {
    // La cornice c'e' sempre, anche sulle caselle vuote: sono il posto dove
    // andra' qualcosa, e senza bordo non si capisce quanti caratteri servono.
    val borderColor = when {
        highlighted -> MaterialTheme.colorScheme.primary
        char != null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = Modifier
            // 48dp e' il minimo per un bersaglio da toccare: a 36 il dito ci
            // arrivava male, e queste caselle adesso si toccano per correggerle.
            .width(48.dp)
            .height(56.dp)
            // Senza indicazione visiva del tocco: la casella ha gia' il suo
            // bordo che si accende, e un'onda sopra sarebbe rumore.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                MaterialTheme.shapes.small,
            )
            .border(
                width = if (highlighted) 2.dp else 1.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.small,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = char?.toString().orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
        )
    }
}
