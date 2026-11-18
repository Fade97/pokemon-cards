// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme

/**
 * Pearl's focus ring (ADR 0014): a 2 dp line in `LumifoilTheme.colors.focusRing`, 3 dp outside the control, drawn
 * while it has keyboard or D-pad focus. Touch never focuses a button, so the ring does not show while tapping. Put
 * it before the control's own clickable in the modifier chain; the shared buttons, chips and app bar icons do.
 * A negative [gap] draws the ring inside the control, for items that touch the screen edge (navigation bar).
 */
fun Modifier.focusRing(shape: Shape, gap: Dp = 3.dp): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = LumifoilTheme.colors.focusRing
    onFocusChanged { focused = it.hasFocus }
        .drawWithContent {
            drawContent()
            if (focused) {
                val width = 2.dp.toPx()
                val outset = gap.toPx() + width / 2
                val outline = shape.createOutline(
                    Size(size.width + 2 * outset, size.height + 2 * outset),
                    layoutDirection,
                    this,
                )
                translate(-outset, -outset) { drawOutline(outline, color, style = Stroke(width)) }
            }
        }
}
