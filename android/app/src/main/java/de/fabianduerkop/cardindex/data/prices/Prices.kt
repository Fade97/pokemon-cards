// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.Instant
import java.util.Locale

/** One price as the UI shows it: an amount from one market for one printing, with the date the market set it. */
data class PriceQuote(
    val source: PriceSource,
    val variant: PriceVariant,
    val amountMinor: Long,
    val currency: String,
    val priceDate: Instant?,
    val fetchedAt: Instant,
) {
    /** Cardmarket's free price guide mixes all card languages (DESIGN.md, Prices); the UI says so next to it. */
    val mixesLanguages: Boolean get() = source == PriceSource.CARDMARKET
}

/** Picks the cached price that values a copy. All price lookups go through here and [PriceVariant.candidates]. */
object PriceSelection {
    /**
     * The best cached price from [source] for a copy of this finish and edition: the first candidate printing with a
     * headline amount (Cardmarket trend, TCGplayer market). Null when the market has no fitting price, e.g. a
     * 1st-edition copy on Cardmarket, which the guide doesn't split by edition.
     */
    fun quote(prices: List<CachedPriceEntity>, source: PriceSource, finish: Finish, edition: Edition): PriceQuote? =
        PriceVariant.candidates(source, finish, edition).firstNotNullOfOrNull { variant ->
            prices.firstOrNull { it.source == source && it.variant == variant && it.trend != null }?.let {
                PriceQuote(source, variant, it.trend!!, it.currency, it.priceDate, it.fetchedAt)
            }
        }

    /** The price of [copy] from [source]; null when that market has none for the copy's language or printing. */
    fun quote(prices: List<CachedPriceEntity>, source: PriceSource, copy: OwnedCopyEntity): PriceQuote? =
        if (source.prices(copy.cardLanguage)) quote(prices, source, copy.finish, copy.edition) else null

    /** A copy's own price, from [primary] or, where TCGplayer has none for its language, from Cardmarket. */
    fun copyQuote(prices: List<CachedPriceEntity>, primary: PriceSource, copy: OwnedCopyEntity): PriceQuote? =
        quote(prices, primary.forLanguage(copy.cardLanguage), copy)

    /**
     * Main and secondary price of a printing in [cardLanguage], main from the user's [primary] market (DESIGN.md:
     * secondary smaller). A print TCGplayer doesn't price gets Cardmarket as its main price and no secondary one.
     */
    fun pair(
        prices: List<CachedPriceEntity>,
        primary: PriceSource,
        finish: Finish,
        edition: Edition,
        cardLanguage: String,
    ): Pair<PriceQuote?, PriceQuote?> {
        val main = primary.forLanguage(cardLanguage)
        val secondary = main.other().takeIf { it.prices(cardLanguage) }
        return quote(prices, main, finish, edition) to secondary?.let { quote(prices, it, finish, edition) }
    }

    /**
     * Sum of [quote] × quantity over copies with a price from [source], and how many copies had none. Copies
     * TCGplayer doesn't price count as unpriced in a TCGplayer total: their Cardmarket price is in another currency.
     */
    fun total(
        copies: List<OwnedCopyEntity>,
        pricesByCard: Map<String, List<CachedPriceEntity>>,
        source: PriceSource,
    ): CollectionValue {
        var sum = 0L
        var unpriced = 0
        for (copy in copies) {
            val quote = quote(pricesByCard[copy.cardId].orEmpty(), source, copy)
            if (quote == null) unpriced += copy.quantity else sum += quote.amountMinor * copy.quantity
        }
        return CollectionValue(sum, source, unpriced)
    }
}

/** The value of a list of copies in one market's currency. */
data class CollectionValue(val amountMinor: Long, val source: PriceSource, val unpricedCopies: Int) {
    val currency: String get() = source.currency
}

val PriceSource.currency: String get() = if (this == PriceSource.CARDMARKET) "EUR" else "USD"

/**
 * Whether this market prices cards in [cardLanguage]. TCGplayer's prices come from its English Pokémon category
 * (TCGCSV category 3), but English and other prints share the TCGdex id, so its price would value a German copy as
 * the English print. Cardmarket's guide mixes all languages and is labelled so.
 */
fun PriceSource.prices(cardLanguage: String): Boolean =
    this != PriceSource.TCGPLAYER || cardLanguage.lowercase(Locale.ROOT) == "en"

/** This market, or Cardmarket for a card language it doesn't price. */
fun PriceSource.forLanguage(cardLanguage: String): PriceSource =
    if (prices(cardLanguage)) this else PriceSource.CARDMARKET

fun PriceSource.other(): PriceSource =
    if (this == PriceSource.CARDMARKET) PriceSource.TCGPLAYER else PriceSource.CARDMARKET

/** The user's choice of main market; [AUTO] follows the region. */
enum class PriceSourcePreference { AUTO, CARDMARKET, TCGPLAYER }

object PriceRegion {
    /**
     * TCGplayer is a US marketplace and also what Canada and the US territories trade on; everyone else gets
     * Cardmarket, the European market (DESIGN.md: EU locales → Cardmarket EUR, US → TCGplayer USD). Regions with
     * neither market (e.g. Japan) get Cardmarket, whose guide at least covers international prints.
     */
    private val tcgplayerRegions = setOf("US", "CA", "PR", "GU", "VI", "AS", "MP", "UM")

    fun defaultSource(locale: Locale = Locale.getDefault()): PriceSource =
        if (locale.country.uppercase(Locale.ROOT) in tcgplayerRegions) PriceSource.TCGPLAYER else PriceSource.CARDMARKET

    fun effective(preference: PriceSourcePreference, locale: Locale = Locale.getDefault()): PriceSource = when (preference) {
        PriceSourcePreference.AUTO -> defaultSource(locale)
        PriceSourcePreference.CARDMARKET -> PriceSource.CARDMARKET
        PriceSourcePreference.TCGPLAYER -> PriceSource.TCGPLAYER
    }
}

/** Links to a card's product page; built from the product ids the price API returns. */
object MarketLinks {
    /** Cardmarket's article-language filter ids (`?language=`). Languages Cardmarket doesn't list get no filter. */
    private val cardmarketLanguages = mapOf(
        "en" to 1, "fr" to 2, "de" to 3, "es" to 4, "it" to 5, "zh-cn" to 6, "ja" to 7, "pt" to 8, "ko" to 10, "zh-tw" to 11,
    )

    /** Site languages Cardmarket serves; other UI languages get the English site. */
    private val cardmarketSites = setOf("en", "de", "fr", "it", "es")

    /**
     * `/Products?idProduct=` redirects to the product page and keeps simple filters, so the link opens the offers in
     * the card's language: the free guide's price mixes languages, the offers don't (#11).
     */
    fun cardmarket(productId: Long, cardLanguage: String, uiLocale: Locale = Locale.getDefault()): String {
        val site = uiLocale.language.takeIf { it in cardmarketSites } ?: "en"
        val language = cardmarketLanguages[cardLanguage.lowercase(Locale.ROOT)]
        return "https://www.cardmarket.com/$site/Pokemon/Products?idProduct=$productId" +
            (language?.let { "&language=$it" } ?: "")
    }

    fun filtersLanguage(cardLanguage: String): Boolean = cardLanguage.lowercase(Locale.ROOT) in cardmarketLanguages

    fun tcgplayer(productId: Long): String = "https://www.tcgplayer.com/product/$productId"
}
