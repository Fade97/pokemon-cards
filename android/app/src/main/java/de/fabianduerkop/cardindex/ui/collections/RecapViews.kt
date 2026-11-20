// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.collections.Recap
import de.fabianduerkop.cardindex.data.collections.RecapPeriod
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.LumifoilWordmark
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.share.ShareCanvas
import de.fabianduerkop.cardindex.ui.share.ShareImageButton
import de.fabianduerkop.cardindex.ui.share.ShareImagePreview
import de.fabianduerkop.cardindex.ui.theme.Spacing
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Completed sets named on the recap image; more become "+ 2 more". */
private const val RecapSets = 4

/** "November 2026" or "2026". */
@Composable
private fun periodLabel(period: RecapPeriod): String = when (period.kind) {
    RecapPeriod.Kind.MONTH -> YearMonth.from(period.start).format(DateTimeFormatter.ofPattern("LLLL yyyy"))
    RecapPeriod.Kind.YEAR -> period.start.year.toString()
}

/**
 * A ready recap on the Collections home (#175): "Your November in cards", with a dot until it was opened. It simply
 * waits here; no notification asks for it.
 */
@Composable
internal fun RecapRow(entry: RecapEntry, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val title = stringResource(
        if (entry.period.kind == RecapPeriod.Kind.MONTH) R.string.recap_row_month else R.string.recap_row_year,
        periodLabel(entry.period),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = Spacing.xs)
            .focusRing(shape)
            .clip(shape)
            .background(colors.secondaryContainer)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.recap_open), onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (entry.new) Box(Modifier.size(8.dp).background(colors.primary, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(stringResource(R.string.recap_row_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** The recap to look at before sharing: the 9:16 image as it will be posted, Share, Close. */
@Composable
internal fun RecapSheet(recap: Recap, onDone: () -> Unit) {
    val layer = rememberGraphicsLayer()
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.recap_title, periodLabel(recap.period)),
            Modifier.fillMaxWidth().semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        ShareImagePreview(layer, recapDescription(recap)) { RecapImage(recap) }
        Text(
            stringResource(R.string.set_share_no_art),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        ShareImageButton(
            stringResource(R.string.set_share_image),
            layer = layer,
            fileName = "foilark-recap-" + recap.period.key,
            chooserTitle = stringResource(R.string.recap_share_title),
            modifier = Modifier.fillMaxWidth(),
        )
        TextAction(stringResource(R.string.receipt_close), onClick = onDone)
    }
}

/**
 * The recap image (#175): the period, cards added and what they are worth now, the top find, completed sets and,
 * with Pro, how the collection's value moved and its best riser. Numbers and names only (ADR 0009).
 */
@Composable
internal fun RecapImage(recap: Recap) {
    ShareCanvas {
        val colors = MaterialTheme.colorScheme
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LumifoilWordmark()
                Spacer(Modifier.weight(1f))
                Text(periodLabel(recap.period), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                stringResource(if (recap.period.kind == RecapPeriod.Kind.MONTH) R.string.recap_overline_month else R.string.recap_overline_year),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Text(
                pluralStringResource(R.plurals.recap_cards_added, recap.cardsAdded, recap.cardsAdded),
                style = MaterialTheme.typography.displaySmall,
                color = colors.onSurface,
            )
            val added = recap.valueAdded
            if (added.pricedCopies > 0) {
                Text(
                    stringResource(R.string.recap_worth_now, Money.format(added.amountMinor, added.currency)),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
            }
            recap.valueChange?.takeIf { it.startMinor > 0 }?.let { change ->
                val sign = if (change.deltaMinor >= 0) "+" else "−"
                val percent = percentChange(change.startMinor, change.endMinor)?.let { " ($sign$it)" }.orEmpty()
                Text(
                    stringResource(R.string.recap_value_change, sign + Money.format(abs(change.deltaMinor), change.currency) + percent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = Spacing.xs), color = colors.outlineVariant)
            recap.topFind?.let { find ->
                RecapBlock(stringResource(R.string.recap_top_find)) {
                    CollectorNumber(find.card.localId, find.set.printedTotal, style = MaterialTheme.typography.headlineSmall)
                    Text(find.card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${find.set.name} · ${Money.format(find.quote.amountMinor, find.quote.currency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            recap.bestRiser?.let { riser ->
                RecapBlock(stringResource(R.string.recap_best_riser)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        CollectorNumber(riser.card.localId, riser.set.printedTotal, style = MaterialTheme.typography.titleMedium)
                        Text(riser.card.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("+" + Money.format(riser.deltaMinor, riser.currency), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    }
                }
            }
            if (recap.setsCompleted.isNotEmpty()) {
                RecapBlock(pluralStringResource(R.plurals.recap_sets_completed, recap.setsCompleted.size, recap.setsCompleted.size)) {
                    recap.setsCompleted.take(RecapSets).forEach { set ->
                        Text("${set.name} (${set.language.uppercase()})", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val more = recap.setsCompleted.size - RecapSets
                    if (more > 0) {
                        Text(pluralStringResource(R.plurals.receipt_more, more, more), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecapBlock(label: String, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow, MaterialTheme.shapes.medium)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        content()
    }
}

/** What TalkBack reads for the image: the main facts in one sentence. */
@Composable
private fun recapDescription(recap: Recap): String = listOfNotNull(
    periodLabel(recap.period),
    pluralStringResource(R.plurals.recap_cards_added, recap.cardsAdded, recap.cardsAdded),
    recap.valueAdded.takeIf { it.pricedCopies > 0 }?.let { stringResource(R.string.recap_worth_now, Money.format(it.amountMinor, it.currency)) },
    recap.setsCompleted.takeIf { it.isNotEmpty() }?.let { pluralStringResource(R.plurals.recap_sets_completed, it.size, it.size) },
).joinToString(", ")
