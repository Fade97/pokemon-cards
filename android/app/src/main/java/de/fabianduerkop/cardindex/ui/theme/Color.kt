// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Pearl daylight tokens (ADR 0014), from `.pearl` in design/mockups/lumifoil/index.html. Two values differ from the
 * study on purpose: secondary text is a little darker (#5E6A72 instead of #657078) so it reaches 4.5:1 on raised
 * surfaces, and the outline for missing slots is dark enough (3:1) to read as a shape.
 */
internal object PearlLight {
    val Paper = Color(0xFFFAFBFC) // --paper: page and bars
    val Lowest = Color(0xFFFFFFFF)
    val Low = Color(0xFFF4F6F8)
    val Raised = Color(0xFFEEF2F4) // --raised: cover, rows that stand out
    val High = Color(0xFFE7ECEF)
    val Highest = Color(0xFFE0E7E9) // the soft glow behind the card fan
    val Ink = Color(0xFF26353C) // --ink
    val Sub = Color(0xFF5E6A72) // --sub, darkened
    val Outline = Color(0xFF78858B)
    val Line = Color(0xFFD9E1E5) // --line: dividers
    val SeaGlass = Color(0xFF356C6C) // --accent: actions, owned slots
    val Selection = Color(0xFFDFEDEB) // --selection: nav indicator, selected chips
    val OnSelection = Color(0xFF173F3E)
    val Mark = Color(0xFF4D7779) // secondary role (the mark itself is sea-glass, see LightExtraColors)
    val Amber = Color(0xFF8A5A12) // needs attention, e.g. scans to review
    val AmberContainer = Color(0xFFF8EBD5)
    val OnAmberContainer = Color(0xFF5A3A06)
    val Good = Color(0xFF39705B) // --good: price went up
}

/**
 * Pearl at night: the same porcelain idea under low light, not an inverted daylight scheme. Surfaces are a deep
 * blue-green slate that steps up in small amounts, the sea-glass turns lighter and calmer, and card art stays the
 * brightest thing on screen.
 */
internal object PearlDark {
    val Paper = Color(0xFF111718)
    val Lowest = Color(0xFF0C1112)
    val Low = Color(0xFF161D1E)
    val Raised = Color(0xFF1B2324)
    val High = Color(0xFF222B2C)
    val Highest = Color(0xFF2A3435)
    val Bright = Color(0xFF313B3C)
    val Ink = Color(0xFFE2E9EB)
    val Sub = Color(0xFFA3B0B5)
    val Outline = Color(0xFF738287)
    val Line = Color(0xFF2F3A3C)
    val SeaGlass = Color(0xFF8CCBC2)
    val OnSeaGlass = Color(0xFF0B3533)
    val Selection = Color(0xFF1F4644)
    val OnSelection = Color(0xFFBFE6E0)
    val Mark = Color(0xFF9DC1C2)
    val Amber = Color(0xFFE9BE78)
    val AmberContainer = Color(0xFF4F3A12)
    val OnAmberContainer = Color(0xFFFADDAD)
    val Good = Color(0xFF86C9A6)
}

internal val LightColors: ColorScheme = lightColorScheme(
    primary = PearlLight.SeaGlass,
    onPrimary = Color.White,
    primaryContainer = PearlLight.Selection,
    onPrimaryContainer = PearlLight.OnSelection,
    inversePrimary = PearlDark.SeaGlass,
    secondary = PearlLight.Mark,
    onSecondary = Color.White,
    secondaryContainer = PearlLight.Selection, // navigation indicator
    onSecondaryContainer = PearlLight.OnSelection,
    tertiary = PearlLight.Amber,
    onTertiary = Color.White,
    tertiaryContainer = PearlLight.AmberContainer,
    onTertiaryContainer = PearlLight.OnAmberContainer,
    background = PearlLight.Paper,
    onBackground = PearlLight.Ink,
    surface = PearlLight.Paper,
    onSurface = PearlLight.Ink,
    surfaceVariant = PearlLight.High,
    onSurfaceVariant = PearlLight.Sub,
    surfaceTint = PearlLight.SeaGlass,
    inverseSurface = PearlLight.Ink,
    inverseOnSurface = PearlLight.Raised,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF601410),
    outline = PearlLight.Outline,
    outlineVariant = PearlLight.Line,
    scrim = Color.Black,
    surfaceBright = PearlLight.Lowest,
    surfaceDim = PearlLight.Highest,
    surfaceContainerLowest = PearlLight.Lowest,
    surfaceContainerLow = PearlLight.Low,
    surfaceContainer = PearlLight.Raised,
    surfaceContainerHigh = PearlLight.High,
    surfaceContainerHighest = PearlLight.Highest,
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = PearlDark.SeaGlass,
    onPrimary = PearlDark.OnSeaGlass,
    primaryContainer = PearlDark.Selection,
    onPrimaryContainer = PearlDark.OnSelection,
    inversePrimary = PearlLight.SeaGlass,
    secondary = PearlDark.Mark,
    onSecondary = Color(0xFF143435),
    secondaryContainer = PearlDark.Selection,
    onSecondaryContainer = PearlDark.OnSelection,
    tertiary = PearlDark.Amber,
    onTertiary = Color(0xFF422B00),
    tertiaryContainer = PearlDark.AmberContainer,
    onTertiaryContainer = PearlDark.OnAmberContainer,
    background = PearlDark.Paper,
    onBackground = PearlDark.Ink,
    surface = PearlDark.Paper,
    onSurface = PearlDark.Ink,
    surfaceVariant = PearlDark.High,
    onSurfaceVariant = PearlDark.Sub,
    surfaceTint = PearlDark.SeaGlass,
    inverseSurface = PearlDark.Ink,
    inverseOnSurface = PearlLight.Ink,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF5A2220), // muted, so an error box does not glare at night
    onErrorContainer = Color(0xFFF9DEDC),
    outline = PearlDark.Outline,
    outlineVariant = PearlDark.Line,
    scrim = Color.Black,
    surfaceBright = PearlDark.Bright,
    surfaceDim = PearlDark.Paper,
    surfaceContainerLowest = PearlDark.Lowest,
    surfaceContainerLow = PearlDark.Low,
    surfaceContainer = PearlDark.Raised,
    surfaceContainerHigh = PearlDark.High,
    surfaceContainerHighest = PearlDark.Highest,
)
