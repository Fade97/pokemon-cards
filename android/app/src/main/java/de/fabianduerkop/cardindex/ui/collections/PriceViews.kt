// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.data.prices.MarketLinks
import de.fabianduerkop.cardindex.data.prices.PriceQuote
import de.fabianduerkop.cardindex.data.prices.PriceSelection
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.Instant
import java.time.ZoneId

@StringRes
internal fun PriceSource.label(): Int =
    if (this == PriceSource.CARDMARKET) R.string.price_label_cardmarket else R.string.price_label_tcgplayer

@StringRes
private fun PriceVariant.label(): Int = when (this) {
    PriceVariant.NORMAL -> R.string.finish_normal
    PriceVariant.HOLO -> R.string.finish_holo
    PriceVariant.REVERSE_HOLO -> R.string.finish_reverse_holo
    PriceVariant.FIRST_EDITION -> R.string.edition_first
    PriceVariant.FIRST_EDITION_HOLO -> R.string.edition_first
}

private fun Instant.toLocalDate() = atZone(ZoneId.systemDefault()).toLocalDate()

/** A big money amount with a caption line under it, as in the value headers; "No prices yet" without any price. */
@Composable
internal fun ValueBlock(
    value: CollectionValue?,
    caption: String,
    modifier: Modifier = Modifier,
    onCaptionClick: (() -> Unit)? = null,
    priceDate: Instant? = null,
) {
    Column(modifier) {
        val priced = value != null && value.amountMinor > 0
        Text(
            text = if (priced) Money.format(value.amountMinor, value.currency) else stringResource(R.string.collections_no_prices),
            style = if (priced) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineSmall,
            color = if (priced) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        val details = buildList {
            if (value != null && value.unpricedCopies > 0 && priced) {
                add(pluralStringResource(R.plurals.collections_unpriced, value.unpricedCopies, value.unpricedCopies))
            }
            priceDate?.let { add(stringResource(R.string.price_as_of, formatDate(it.toLocalDate()))) }
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = if (onCaptionClick != null) Modifier.clickable(onClick = onCaptionClick) else Modifier,
        )
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Price of a copy at the end of a list row, with a second line under it (the card language). */
@Composable
internal fun QuoteColumn(quote: PriceQuote?, secondLine: String) {
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
        Text(
            quote?.let { Money.format(it.amountMinor, it.currency) } ?: "–",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            secondLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The price card of the card detail screen: the main market's price big, the other market's smaller (DESIGN.md),
 * each labelled with what it is, the market date, and links to the offers. Cardmarket's link filters the offers by the
 * card's language because the free price mixes languages.
 */
@Composable
internal fun PriceBox(
    prices: List<CachedPriceEntity>,
    primary: PriceSource,
    finish: Finish,
    edition: Edition,
    cardLanguage: String,
    modifier: Modifier = Modifier,
) {
    val (main, secondary) = PriceSelection.pair(prices, primary, finish, edition)
    val uriHandler = LocalUriHandler.current
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            if (main == null && secondary == null) {
                Text(stringResource(R.string.price_none), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.price_none_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        main?.let {
                            Text(Money.format(it.amountMinor, it.currency), style = MaterialTheme.typography.headlineLarge)
                            QuoteCaption(it)
                        }
                    }
                    secondary?.let {
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                            Text(Money.format(it.amountMinor, it.currency), style = MaterialTheme.typography.titleMedium)
                            QuoteCaption(it, alignEnd = true)
                        }
                    }
                }
                val variant = (main ?: secondary)!!.variant
                val date = listOfNotNull(main?.priceDate, secondary?.priceDate).maxOrNull()
                Text(
                    listOfNotNull(
                        stringResource(R.string.price_for_print, stringResource(variant.label())),
                        date?.let { stringResource(R.string.price_as_of, formatDate(it.toLocalDate())) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            val cardmarketId = prices.firstNotNullOfOrNull { it.productId.takeIf { _ -> it.source == PriceSource.CARDMARKET } }
            val tcgplayerId = prices.firstNotNullOfOrNull { it.productId.takeIf { _ -> it.source == PriceSource.TCGPLAYER } }
            if (cardmarketId != null) {
                TextButton(onClick = { uriHandler.openUri(MarketLinks.cardmarket(cardmarketId, cardLanguage)) }) {
                    Text(
                        if (MarketLinks.filtersLanguage(cardLanguage)) {
                            stringResource(R.string.price_open_cardmarket_language, languageName(cardLanguage))
                        } else {
                            stringResource(R.string.price_open_cardmarket)
                        },
                    )
                }
            }
            if (tcgplayerId != null) {
                TextButton(onClick = { uriHandler.openUri(MarketLinks.tcgplayer(tcgplayerId)) }) {
                    Text(stringResource(R.string.price_open_tcgplayer))
                }
            }
        }
    }
}

@Composable
private fun QuoteCaption(quote: PriceQuote, alignEnd: Boolean = false) {
    Text(
        stringResource(quote.source.label()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
    )
}
