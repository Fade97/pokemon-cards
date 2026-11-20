// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.db.PriceHistoryEntity
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.LocalDate

enum class PriceDirection {
    UP, DOWN, FLAT;

    companion object {
        fun of(startMinor: Long, endMinor: Long): PriceDirection = when {
            endMinor > startMinor -> UP
            endMinor < startMinor -> DOWN
            else -> FLAT
        }
    }
}

/**
 * What a set row or copy row shows of the last week: Pro gets the amounts ([WeeklyChange], from the history), free users
 * only the direction ([WeeklyDirection], from the price files, #186). Both cover the same copies by the same rules.
 */
sealed interface WeeklyTrend {
    val direction: PriceDirection

    /** Copies priced at both ends of the week; the rest of [pricedCopies] are left out. */
    val coveredCopies: Int

    /** Copies with a current price in the list's market. */
    val pricedCopies: Int

    /** Some copies with a current price have no price a week ago and are left out of the change. */
    val partial: Boolean get() = coveredCopies < pricedCopies
}

/**
 * How a price or a list's value moved over the last week (#173): [startMinor] on [start] against [endMinor] on [end],
 * in one [currency]. Only copies priced at both ends are in it ([coveredCopies]); [pricedCopies] is how many copies
 * have a current price in that market, so a list can say "3 of 4 cards" when some lack a week of history.
 */
data class WeeklyChange(
    val startMinor: Long,
    val endMinor: Long,
    val currency: String,
    val start: LocalDate,
    val end: LocalDate,
    override val coveredCopies: Int,
    override val pricedCopies: Int,
) : WeeklyTrend {
    val deltaMinor: Long get() = endMinor - startMinor

    override val direction: PriceDirection get() = PriceDirection.of(startMinor, endMinor)
}

/**
 * The free weekly direction (#186, ADR 0010): only which way a price or a list's value moved in the last week, never
 * by how much. Amounts over time are Pro ([WeeklyChange]).
 */
data class WeeklyDirection(
    override val direction: PriceDirection,
    override val coveredCopies: Int,
    override val pricedCopies: Int,
) : WeeklyTrend

/**
 * The weekly change of set rows and copy lists (#173), from the cached Pro price history (ADR 0012), and the free
 * weekly direction (#186) from the week-ago prices in the free price files. Nothing here invents a change: without
 * history or a week-ago price there is none to show.
 *
 * The change runs from the newest history day back [DAYS] days. A day the market skipped takes the last price before
 * it, as in [PriceHistorySelection.portfolio], but only within [MAX_GAP_DAYS]: an older price says nothing about this
 * week. History whose newest day is more than [MAX_GAP_DAYS] before today is stale (the import stopped) and shows no
 * change either.
 *
 * Each copy is followed in the printing its current price comes from ([PriceSelection.quote]), so a list's change
 * covers the same copies and prices as its total, never a fallback printing's series.
 */
object WeeklyPriceChange {
    const val DAYS = 7L

    /** As [PriceFreshness.STALE_AFTER]: the backend imports once a night, so three missed nights is the limit. */
    const val MAX_GAP_DAYS = 3L

    /** The share of a list's current value its covered copies must hold before the list shows a change. */
    const val MIN_COVERED_VALUE = 0.5

    /** The first day the history cache needs for a weekly change on [today]. */
    fun from(today: LocalDate): LocalDate = today.minusDays(DAYS + MAX_GAP_DAYS)

    /**
     * The change of one card's price as [quote] shows it, per card (not times the quantity), in the quote's market
     * and printing. Null when that printing has no price within [MAX_GAP_DAYS] of either end.
     */
    fun ofQuote(rows: List<PriceHistoryEntity>, quote: PriceQuote, today: LocalDate): WeeklyChange? {
        val points = series(rows, quote.source, quote.variant)
        val end = points.lastOrNull()?.date ?: return null
        if (end.isBefore(today.minusDays(MAX_GAP_DAYS))) return null
        val (startPrice, endPrice) = ends(points, end) ?: return null
        return WeeklyChange(startPrice, endPrice, quote.currency, end.minusDays(DAYS), end, 1, 1)
    }

