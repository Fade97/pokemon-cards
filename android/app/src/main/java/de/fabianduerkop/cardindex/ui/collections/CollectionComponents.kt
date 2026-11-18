// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.collections.PrintOrder
import de.fabianduerkop.cardindex.model.EnergyType
import de.fabianduerkop.cardindex.ui.components.ChoiceChip
import de.fabianduerkop.cardindex.ui.theme.EnergyColors
import de.fabianduerkop.cardindex.ui.theme.Spacing

/** Side padding of the collection screens: the shared Pearl gutter (ADR 0014). */
internal val ScreenPadding = Spacing.gutter

/** "All languages" plus one chip per language code; an empty selection means all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LanguageChips(
    languages: List<String>,
    selected: Set<String>,
    onToggle: (String?) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = ScreenPadding,
) {
    if (languages.size < 2) return
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChoiceChip(
            selected = selected.isEmpty(),
            onClick = { onToggle(null) },
            label = stringResource(R.string.collections_all_languages),
        )
        languages.forEach { code ->
            ChoiceChip(selected = code in selected, onClick = { onToggle(code) }, label = code.uppercase())
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
    )
}

/**
 * The small slot strip of a set row: one square per number up to the printed total (plus owned numbers beyond it),
 * filled with the card's energy colour when owned and outlined when missing. Drawn on a canvas, since a set can
 * have 250+ numbers. The row's text carries the meaning for screen readers.
 */
@Composable
internal fun SlotStrip(
    printedTotal: Int,
    owned: Map<String, EnergyType?>,
    modifier: Modifier = Modifier,
    cell: Dp = 9.dp,
    gap: Dp = 2.dp,
) {
    val extra = owned.keys.filter { key -> key.toIntOrNull()?.let { it > printedTotal } ?: true }.sortedWith(PrintOrder)
    val keys = (1..printedTotal).map(Int::toString) + extra
    if (keys.isEmpty()) return
    val unknownFill = MaterialTheme.colorScheme.onSurfaceVariant
    val emptyStroke = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics {}) {
        val columns = ((maxWidth + gap) / (cell + gap)).toInt().coerceAtLeast(1)
        val rows = (keys.size + columns - 1) / columns
        Canvas(Modifier.fillMaxWidth().height(cell * rows + gap * (rows - 1))) {
            val size = cell.toPx()
            val step = size + gap.toPx()
            val radius = CornerRadius(size * 0.18f)
            keys.forEachIndexed { index, key ->
                val topLeft = Offset((index % columns) * step, (index / columns) * step)
                if (owned.containsKey(key)) {
                    val color = owned[key]?.let(EnergyColors::fill) ?: unknownFill
                    drawRoundRect(color, topLeft, Size(size, size), radius)
                } else {
                    val inset = 0.5.dp.toPx()
                    drawRoundRect(
                        emptyStroke,
                        topLeft + Offset(inset, inset),
                        Size(size - 2 * inset, size - 2 * inset),
                        radius,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
        }
    }
}
