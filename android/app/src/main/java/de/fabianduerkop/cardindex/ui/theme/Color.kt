// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Tokens from `:root` in design/mockups/c-index.html (direction Index, ADR 0010). */
internal object IndexTokens {
    val Page = Color(0xFFE6ECF3)
    val Surface = Color(0xFFF7F9FC)
    val Raised = Color(0xFFFFFFFF)
    val Ink = Color(0xFF14233D)
    val Muted = Color(0xFF56657E)
    val Line = Color(0xFFD3DCE7)
    val Empty = Color(0xFFE3E9F1)
    val Action = Color(0xFF2346A6)
    val ActionInk = Color(0xFFFFFFFF)
    val Warn = Color(0xFF9A5A00)

    // Values the mockups use inline.
    val Indicator = Color(0xFFD9E2F3) // selected bottom-nav item
    val Hover = Color(0xFFEEF2F8)
    val DashedBorder = Color(0xFF8C9AB0)
    val WarnContainer = Color(0xFFFFF1D6)
    val Positive = Color(0xFF1F7A3A) // "+18,20 € this week"
    val Camera = Color(0xFF101722)
    val CameraInk = Color(0xFFE8EEF7)
    val CameraMuted = Color(0xFFA3B2C8)
}

internal val LightColors: ColorScheme = lightColorScheme(
    primary = IndexTokens.Action,
    onPrimary = IndexTokens.ActionInk,
    primaryContainer = IndexTokens.Indicator,
    onPrimaryContainer = IndexTokens.Ink,
    inversePrimary = Color(0xFFA9BDF5),
    secondary = IndexTokens.Muted,
    onSecondary = Color.White,
    secondaryContainer = IndexTokens.Indicator,
    onSecondaryContainer = IndexTokens.Ink,
    tertiary = IndexTokens.Warn,
    onTertiary = Color.White,
    tertiaryContainer = IndexTokens.WarnContainer,
    onTertiaryContainer = IndexTokens.Warn,
    background = IndexTokens.Surface,
    onBackground = IndexTokens.Ink,
    surface = IndexTokens.Surface,
    onSurface = IndexTokens.Ink,
    surfaceVariant = IndexTokens.Empty,
    onSurfaceVariant = IndexTokens.Muted,
    surfaceTint = IndexTokens.Action,
    inverseSurface = IndexTokens.Ink,
    inverseOnSurface = IndexTokens.Surface,
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E4),
    onErrorContainer = Color(0xFF7A1A10),
    outline = IndexTokens.DashedBorder,
    outlineVariant = IndexTokens.Line,
    scrim = Color.Black,
    surfaceBright = IndexTokens.Raised,
    surfaceDim = Color(0xFFDDE4EE),
    surfaceContainerLowest = IndexTokens.Raised,
    surfaceContainerLow = IndexTokens.Raised,
    surfaceContainer = IndexTokens.Raised, // bottom navigation bar
    surfaceContainerHigh = IndexTokens.Hover,
    surfaceContainerHighest = IndexTokens.Page,
)

/** Dark scheme derived from the camera screen (ink background, light text) with the same hue family. */
internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFA9BDF5),
    onPrimary = Color(0xFF0E2463),
    primaryContainer = Color(0xFF2A3B66),
    onPrimaryContainer = Color(0xFFDCE4F7),
    inversePrimary = IndexTokens.Action,
    secondary = IndexTokens.CameraMuted,
    onSecondary = Color(0xFF1C2638),
    secondaryContainer = Color(0xFF2A3B66),
    onSecondaryContainer = Color(0xFFDCE4F7),
    tertiary = Color(0xFFF2BE6B),
    onTertiary = Color(0xFF4A2A00),
    tertiaryContainer = Color(0xFF5C3700),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = IndexTokens.Camera,
    onBackground = IndexTokens.CameraInk,
    surface = IndexTokens.Camera,
    onSurface = IndexTokens.CameraInk,
    surfaceVariant = Color(0xFF253044),
    onSurfaceVariant = IndexTokens.CameraMuted,
    surfaceTint = Color(0xFFA9BDF5),
    inverseSurface = IndexTokens.CameraInk,
    inverseOnSurface = IndexTokens.Ink,
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD5),
    outline = Color(0xFF6B7A93),
    outlineVariant = Color(0xFF2B3446),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2B3446),
    surfaceDim = IndexTokens.Camera,
    surfaceContainerLowest = Color(0xFF0B111A),
    surfaceContainerLow = Color(0xFF141C29),
    surfaceContainer = Color(0xFF18212F),
    surfaceContainerHigh = Color(0xFF1F2938),
    surfaceContainerHighest = Color(0xFF263142),
)
