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

/** Schibsted Grotesk, one variable font file (wght 400–900); each weight is a variation of it. */
@OptIn(ExperimentalTextApi::class)
val SchibstedGrotesk = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold).map {
        Font(R.font.schibsted_grotesk, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)))
    },
)

/** OpenType feature for tabular (fixed-width) figures, so collector numbers line up. */
const val TabularFigures = "tnum"

private val base = TextStyle(fontFamily = SchibstedGrotesk)

/**
 * Type scale from the Index mockups. The display styles carry the big numbers: displayLarge is the collector number
 * on the scan and detail screens (72 px, weight 800, tight tracking), displayMedium the collection value.
 */
val IndexTypography = Typography(
    displayLarge = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 72.sp, lineHeight = 66.sp, letterSpacing = (-0.045).em),
    displayMedium = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-0.03).em),
    displaySmall = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 38.sp, letterSpacing = (-0.045).em),
    headlineLarge = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.03).em),
    headlineMedium = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 33.sp, letterSpacing = (-0.025).em),
    headlineSmall = base.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em),
    titleLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em),
    titleMedium = base.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = base.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = base.copy(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = base.copy(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = base.copy(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp),
)
