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
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlin.math.abs

/** "1,20 € (12 %)": the size of the change without a sign; the percentage only from a start above zero. */
private fun WeeklyChange.magnitude(): String {
    val amount = Money.format(abs(deltaMinor), currency)
    return percentChange(startMinor, endMinor)?.let { "$amount ($it)" } ?: amount
}

/**
 * Pro: "+1,20 € (+12 %) in 7 days" or "No change in 7 days", then "3 of 4 copies" when the change doesn't cover all.
 * Free (#186): only "Up in 7 days", "Down in 7 days" or "No change in 7 days", never an amount or a percentage.
 */
@Composable
internal fun weeklyChangeText(trend: WeeklyTrend): String = when (trend) {
    is WeeklyChange -> amountText(trend)
    is WeeklyDirection -> stringResource(
        when (trend.direction) {
            PriceDirection.UP -> R.string.weekly_direction_up
            PriceDirection.DOWN -> R.string.weekly_direction_down
            PriceDirection.FLAT -> R.string.weekly_change_flat
        },
    )
}

/** What TalkBack says: Pro the amounts ([amountDescription]), free only the direction (#186). */
@Composable
internal fun weeklyChangeDescription(trend: WeeklyTrend): String = when (trend) {
    is WeeklyChange -> amountDescription(trend)
    is WeeklyDirection -> stringResource(
        when (trend.direction) {
            PriceDirection.UP -> R.string.weekly_direction_up_description
            PriceDirection.DOWN -> R.string.weekly_direction_down_description
            PriceDirection.FLAT -> R.string.weekly_change_flat_description
        },
    )
}

@Composable
private fun amountText(change: WeeklyChange): String {
    val sign = if (change.direction == PriceDirection.UP) "+" else "−"
    val main = when (change.direction) {
        PriceDirection.FLAT -> stringResource(R.string.weekly_change_flat)
        else -> {
            val amount = sign + Money.format(abs(change.deltaMinor), change.currency)
            val percent = percentChange(change.startMinor, change.endMinor)?.let { " ($sign$it)" }.orEmpty()
            stringResource(R.string.weekly_change, amount + percent)
        }
    }
    if (!change.partial) return main
    val covered = pluralStringResource(
        R.plurals.weekly_change_covered,
        change.pricedCopies,
        change.coveredCopies,
        change.pricedCopies,
    )
    return "$main · $covered"
}

/** What TalkBack says (#173): "Up 1,20 € (12 %) over the last 7 days, for 3 of 4 copies". */
@Composable
private fun amountDescription(change: WeeklyChange): String {
    val main = when (change.direction) {
        PriceDirection.UP -> stringResource(R.string.weekly_change_up_description, change.magnitude())
        PriceDirection.DOWN -> stringResource(R.string.weekly_change_down_description, change.magnitude())
        PriceDirection.FLAT -> stringResource(R.string.weekly_change_flat_description)
    }
    if (!change.partial) return main
    val covered = pluralStringResource(
        R.plurals.weekly_change_covered_description,
        change.pricedCopies,
        change.coveredCopies,
        change.pricedCopies,
    )
    return "$main, $covered"
}

/**
 * The weekly price change (#173) as one quiet line: an arrow, the signed amount and percentage, and "in 7 days"; for
 * free users (#186) the arrow and the direction in words only. Muted like the other secondary text, never red or
 * green: a quiet line on every row, not a ticker. The arrow, the sign and the words carry the direction, and TalkBack
 * hears [weeklyChangeDescription] instead of "minus" and symbols.
 */
@Composable
internal fun WeeklyChangeLine(change: WeeklyTrend, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
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
        Text(
            weeklyChangeText(change),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
        )
    }
}
