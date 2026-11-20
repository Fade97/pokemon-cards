// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.collections

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.PriceHistoryEntity
import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.data.prices.PriceHistorySelection
import de.fabianduerkop.cardindex.data.prices.PricePoint
import de.fabianduerkop.cardindex.data.prices.PriceQuote
import de.fabianduerkop.cardindex.data.prices.PriceSelection
import de.fabianduerkop.cardindex.data.prices.currency
import de.fabianduerkop.cardindex.data.prices.prices
import de.fabianduerkop.cardindex.model.PriceSource
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** A recap's span (#175): a calendar month or year, [start] inclusive to [end] exclusive, in the phone's time zone. */
data class RecapPeriod(val kind: Kind, val start: LocalDate, val end: LocalDate) {
    enum class Kind { MONTH, YEAR }

    fun contains(instant: Instant, zone: ZoneId): Boolean {
        val day = instant.atZone(zone).toLocalDate()
        return !day.isBefore(start) && day.isBefore(end)
    }

    /** "2026-11" or "2026": remembers which recaps were opened. */
    val key: String get() = if (kind == Kind.MONTH) YearMonth.from(start).toString() else start.year.toString()

    companion object {
        fun month(month: YearMonth) = RecapPeriod(Kind.MONTH, month.atDay(1), month.plusMonths(1).atDay(1))

        fun year(year: Int) = RecapPeriod(Kind.YEAR, LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1))

        /**
         * The recaps ready on [today]: last month all through this month, and in January also last year. No
         * notifications (#175): the recap waits in the app until it is looked at.
         */
        fun ready(today: LocalDate): List<RecapPeriod> = buildList {
            add(month(YearMonth.from(today).minusMonths(1)))
            if (today.monthValue == 1) add(year(today.year - 1))
        }
    }
}

/** The card with the biggest price in a recap: number, name, set and its price in the main market. */
data class RecapFind(val card: CardEntity, val set: CardSetEntity, val quote: PriceQuote)

/** The owned card whose price rose most over the period (Pro, from the price history). */
data class RecapRiser(val card: CardEntity, val set: CardSetEntity, val startMinor: Long, val endMinor: Long, val currency: String) {
    val deltaMinor: Long get() = endMinor - startMinor
}

/** The collection's value at the period's start and end, each with the copies owned then (Pro). */
data class RecapValueChange(val startMinor: Long, val endMinor: Long, val currency: String) {
    val deltaMinor: Long get() = endMinor - startMinor
}

/** A completed set in a recap: its name and the language it was completed in. */
data class RecapSet(val name: String, val language: String)

/**
 * A monthly or yearly recap (#175), "Wrapped" style but calm: computed on the phone from the copies and prices it
 * already has. [valueChange] and [bestRiser] need the price history, so they are Pro only (ADR 0012) and null without.
 */
data class Recap(
    val period: RecapPeriod,
    val cardsAdded: Int,
    /** What the cards added in the period are worth now, in the main market. */
    val valueAdded: CollectionValue,
    val topFind: RecapFind?,
    val setsCompleted: List<RecapSet>,
    val valueChange: RecapValueChange?,
    val bestRiser: RecapRiser?,
) {
    val isEmpty: Boolean get() = cardsAdded == 0 && setsCompleted.isEmpty()
}

