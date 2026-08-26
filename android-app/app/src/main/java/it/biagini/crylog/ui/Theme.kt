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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * La palette di CryLog, costruita sull'indigo dell'icona.
 *
 * Niente colori dinamici, che pure sarebbero gratis su Android 12 e piacciono.
 * In questa app il colore porta informazione: rosso vuol dire che nessuno sta
 * ascoltando, e il contenitore acceso vuol dire che l'audio arriva. Presi dallo
 * sfondo del telefono quei significati cambiano da dispositivo a dispositivo, e
 * uno stato che non si riconosce a colpo d'occhio non serve a niente.
 */
private val Brand = Color(0xFF1C2B4C)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E4A7D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),

    secondary = Color(0xFF565E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),

    tertiary = Color(0xFF3C6A4B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBEF0C9),
    onTertiaryContainer = Color(0xFF00210E),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1B1F),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1B1F),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),

    // I cinque livelli di contenitore: sono loro a dare profondita' senza
    // bordi. Prima esisteva un grigio solo, e una casella dentro una card dello
    // stesso grigio semplicemente spariva.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F4FA),
    surfaceContainer = Color(0xFFF1EEF5),
    surfaceContainerHigh = Color(0xFFEBE8EF),
    surfaceContainerHighest = Color(0xFFE5E2EA),
    surfaceBright = Color(0xFFFDFBFF),
    surfaceDim = Color(0xFFDDD9E0),

    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002E69),
    // Il colore dell'icona, al suo posto: al buio è il fondo di ciò che conta.
    primaryContainer = Brand,
    onPrimaryContainer = Color(0xFFD8E2FF),

    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),

    tertiary = Color(0xFFA3D4AE),
    onTertiary = Color(0xFF0A3921),
    tertiaryContainer = Color(0xFF245136),
    onTertiaryContainer = Color(0xFFBEF0C9),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),

    surfaceContainerLowest = Color(0xFF0D0E12),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E1F24),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33343A),
    surfaceBright = Color(0xFF38393E),
    surfaceDim = Color(0xFF121318),

    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
)

@Composable
fun CryLogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = CryLogShapes,
        content = content,
    )
}