    /**
     * The change of [copies]' value in [source]'s currency: each copy with a current price, valued at both ends with
     * its printing's history, times its quantity. Copies [source] doesn't price (their language, or no fitting
     * printing) are left out, as in [PriceSelection.total]. Null when no copy has a price at both ends, and when the
     * copies that have one hold less than [MIN_COVERED_VALUE] of the list's current value: thirty bulk cards with history
     * say nothing about a set whose chase card has none.
     */
    fun ofCopies(
        copies: List<OwnedCopyEntity>,
        pricesByCard: Map<String, List<CachedPriceEntity>>,
        historyByCard: Map<String, List<PriceHistoryEntity>>,
        source: PriceSource,
        today: LocalDate,
    ): WeeklyChange? {
        // Copies of the same card and printing share one series and one current price.
        val groups = copies.mapNotNull { copy ->
            PriceSelection.quote(pricesByCard[copy.cardId].orEmpty(), source, copy)?.let { Triple(copy.cardId to it.variant, it.amountMinor, copy.quantity) }
        }.groupBy { it.first }.map { (key, rows) ->
            PricedGroup(series(historyByCard[key.first].orEmpty(), source, key.second), rows.first().second, rows.sumOf { it.third })
        }
        val priced = groups.sumOf { it.quantity }
        val pricedValue = groups.sumOf { it.currentMinor * it.quantity }
        val end = groups.mapNotNull { it.points.lastOrNull()?.date }.maxOrNull() ?: return null
        if (end.isBefore(today.minusDays(MAX_GAP_DAYS))) return null

        var startSum = 0L
        var endSum = 0L
        var covered = 0
        var coveredValue = 0L
        for ((points, currentMinor, quantity) in groups) {
            val (startPrice, endPrice) = ends(points, end) ?: continue
            startSum += startPrice * quantity
            endSum += endPrice * quantity
            covered += quantity
            coveredValue += currentMinor * quantity
        }
        if (covered == 0) return null
        if (coveredValue < pricedValue * MIN_COVERED_VALUE) return null
        return WeeklyChange(startSum, endSum, source.currency, end.minusDays(DAYS), end, covered, priced)
    }

    /**
     * Free (#186): the direction of one card's price as [quote] shows it, from the week-ago price the price file
     * carries for the quote's market and printing ([CachedPriceEntity.weekAgoMinor]). Null without one, or when the
     * file's week ended more than [MAX_GAP_DAYS] before [today].
     */
    fun directionOfQuote(prices: List<CachedPriceEntity>, quote: PriceQuote, today: LocalDate): WeeklyDirection? {
        val start = weekAgo(prices, quote.source, quote.variant, today) ?: return null
        return WeeklyDirection(PriceDirection.of(start, quote.amountMinor), 1, 1)
    }

    /**
     * Free (#186): the direction of [copies]' value in [source]'s currency, by the same rules as [ofCopies]: each copy
     * followed in the printing its current price comes from, copies [source] doesn't price left out, and no direction
     * when the copies with a week-ago price hold less than [MIN_COVERED_VALUE] of the list's current value. The week-ago
     * price comes from the price files instead of the Pro history.
     */
    fun directionOfCopies(
        copies: List<OwnedCopyEntity>,
        pricesByCard: Map<String, List<CachedPriceEntity>>,
        source: PriceSource,
        today: LocalDate,
    ): WeeklyDirection? {
        var priced = 0
        var pricedValue = 0L
        var covered = 0
        var startSum = 0L
        var endSum = 0L
        for (copy in copies) {
            val prices = pricesByCard[copy.cardId].orEmpty()
            val quote = PriceSelection.quote(prices, source, copy) ?: continue
            priced += copy.quantity
            pricedValue += quote.amountMinor * copy.quantity
            val start = weekAgo(prices, source, quote.variant, today) ?: continue
            covered += copy.quantity
            startSum += start * copy.quantity
            endSum += quote.amountMinor * copy.quantity
        }
        if (covered == 0) return null
        if (endSum < pricedValue * MIN_COVERED_VALUE) return null
        return WeeklyDirection(PriceDirection.of(startSum, endSum), covered, priced)
    }

    /**
     * The week-ago price of one printing from the cached price files. The current price came with it in the same file,
     * so the week ends [DAYS] after [CachedPriceEntity.weekAgoDate]; an end more than [MAX_GAP_DAYS] before [today] is
     * stale, as in [ofCopies].
     */
    private fun weekAgo(prices: List<CachedPriceEntity>, source: PriceSource, variant: PriceVariant, today: LocalDate): Long? {
        val row = prices.firstOrNull { it.source == source && it.variant == variant } ?: return null
        val date = row.weekAgoDate ?: return null
        if (date.plusDays(DAYS).isBefore(today.minusDays(MAX_GAP_DAYS))) return null
        return row.weekAgoMinor
    }

    private data class PricedGroup(val points: List<PricePoint>, val currentMinor: Long, val quantity: Int)

    /** One printing's daily prices in [source], ascending; days without a price are left out. */
    private fun series(rows: List<PriceHistoryEntity>, source: PriceSource, variant: PriceVariant): List<PricePoint> =
        rows.filter { it.source == source && it.variant == variant }
            .flatMap(PriceHistoryCodec::points)
            .distinctBy { it.date }
            .sortedBy { it.date }

    /** The prices on [end] and [DAYS] before it, each the last known within [MAX_GAP_DAYS]; null if either is missing. */
    private fun ends(points: List<PricePoint>, end: LocalDate): Pair<Long, Long>? {
        val endPrice = priceOn(points, end) ?: return null
        val startPrice = priceOn(points, end.minusDays(DAYS)) ?: return null
        return startPrice to endPrice
    }

    private fun priceOn(points: List<PricePoint>, day: LocalDate): Long? =
        points.lastOrNull { !it.date.isAfter(day) }
            ?.takeIf { !it.date.isBefore(day.minusDays(MAX_GAP_DAYS)) }
            ?.amountMinor
}
