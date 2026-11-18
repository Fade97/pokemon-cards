// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Pearl corner radii (ADR 0014), from the study: slots 5, chips and card images 7, buttons and fields 10, the
 * featured set cover and cards 14, sheets 22.
 */
internal val PearlShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(7.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

/**
 * Spacing steps (ADR 0014). Use these instead of loose numbers, so screens line up. [gutter] is the side padding of
 * every screen; [touchTarget] is the smallest height or width of anything tappable.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Side padding of a screen (22 px in the study's 360 px phone). */
    val gutter = 20.dp

    /** Material's minimum size for a control. */
    val touchTarget = 48.dp

    /** Thickness of the foil edge on the featured set cover. */
    val foilEdge = 4.dp
}
