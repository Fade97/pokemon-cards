// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.prices.PriceDirection
import de.fabianduerkop.cardindex.data.prices.WeeklyChange
import de.fabianduerkop.cardindex.data.prices.WeeklyDirection
import de.fabianduerkop.cardindex.data.prices.WeeklyTrend
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.ui.components.JoinedText
import de.fabianduerkop.cardindex.ui.components.PartSeparator
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlin.math.abs

/** "1,20 € (12 %)": the size of the change without a sign; the percentage only from a start above zero. */
private fun WeeklyChange.magnitude(): String {
    val amount = Money.format(abs(deltaMinor), currency)
    return percentChange(startMinor, endMinor)?.let { "$amount ($it)" } ?: amount
}

/**
 * Pro: "+1,20 € (+12 %) over the last 7 days" or "No change over the last 7 days"; free (#186): only "Up over the
 * last 7 days", "Down …" or "No change …", never an amount or a percentage. Either way followed by "8 of 10 priced
 * copies" when the change doesn't cover every priced copy (#250).
 */
@Composable
internal fun weeklyChangeText(trend: WeeklyTrend): String = weeklyChangeParts(trend).joinToString(PartSeparator)

/** The facts of [weeklyChangeText], for a line that breaks only between them ([JoinedText]). */
@Composable
internal fun weeklyChangeParts(trend: WeeklyTrend): List<String> {
    val main = when (trend) {
        is WeeklyChange -> amountText(trend)
        is WeeklyDirection -> stringResource(
            when (trend.direction) {
                PriceDirection.UP -> R.string.weekly_direction_up
                PriceDirection.DOWN -> R.string.weekly_direction_down
                PriceDirection.FLAT -> R.string.weekly_change_flat
            },
        )
    }
    if (!trend.partial) return listOf(main)
    return listOf(
        main,
        pluralStringResource(R.plurals.weekly_change_covered, trend.pricedCopies, trend.coveredCopies, trend.pricedCopies),
    )
}

/** What TalkBack says: Pro the amounts, free only the direction (#186); both with their coverage when partial. */
@Composable
internal fun weeklyChangeDescription(trend: WeeklyTrend): String {
    val main = when (trend) {
        is WeeklyChange -> when (trend.direction) {
            PriceDirection.UP -> stringResource(R.string.weekly_change_up_description, trend.magnitude())
            PriceDirection.DOWN -> stringResource(R.string.weekly_change_down_description, trend.magnitude())
            PriceDirection.FLAT -> stringResource(R.string.weekly_change_flat_description)
        }
        is WeeklyDirection -> stringResource(
            when (trend.direction) {
                PriceDirection.UP -> R.string.weekly_direction_up_description
                PriceDirection.DOWN -> R.string.weekly_direction_down_description
                PriceDirection.FLAT -> R.string.weekly_change_flat_description
            },
        )
    }
    if (!trend.partial) return main
    val covered = pluralStringResource(
        R.plurals.weekly_change_covered_description,
        trend.pricedCopies,
        trend.coveredCopies,
        trend.pricedCopies,
    )
    return "$main, $covered"
}

@Composable
private fun amountText(change: WeeklyChange): String {
    val sign = if (change.direction == PriceDirection.UP) "+" else "−"
    return when (change.direction) {
        PriceDirection.FLAT -> stringResource(R.string.weekly_change_flat)
        else -> {
            val amount = sign + Money.format(abs(change.deltaMinor), change.currency)
            val percent = percentChange(change.startMinor, change.endMinor)?.let { " ($sign$it)" }.orEmpty()
            stringResource(R.string.weekly_change, amount + percent)
        }
    }
}

/**
 * "+12 % · 7 days" for a narrow price column (a copy row's 128 dp): the percentage, or the amount when there is none.
 * The full amount and coverage stay in [weeklyChangeDescription] for TalkBack. Free (#186) keeps "Up in 7 days",
 * which already fits.
 */
@Composable
internal fun weeklyChangeShortText(trend: WeeklyTrend): String {
    val change = trend as? WeeklyChange ?: return weeklyChangeText(trend)
    if (change.direction == PriceDirection.FLAT) return stringResource(R.string.weekly_change_short_flat)
    val sign = if (change.direction == PriceDirection.UP) "+" else "−"
    val size = percentChange(change.startMinor, change.endMinor) ?: Money.format(abs(change.deltaMinor), change.currency)
    return stringResource(R.string.weekly_change_short, sign + size)
}

/**
 * The weekly price change (#173) as one quiet line: an arrow, the signed amount and percentage, and "in 7 days"; for
 * free users (#186) the arrow and the direction in words only. Muted like the other secondary text, never red or
 * green: a quiet line on every row, not a ticker. The arrow, the sign and the words carry the direction, and TalkBack
 * hears [weeklyChangeDescription] instead of "minus" and symbols.
 */
@Composable
internal fun WeeklyChangeLine(
    change: WeeklyTrend,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    short: Boolean = false,
) {
    val description = weeklyChangeDescription(change)
    val icon = when (change.direction) {
        PriceDirection.UP -> R.drawable.ic_trend_up
        PriceDirection.DOWN -> R.drawable.ic_trend_down
        PriceDirection.FLAT -> R.drawable.ic_trend_flat
    }
    // The arrow grows with the text, so it stays readable at large font sizes.
    val iconSize = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.fontSize.toDp() } + 2.dp
    Row(modifier.clearAndSetSemantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
        Spacer(Modifier.width(Spacing.xxs))
        JoinedText(
            if (short) listOf(weeklyChangeShortText(change)) else weeklyChangeParts(change),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
        )
    }
}