object Recaps {
    /**
     * The recap of [period] for the collection as it is now ([copies], deleted copies are gone). [history] is null
     * without Pro; with it, the value change and best riser come from each printing's daily prices.
     */
    fun build(
        period: RecapPeriod,
        copies: List<CopyWithCard>,
        pricesByCard: Map<String, List<CachedPriceEntity>>,
        source: PriceSource,
        history: Map<String, List<PriceHistoryEntity>>?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Recap {
        val added = copies.filter { period.contains(it.copy.createdAt, zone) }
        val topFind = added.mapNotNull { item ->
            PriceSelection.quote(pricesByCard[item.copy.cardId].orEmpty(), source, item.copy)?.let { RecapFind(item.card, item.set, it) }
        }.maxByOrNull { it.quote.amountMinor }
        val completed = copies.groupBy { it.card.setId to it.copy.cardLanguage }.mapNotNull { (key, group) ->
            val set = group.first().set
            completionDate(group, set.printedTotal, zone)
                ?.takeIf { !it.isBefore(period.start) && it.isBefore(period.end) }
                ?.let { RecapSet(set.name, key.second) }
        }.sortedBy { it.name }
        return Recap(
            period = period,
            cardsAdded = added.sumOf { it.copy.quantity },
            valueAdded = PriceSelection.total(added.map { it.copy }, pricesByCard, source),
            topFind = topFind,
            setsCompleted = completed,
            valueChange = history?.let { valueChange(period, copies, it, source, zone) },
            bestRiser = history?.let { bestRiser(period, copies, it, source, zone) },
        )
    }

    /**
     * The day the base numbers of one set and language ([copies]) were all owned: the latest of each number's first
     * copy. Null while one is missing. Also the certificate's date (#169).
     */
    fun completionDate(copies: List<CopyWithCard>, printedTotal: Int, zone: ZoneId): LocalDate? {
        if (printedTotal <= 0) return null
        val firstByNumber = copies.groupBy { numberKey(it.card.localId) }
            .filterKeys { key -> key.toIntOrNull()?.let { it in 1..printedTotal } == true }
            .mapValues { (_, group) -> group.minOf { it.copy.createdAt } }
        if (firstByNumber.size < printedTotal) return null
        return firstByNumber.values.max().atZone(zone).toLocalDate()
    }

    /** Value at the period's first and last day, each counting the copies owned by then. */
    private fun valueChange(
        period: RecapPeriod,
        copies: List<CopyWithCard>,
        history: Map<String, List<PriceHistoryEntity>>,
        source: PriceSource,
        zone: ZoneId,
    ): RecapValueChange? {
        val last = period.end.minusDays(1)
        var start = 0L
        var end = 0L
        var any = false
        for (item in copies) {
            if (!source.prices(item.copy.cardLanguage)) continue
            val points = series(history, source, item)
            val owned = item.copy.createdAt.atZone(zone).toLocalDate()
            if (!owned.isAfter(period.start)) priceOn(points, period.start)?.let { start += it * item.copy.quantity; any = true }
            if (!owned.isAfter(last)) priceOn(points, last)?.let { end += it * item.copy.quantity; any = true }
        }
        return if (any) RecapValueChange(start, end, source.currency) else null
    }

    /** The owned card (by printing) with the biggest price rise from the period's start to its end. */
    private fun bestRiser(
        period: RecapPeriod,
        copies: List<CopyWithCard>,
        history: Map<String, List<PriceHistoryEntity>>,
        source: PriceSource,
        zone: ZoneId,
    ): RecapRiser? {
        val last = period.end.minusDays(1)
        return copies.asSequence()
            .filter { source.prices(it.copy.cardLanguage) && !it.copy.createdAt.atZone(zone).toLocalDate().isAfter(last) }
            .distinctBy { Triple(it.copy.cardId, it.copy.finish, it.copy.edition) }
            .mapNotNull { item ->
                val points = series(history, source, item)
                val from = priceOn(points, period.start) ?: return@mapNotNull null
                val to = priceOn(points, last) ?: return@mapNotNull null
                RecapRiser(item.card, item.set, from, to, source.currency)
            }
            .filter { it.deltaMinor > 0 }
            .maxByOrNull { it.deltaMinor }
    }

    private fun series(history: Map<String, List<PriceHistoryEntity>>, source: PriceSource, item: CopyWithCard): List<PricePoint> =
        PriceHistorySelection.series(history[item.copy.cardId].orEmpty(), source, item.copy.finish, item.copy.edition)

    /** The last price on or before [day]; null before the first one. */
    private fun priceOn(points: List<PricePoint>, day: LocalDate): Long? = points.lastOrNull { !it.date.isAfter(day) }?.amountMinor
}
