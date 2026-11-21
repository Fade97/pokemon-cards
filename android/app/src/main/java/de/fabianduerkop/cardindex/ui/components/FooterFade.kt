// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/** Height of [FooterFade]; text that must stay readable above the footer keeps at least this much room under it. */
val FooterFadeHeight = 32.dp

/**
 * A short fade from the page into a sticky footer (Review's Confirm bar, card detail's Add bar), so a line the footer
 * cuts reads as "more below" rather than as a rendering glitch (#218). Place it at the bottom of the scrolling area,
 * right above the footer; it takes no touches.
 */
@Composable
fun FooterFade(modifier: Modifier = Modifier) {
    val page = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(FooterFadeHeight)
            // Eased, solid for its last few dp, so no half-cut glyph shows right on the footer's edge.
            .background(
                Brush.verticalGradient(
                    0f to page.copy(alpha = 0f),
                    0.6f to page.copy(alpha = 0.8f),
                    0.85f to page,
                    1f to page,
                ),
            ),
    )
}
