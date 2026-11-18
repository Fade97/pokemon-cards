// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import de.fabianduerkop.cardindex.ui.theme.Spacing

/**
 * The one foil edge (ADR 0014): a thin, still, horizontal band of four pale stops. It is a material cue, like the
 * edge of a holo card, and appears once per screen at most, on the featured set cover. Not for buttons, chips, bars or
 * backgrounds, and never animated. Decorative, so screen readers skip it.
 */
@Composable
fun FoilEdge(modifier: Modifier = Modifier) {
    val stops = LumifoilTheme.colors.foil
    Box(
        modifier
            .fillMaxWidth()
            .height(Spacing.foilEdge)
            .background(Brush.horizontalGradient(stops))
            .clearAndSetSemantics {},
    )
}

/**
 * The featured set cover (#97): a raised porcelain card with the foil edge along its top, clipped to the large
 * corner. Put the set title, the fanned artwork, the completion line and value inside. The whole cover is one
 * button; give it [onClickLabel] (e.g. "Open set") so TalkBack says what a tap does.
 */
@Composable
fun FoilCover(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .focusRing(shape)
            .then(if (onClickLabel != null) Modifier.semantics { onClick(label = onClickLabel) { onClick(); true } } else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            FoilEdge()
            Column(Modifier.padding(Spacing.lg), content = content)
        }
    }
}
