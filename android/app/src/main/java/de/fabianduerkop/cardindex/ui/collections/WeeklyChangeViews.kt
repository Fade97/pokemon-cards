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
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlin.math.abs

/** "1,20 € (12 %)": the size of the change without a sign; the percentage only from a start above zero. */
private fun WeeklyChange.magnitude(): String {
    val amount = Money.format(abs(deltaMinor), currency)
    return percentChange(startMinor, endMinor)?.let { "$amount ($it)" } ?: amount
}

/** "+1,20 € (+12 %) in 7 days" or "No change in 7 days", then "3 of 4 copies" when the change doesn't cover all. */
@Composable
internal fun weeklyChangeText(change: WeeklyChange): String {
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

/**
 * "+12 % · 7 days" for a narrow price column (a copy row's 128 dp): the percentage, or the amount when there is none.
 * The full amount and coverage stay in [weeklyChangeDescription] for TalkBack.
 */
@Composable
internal fun weeklyChangeShortText(change: WeeklyChange): String {
    if (change.direction == PriceDirection.FLAT) return stringResource(R.string.weekly_change_short_flat)
    val sign = if (change.direction == PriceDirection.UP) "+" else "−"
    val size = percentChange(change.startMinor, change.endMinor) ?: Money.format(abs(change.deltaMinor), change.currency)
    return stringResource(R.string.weekly_change_short, sign + size)
}

/** What TalkBack says (#173): "Up 1,20 € (12 %) over the last 7 days, for 3 of 4 copies". */
@Composable
internal fun weeklyChangeDescription(change: WeeklyChange): String {
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
 * The weekly price change (#173) as one quiet line: an arrow, the signed amount and percentage, and "in 7 days".
 * Muted like the other secondary text, never red or green: a quiet line on every row, not a ticker. The arrow, the
 * sign and the words carry the direction, and TalkBack hears [weeklyChangeDescription] instead of "minus" and symbols.
 */
@Composable
internal fun WeeklyChangeLine(
    change: WeeklyChange,
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
        Text(
            if (short) weeklyChangeShortText(change) else weeklyChangeText(change),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
        )
    }
}
