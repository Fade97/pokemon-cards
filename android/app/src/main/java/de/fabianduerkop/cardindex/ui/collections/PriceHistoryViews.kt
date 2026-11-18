// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.prices.HistoryRange
import de.fabianduerkop.cardindex.data.prices.PricePoint
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.ui.components.ChoiceChip
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import de.fabianduerkop.cardindex.ui.theme.Spacing
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat

enum class HistoryStatus {
    /** Fetching the range for the first time. */
    LOADING,
    READY,

    /** The backend could not be reached; cached history (if any) is shown. */
    FAILED,

    /** The backend refused the Pro token (or there is none). */
    REFUSED,
}

/** A price chart: the points of one range in one currency. Null in a screen's state means no Pro, no chart. */
data class HistoryUiState(
    val range: HistoryRange,
    val points: List<PricePoint>,
    val currency: String,
    val status: HistoryStatus,
)

@StringRes
private fun HistoryRange.label(): Int = when (this) {
    HistoryRange.MONTH -> R.string.history_range_month
    HistoryRange.QUARTER -> R.string.history_range_quarter
    HistoryRange.YEAR -> R.string.history_range_year
}

/**
 * Range chips, the change over the range and the chart; a line of text instead while there are fewer than two days.
 * Shared by the card detail (one printing's price) and the collections screen (the portfolio).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PriceHistoryBlock(state: HistoryUiState, onRange: (HistoryRange) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            HistoryRange.entries.forEach { range ->
                ChoiceChip(
                    selected = range == state.range,
                    onClick = { onRange(range) },
                    label = stringResource(range.label()),
                )
            }
        }
        val points = state.points
        if (points.size >= 2) {
            ChangeLine(points.first(), points.last(), state.currency)
            PriceChart(points, state.currency, Modifier.padding(top = Spacing.sm))
            if (state.status == HistoryStatus.FAILED) HistoryNote(stringResource(R.string.history_offline))
        } else {
            HistoryNote(
                stringResource(
                    when (state.status) {
                        HistoryStatus.LOADING -> R.string.history_loading
                        HistoryStatus.FAILED -> R.string.history_failed
                        HistoryStatus.REFUSED -> R.string.history_refused
                        HistoryStatus.READY -> R.string.history_empty
                    },
                ),
            )
        }
    }
}

@Composable
private fun HistoryNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm),
    )
}

/** "+1,20 € (+12 %) since 18 Aug 2026", green up, red down, as the mockup's "+18,20 € this week". */
@Composable
private fun ChangeLine(first: PricePoint, last: PricePoint, currency: String) {
    val delta = last.amountMinor - first.amountMinor
    val sign = if (delta > 0) "+" else if (delta < 0) "−" else "±"
    val amount = sign + Money.format(kotlin.math.abs(delta), currency)
    val percent = percentChange(first.amountMinor, last.amountMinor)?.let { " ($sign$it)" }.orEmpty()
    Text(
        stringResource(R.string.history_change, amount + percent, formatDate(first.date)),
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = when {
            delta > 0 -> LumifoilTheme.colors.positive
            delta < 0 -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(top = Spacing.md),
    )
}

/** The relative change without sign, e.g. `12 %` in the user's locale; null from a start of zero. */
internal fun percentChange(from: Long, to: Long): String? {
    if (from <= 0) return null
    val ratio = BigDecimal.valueOf(kotlin.math.abs(to - from)).divide(BigDecimal.valueOf(from), 3, RoundingMode.HALF_UP)
    val format = NumberFormat.getPercentInstance().apply { maximumFractionDigits = if (ratio < BigDecimal("0.1")) 1 else 0 }
    return format.format(ratio)
}

/**
 * A line over the range with the highest and lowest amount on the right and the first and last date under it.
 * Days are placed by date, so a gap in the data shows as a longer segment. Drawn in sea-glass, the action colour
 * (ADR 0014); up and down in the change line above also say "+" or "−", so colour is never the only signal.
 */
@Composable
internal fun PriceChart(points: List<PricePoint>, currency: String, modifier: Modifier = Modifier) {
    val min = points.minOf { it.amountMinor }
    val max = points.maxOf { it.amountMinor }
    val line = MaterialTheme.colorScheme.primary
    val rule = MaterialTheme.colorScheme.outlineVariant
    // Proportional figures: Schibsted's tabular set also widens `.` and `,`, which breaks up prices (ADR 0014).
    val labelStyle = MaterialTheme.typography.bodySmall
    val description = stringResource(
        R.string.history_chart_description,
        formatDate(points.first().date),
        formatDate(points.last().date),
        Money.format(points.first().amountMinor, currency),
        Money.format(points.last().amountMinor, currency),
        Money.format(min, currency),
        Money.format(max, currency),
    )
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }) {
        Row {
            Canvas(Modifier.weight(1f).height(128.dp)) {
                val inset = 4.dp.toPx()
                val top = inset
                val bottom = size.height - inset
                val firstDay = points.first().date.toEpochDay()
                val span = (points.last().date.toEpochDay() - firstDay).coerceAtLeast(1)
                fun x(p: PricePoint) = (p.date.toEpochDay() - firstDay).toFloat() / span * size.width
                fun y(p: PricePoint) =
                    if (max == min) (top + bottom) / 2 else bottom - (p.amountMinor - min).toFloat() / (max - min) * (bottom - top)

                drawLine(rule, Offset(0f, top), Offset(size.width, top), strokeWidth = 1.dp.toPx())
                drawLine(rule, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
                val path = Path().apply {
                    points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p), y(p)) else lineTo(x(p), y(p)) }
                }
                val area = Path().apply {
                    addPath(path)
                    lineTo(x(points.last()), bottom)
                    lineTo(x(points.first()), bottom)
                    close()
                }
                drawPath(area, line.copy(alpha = 0.08f))
                drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Column(
                Modifier.height(128.dp).padding(start = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(Money.format(max, currency), style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.format(min, currency), style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(formatDate(points.first().date), style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatDate(points.last().date), style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
