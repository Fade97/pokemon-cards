// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrast of Pearl's colour roles (ADR 0014), in both schemes: text pairs reach WCAG AA (4.5:1), shapes that carry
 * meaning (missing-slot outlines, the focus ring, the selected nav icon) reach 3:1.
 */
class PearlContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(name: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        assertTrue("$name: %.2f:1 is below %.1f:1".format(r, min), r >= min)
    }

    private fun checkScheme(theme: String, s: ColorScheme, x: LumifoilColors) {
        val text = 4.5
        val shape = 3.0
        listOf(s.background, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh).forEachIndexed { i, bg ->
            check("$theme onSurface on surface #$i", s.onSurface, bg, text)
            check("$theme onSurfaceVariant on surface #$i", s.onSurfaceVariant, bg, text)
            check("$theme primary text on surface #$i", s.primary, bg, text)
            check("$theme positive on surface #$i", x.positive, bg, text)
            check("$theme focus ring on surface #$i", x.focusRing, bg, shape)
            check("$theme missing slot outline on surface #$i", x.missingSlotOutline, bg, shape)
        }
        check("$theme onPrimary", s.onPrimary, s.primary, text)
        check("$theme onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer, text)
        check("$theme selected nav icon", s.primary, s.secondaryContainer, shape)
        check("$theme selected chip label", s.primary, s.secondaryContainer, text)
        check("$theme onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer, text)
        check("$theme onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, text)
        check("$theme error on background", s.error, s.background, text)
        check("$theme onErrorContainer", s.onErrorContainer, s.errorContainer, text)
        check("$theme inverse", s.inverseOnSurface, s.inverseSurface, text)
        check("$theme owned slot number", x.onOwnedSlot, x.ownedSlot, text)
        check("$theme missing slot number", x.onMissingSlot, s.background, text)
        check("$theme camera text", x.camera.content, x.camera.background, text)
        check("$theme camera muted", x.camera.muted, x.camera.background, text)
    }

    @Test fun lightSchemeMeetsContrast() = checkScheme("light", LightColors, LightExtraColors)

    @Test fun darkSchemeMeetsContrast() = checkScheme("dark", DarkColors, DarkExtraColors)

    /** The night scheme is its own design, not the daylight one inverted: the surfaces keep Pearl's sea-glass tint. */
    @Test fun darkSurfacesAreTintedNotNeutral() {
        val bg = DarkColors.background
        assertTrue("dark background should lean blue-green", bg.green > bg.red && bg.blue > bg.red)
        assertTrue("dark background is not black", bg.luminance() > 0.003f)
    }
}
