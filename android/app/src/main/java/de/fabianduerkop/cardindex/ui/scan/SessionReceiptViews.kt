// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.scan.ReceiptLine
import de.fabianduerkop.cardindex.data.scan.SessionReceipt
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.ui.collections.formatDate
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.LumifoilWordmark
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.share.ShareCanvas
import de.fabianduerkop.cardindex.ui.share.ShareImageButton
import de.fabianduerkop.cardindex.ui.share.ShareImagePreview
import de.fabianduerkop.cardindex.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Prints listed by name; the rest are summed up as "+ 12 more". */
private const val ReceiptLines = 6

/**
 * The session receipt to look at before sharing (#163): the image as it will be posted, scaled to the screen, and
 * Share, which renders exactly that image and opens the Android share sheet. Nothing is uploaded.
 */
@Composable
internal fun SessionReceiptSheet(receipt: SessionReceipt, today: LocalDate, onDone: () -> Unit) {
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
            stringResource(R.string.receipt_title),
            Modifier.fillMaxWidth().semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        ShareImagePreview(layer, receiptDescription(receipt)) { ReceiptImage(receipt, today) }
        Text(
            stringResource(R.string.receipt_no_art),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        ShareImageButton(
            stringResource(R.string.receipt_share),
            layer = layer,
            fileName = "foilark-session-$today",
            chooserTitle = stringResource(R.string.receipt_share_title),
            modifier = Modifier.fillMaxWidth(),
        )
        TextAction(stringResource(R.string.receipt_close), onClick = onDone)
    }
}

/**
 * The image itself: always Pearl light and at font scale 1, so every receipt looks the same whatever the phone's
 * theme and text size. No card art (ADR 0009): numbers, names and prices; a small foilark wordmark, nothing else
 * branded. The market, currency, price date and unpriced copies are always on it.
 */
@Composable
internal fun ReceiptImage(receipt: SessionReceipt, today: LocalDate) {
    ShareCanvas {
        val colors = MaterialTheme.colorScheme
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LumifoilWordmark()
                Spacer(Modifier.weight(1f))
                Text(formatDate(today), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(Spacing.md))
            Text(stringResource(R.string.receipt_heading), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Text(
                pluralStringResource(R.plurals.receipt_cards, receipt.cards, receipt.cards),
                style = MaterialTheme.typography.displaySmall,
                color = colors.onSurface,
            )
            ReceiptValue(receipt)
            Text(receiptStats(receipt), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            receipt.bestFind?.let { BestFind(it) }
            HorizontalDivider(Modifier.padding(vertical = Spacing.xs), color = colors.outlineVariant)
            receipt.lines.take(ReceiptLines).forEach { ReceiptRow(it) }
            val more = receipt.lines.size - ReceiptLines
            if (more > 0) {
                Text(
                    pluralStringResource(R.plurals.receipt_more, more, more),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReceiptValue(receipt: SessionReceipt) {
    val colors = MaterialTheme.colorScheme
    val value = receipt.value
    if (value.pricedCopies == 0) {
        Text(stringResource(R.string.receipt_no_prices), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
        return
    }
    Text(Money.format(value.amountMinor, value.currency), style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
    val details = buildList {
        add(stringResource(marketLabel(value.source)))
        receipt.priceDate?.let { add(stringResource(R.string.price_as_of, formatDate(it.localDate()))) }
        if (value.unpricedCopies > 0) {
            add(pluralStringResource(R.plurals.scan_session_unpriced, value.unpricedCopies, value.unpricedCopies, value.currency))
        }
    }
    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
}

@Composable
private fun BestFind(line: ReceiptLine) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs)
            .background(colors.surfaceContainerLow, MaterialTheme.shapes.medium)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(stringResource(R.string.receipt_best_find), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        CollectorNumber(line.card.localId, line.set.printedTotal, style = MaterialTheme.typography.headlineSmall)
        Text(line.card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(line.set.name, line.quote?.let { Money.format(it.amountMinor, it.currency) }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "025/165  Pikachu ×2   €8.40", like a till receipt. */
@Composable
private fun ReceiptRow(line: ReceiptLine) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        CollectorNumber(
            line.card.localId,
            line.set.printedTotal,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
        )
        Text(
            if (line.copies > 1) stringResource(R.string.receipt_line_copies, line.card.name, line.copies) else line.card.name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            line.quote?.let { Money.format(it.amountMinor * line.copies, it.currency) } ?: stringResource(R.string.receipt_no_price),
            style = MaterialTheme.typography.labelLarge,
            color = if (line.quote != null) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

/** "6 new set slots · 3 sets". */
@Composable
private fun receiptStats(receipt: SessionReceipt): String = listOf(
    pluralStringResource(R.plurals.receipt_new_slots, receipt.newSlots, receipt.newSlots),
    pluralStringResource(R.plurals.receipt_sets, receipt.sets, receipt.sets),
).joinToString(" · ")

/** What TalkBack reads for the image: the same facts in one sentence. */
@Composable
private fun receiptDescription(receipt: SessionReceipt): String {
    val cards = pluralStringResource(R.plurals.receipt_cards, receipt.cards, receipt.cards)
    val value = receipt.value.takeIf { it.pricedCopies > 0 }?.let { Money.format(it.amountMinor, it.currency) }
    return listOfNotNull(cards, value, receiptStats(receipt)).joinToString(", ")
}

private fun marketLabel(source: PriceSource): Int = when (source) {
    PriceSource.CARDMARKET -> R.string.price_label_cardmarket
    PriceSource.TCGPLAYER -> R.string.price_label_tcgplayer
}

private fun Instant.localDate(): LocalDate = atZone(ZoneId.systemDefault()).toLocalDate()

