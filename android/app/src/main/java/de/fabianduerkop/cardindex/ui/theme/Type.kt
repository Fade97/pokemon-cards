// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.fabianduerkop.cardindex.R

/** Pearl's heading weight: between semibold and bold, as in the study (`font-weight: 650`). */
val HeadingWeight = FontWeight(650)

/** Schibsted Grotesk, one variable font file (wght 400–900); each weight is a variation of it. */
@OptIn(ExperimentalTextApi::class)
val SchibstedGrotesk = FontFamily(
    listOf(
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        HeadingWeight,
        FontWeight.Bold,
        FontWeight.ExtraBold,
    ).map {
        Font(R.font.schibsted_grotesk, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)))
    },
)

/** OpenType feature for tabular (fixed-width) figures, so collector numbers and prices line up. */
const val TabularFigures = "tnum"

private val base = TextStyle(fontFamily = SchibstedGrotesk)

/**
 * Pearl type scale (ADR 0014). All sizes are `sp`, so they follow the system font size. The study's 360 px phone and
 * its 12 px captions are reference geometry: production text is never smaller than 12 sp, and body text is 15 sp.
 *
 * The display styles carry the numbers. displayLarge is the collector number on the scan and detail screens, the
 * strongest type in the app (ADR 0010 keeps this from Index); displayMedium is a collection value or a number over
 * the camera. Headings use [HeadingWeight] with tight tracking; body and labels stay at normal tracking.
 */
val PearlTypography = Typography(
    displayLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-0.04).em),
    displayMedium = base.copy(fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-0.035).em),
    displaySmall = base.copy(fontWeight = HeadingWeight, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-0.04).em),
    headlineLarge = base.copy(fontWeight = HeadingWeight, fontSize = 30.sp, lineHeight = 35.sp, letterSpacing = (-0.035).em),
    headlineMedium = base.copy(fontWeight = HeadingWeight, fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.03).em),
    headlineSmall = base.copy(fontWeight = HeadingWeight, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.02).em),
    titleLarge = base.copy(fontWeight = HeadingWeight, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.02).em),
    titleMedium = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.01).em),
    titleSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = base.copy(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.copy(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = base.copy(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = base.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = base.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

/** The wordmark: lower-case "lumifoil" in the heading weight, tightly tracked, as in the study. */
val WordmarkStyle = base.copy(fontWeight = HeadingWeight, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.04).em)
