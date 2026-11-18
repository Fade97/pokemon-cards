// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.prices.MarketLinks
import de.fabianduerkop.cardindex.data.prices.PriceQuote
import de.fabianduerkop.cardindex.data.prices.PriceSelection
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.ui.components.DataRow
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.theme.Spacing
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@StringRes
internal fun PriceSource.label(): Int =
    if (this == PriceSource.CARDMARKET) R.string.price_label_cardmarket else R.string.price_label_tcgplayer

/** Just the market's name, for a choice between the two. */
@StringRes
internal fun PriceSource.shortLabel(): Int =
    if (this == PriceSource.CARDMARKET) R.string.settings_price_cardmarket else R.string.settings_price_tcgplayer

@StringRes
internal fun PriceVariant.label(): Int = when (this) {
    PriceVariant.NORMAL -> R.string.finish_normal
    PriceVariant.HOLO -> R.string.finish_holo
    PriceVariant.REVERSE_HOLO -> R.string.finish_reverse_holo
    PriceVariant.FIRST_EDITION -> R.string.edition_first
    PriceVariant.FIRST_EDITION_HOLO -> R.string.edition_first
}

private fun Instant.toLocalDate() = atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * Price of a copy at the end of a list row, with a second line under it (the card language). With [totalCurrency], a
 * price in another currency says that it is not in the list's total: TCGplayer has no price for a German copy, so a
 * dollar total leaves out its euro price instead of converting it.
 */
@Composable
internal fun QuoteColumn(quote: PriceQuote?, secondLine: String, totalCurrency: String? = null) {
    val outside = quote != null && totalCurrency != null && quote.currency != totalCurrency
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = Spacing.sm).widthIn(max = 128.dp)) {
        Text(
            quote?.let { Money.format(it.amountMinor, it.currency) } ?: "–",
            style = MaterialTheme.typography.titleSmall,
            color = if (outside) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            secondLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        if (outside) {
            Text(
                stringResource(R.string.collections_quote_not_in_total, totalCurrency),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** A cached price older than this is called out as not updated since (the backend imports once a night). */
private val STALE_AFTER: Duration = Duration.ofDays(3)

/**
 * The price block of the card detail screen (Pearl study "Card details"): the main market's price big, the other
 * market's on a data row below it (DESIGN.md), each labelled with what it is. Then the printing it prices, the market
 * date, a note when the two are in different currencies (never converted) or the saved price is old, and links to
 * the offers. Cardmarket's link filters the offers by the card's language because the free price mixes languages.
 * Without any price it says so; it never shows a zero.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PriceBox(
    prices: List<CachedPriceEntity>,
    primary: PriceSource,
    finish: Finish,
    edition: Edition,
    cardLanguage: String,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    val (main, secondary) = PriceSelection.pair(prices, primary, finish, edition, cardLanguage)
    val uriHandler = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        val first = main ?: secondary
        if (first == null) {
            Text(stringResource(R.string.price_none), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.price_none_hint),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        } else {
            val second = secondary.takeIf { main != null }
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(stringResource(first.source.label()), style = MaterialTheme.typography.bodySmall, color = muted)
                Text(
                    Money.format(first.amountMinor, first.currency),
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
            HorizontalDivider(Modifier.padding(top = Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
            second?.let { DataRow(stringResource(it.source.label()), Money.format(it.amountMinor, it.currency)) }
            val date = listOfNotNull(main?.priceDate, secondary?.priceDate).maxOrNull()
            val notes = buildList {
                add(
                    listOfNotNull(
                        stringResource(R.string.price_for_print, stringResource(first.variant.label())),
                        date?.let { stringResource(R.string.price_as_of, formatDate(it.toLocalDate())) },
                    ).joinToString(" · "),
                )
                if (second != null && second.currency != first.currency) add(stringResource(R.string.price_currencies))
                val fetched = listOfNotNull(main?.fetchedAt, secondary?.fetchedAt).minOrNull()
                if (fetched != null && fetched.isBefore(now.minus(STALE_AFTER))) {
                    add(stringResource(R.string.price_saved_on, formatDate(fetched.toLocalDate())))
                }
            }
            notes.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = Spacing.sm))
            }
        }
        val cardmarketId = prices.firstNotNullOfOrNull { it.productId.takeIf { _ -> it.source == PriceSource.CARDMARKET } }
        val tcgplayerId = prices.firstNotNullOfOrNull { it.productId.takeIf { _ -> it.source == PriceSource.TCGPLAYER } }
        if (cardmarketId != null || tcgplayerId != null) {
            FlowRow(Modifier.padding(top = Spacing.xs)) {
                if (cardmarketId != null) {
                    TextAction(
                        if (MarketLinks.filtersLanguage(cardLanguage)) {
                            stringResource(R.string.price_open_cardmarket_language, languageName(cardLanguage))
                        } else {
                            stringResource(R.string.price_open_cardmarket)
                        },
                        onClick = { uriHandler.openUri(MarketLinks.cardmarket(cardmarketId, cardLanguage)) },
                    )
                }
                if (tcgplayerId != null) {
                    TextAction(
                        stringResource(R.string.price_open_tcgplayer),
                        onClick = { uriHandler.openUri(MarketLinks.tcgplayer(tcgplayerId)) },
                    )
                }
            }
        }
    }
}
