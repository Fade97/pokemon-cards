// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalLumifoilColors = staticCompositionLocalOf { LightExtraColors }

/**
 * App theme, direction Pearl (ADR 0014): light porcelain by day, its companion night scheme when the system is
 * dark. No dynamic colour: the palette is part of the design.
 */
@Composable
fun LumifoilTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLumifoilColors provides if (darkTheme) DarkExtraColors else LightExtraColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PearlTypography,
            shapes = PearlShapes,
            content = content,
        )
    }
}

/** Pearl's roles beyond Material: `LumifoilTheme.colors.positive`, `LumifoilTheme.colors.camera`, … */
object LumifoilTheme {
    val colors: LumifoilColors
        @Composable @ReadOnlyComposable
        get() = LocalLumifoilColors.current
}
