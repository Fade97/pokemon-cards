// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colour roles the Material scheme has no slot for (ADR 0014). Read them through `LumifoilTheme.colors`; screens use
 * these or `MaterialTheme.colorScheme`, never a raw colour.
 */
@Immutable
data class LumifoilColors(
    /** A price that went up. */
    val positive: Color,
    /** Fill of the Foilark mark next to the wordmark: sea-glass, as in the logo masters (design/logo). */
    val mark: Color,
    /** The keyboard and D-pad focus ring around controls. Kept apart from sea-glass so focus never looks selected. */
    val focusRing: Color,
    /** The four stops of the one foil edge (ADR 0014). Only [de.fabianduerkop.cardindex.ui.components.FoilEdge] uses them. */
    val foil: List<Color>,
    /** An owned checklist slot: solid fill and its number (council correction in the Pearl study). */
    val ownedSlot: Color,
    val onOwnedSlot: Color,
    /** A missing slot: hollow outline and its number. */
    val missingSlotOutline: Color,
    val onMissingSlot: Color,
    /** The camera area, which stays dark in both themes. */
    val camera: CameraColors,
)

/** Colours over the live camera image. The same in light and dark, since the preview is always a photo. */
@Immutable
data class CameraColors(
    val background: Color = Color(0xFF101617),
    val content: Color = Color(0xFFE8EEEF),
    val muted: Color = Color(0xFFA7B4B8),
    /** Behind hint text, so it stays readable on a bright photo. */
    val scrim: Color = Color(0x99000000),
    /** Behind small status pills. */
    val chip: Color = Color(0x1FFFFFFF),
    /** The dashed guide where the card should go. */
    val guide: Color = Color(0xCCFFFFFF),
    /** A detected outline that does not count yet. */
    val faint: Color = Color(0x66FFFFFF),
    /** Live scanning is on. */
    val live: Color = Color(0xFF62D27A),
    /** The card is read and added. */
    val done: Color = Color(0xFF3DDC84),
    /** The card is being read. */
    val reading: Color = Color(0xFF7FB2FF),
    /** A debug warning in the recognition lab. */
    val warning: Color = Color(0xFFFFB020),
)

internal val LightExtraColors = LumifoilColors(
    positive = PearlLight.Good,
    mark = PearlLight.SeaGlass,
    focusRing = Color(0xFF3D63C4),
    foil = listOf(Color(0xFF94CCC0), Color(0xFFBDD7E1), Color(0xFFC7B5D8), Color(0xFFE7C7B5)),
    ownedSlot = PearlLight.SeaGlass,
    onOwnedSlot = Color.White,
    missingSlotOutline = PearlLight.Outline,
    onMissingSlot = PearlLight.Sub,
    camera = CameraColors(),
)

/** At night the foil is dimmed, so the edge reads as material and not as a light source. */
internal val DarkExtraColors = LumifoilColors(
    positive = PearlDark.Good,
    mark = PearlDark.SeaGlass,
    focusRing = Color(0xFFA9BFF5),
    foil = listOf(Color(0xFF6FA99E), Color(0xFF8DAAB6), Color(0xFF9F8DB3), Color(0xFFBC9A88)),
    ownedSlot = PearlDark.SeaGlass,
    onOwnedSlot = PearlDark.OnSeaGlass,
    missingSlotOutline = PearlDark.Outline,
    onMissingSlot = PearlDark.Sub,
    camera = CameraColors(),
)
