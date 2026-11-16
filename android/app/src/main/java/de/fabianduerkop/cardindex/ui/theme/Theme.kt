// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Radii from the mockups: --r-sm 7, --r-md 12, --r-lg 22. */
private val IndexShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(7.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Colours the Material scheme has no role for. */
@Immutable
data class IndexExtraColors(val positive: Color)

private val LightExtra = IndexExtraColors(positive = IndexTokens.Positive)
private val DarkExtra = IndexExtraColors(positive = Color(0xFF7FD49A))

val LocalIndexExtraColors = staticCompositionLocalOf { LightExtra }

/** App theme. No dynamic colour: the Index palette is part of the design (ADR 0010). */
@Composable
fun CardIndexTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndexExtraColors provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = IndexTypography,
            shapes = IndexShapes,
            content = content,
        )
    }
}
