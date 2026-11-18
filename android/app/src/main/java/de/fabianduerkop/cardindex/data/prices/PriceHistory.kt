// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.db.PriceHistoryEntity
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.LocalDate
import java.time.YearMonth

/** One day's price. */
data class PricePoint(val date: LocalDate, val amountMinor: Long)

/** One day of the portfolio chart: today's copies valued with that day's prices. */
data class PortfolioPoint(val date: LocalDate, val valueMinor: Long, val pricedCopies: Int)

/** The chart ranges. A year is the most the app keeps (see [PriceHistoryRepository]). */
enum class HistoryRange(val days: Long) {
    MONTH(30),
    QUARTER(90),
    YEAR(365),
    ;

    fun from(today: LocalDate): LocalDate = today.minusDays(days)
}

/** The backend's history files are per set; this is its `shardOf` (backend/src/price-data.ts), kept identical. */
object PriceShards {
    fun of(cardId: String): String {
        val cut = cardId.lastIndexOf('-')
        val set = if (cut > 0) cardId.substring(0, cut) else ""
        return if (set.isNotEmpty() && !set.startsWith(".")) set else "_misc"
    }
}

/** Packs a month's series into [PriceHistoryEntity]'s two text columns and back. */
object PriceHistoryCodec {
    fun entity(cardId: String, source: PriceSource, variant: PriceVariant, month: YearMonth, points: List<Pair<LocalDate, Long?>>) =
        PriceHistoryEntity(
            cardId = cardId,
            source = source,
            variant = variant,
            month = month.toString(),
            days = points.joinToString(",") { it.first.dayOfMonth.toString() },
            amounts = points.joinToString(",") { it.second?.toString().orEmpty() },
        )

    /** The days with a price; days without one are left out. A malformed row decodes to nothing. */
    fun points(row: PriceHistoryEntity): List<PricePoint> {
        val month = runCatching { YearMonth.parse(row.month) }.getOrNull() ?: return emptyList()
        if (row.days.isEmpty()) return emptyList()
        val days = row.days.split(',')
        val amounts = row.amounts.split(',')
        if (days.size != amounts.size) return emptyList()
        return days.indices.mapNotNull { i ->
            val day = days[i].toIntOrNull()?.takeIf { it in 1..month.lengthOfMonth() } ?: return@mapNotNull null
            val amount = amounts[i].toLongOrNull() ?: return@mapNotNull null
            PricePoint(month.atDay(day), amount)
        }
    }
}

/** History counterpart of [PriceSelection]: the same printings value a copy on every day. */
object PriceHistorySelection {
    /**
     * Daily price of a printing in [source], from [PriceVariant.candidates] like the current price: the series of the
     * first candidate with any price in [rows]. One printing for the whole series, so a day the market skipped shows
     * as a gap (carried over in [portfolio]) instead of the fallback printing's price, e.g. a holo copy's chart
     * dropping to the normal print for a day. Ascending by date.
     */
    fun series(rows: List<PriceHistoryEntity>, source: PriceSource, finish: Finish, edition: Edition): List<PricePoint> {
        val byVariant = rows.filter { it.source == source }.groupBy { it.variant }
        return PriceVariant.candidates(source, finish, edition).asSequence()
            .map { variant -> byVariant[variant].orEmpty().flatMap(PriceHistoryCodec::points) }
            .firstOrNull { it.isNotEmpty() }
            ?.distinctBy { it.date }
            ?.sortedBy { it.date }
            .orEmpty()
    }

    /**
     * Value of [copies] on each day from [from] on, in [source]'s currency: today's holdings with that day's prices.
     * A copy's last known price carries over days without one (the market skipped a day, or a month isn't loaded
     * yet); before its first price the copy counts as unpriced. Copies [source] doesn't price in their language are
     * left out, as in [PriceSelection.total].
     */
    fun portfolio(
        copies: List<OwnedCopyEntity>,
        historyByCard: Map<String, List<PriceHistoryEntity>>,
        source: PriceSource,
        from: LocalDate,
    ): List<PortfolioPoint> {
        // Copies of the same card and printing share one series.
        val groups = copies.filter { source.prices(it.cardLanguage) }
            .groupBy { Triple(it.cardId, it.finish, it.edition) }
            .map { (key, group) ->
                series(historyByCard[key.first].orEmpty(), source, key.second, key.third) to group.sumOf { it.quantity }
            }
            .filter { it.first.isNotEmpty() }
        val dates = groups.flatMap { (points, _) -> points.map { it.date } }.filter { !it.isBefore(from) }.toSortedSet()
        if (dates.isEmpty()) return emptyList()

        val cursor = IntArray(groups.size)
        val last = arrayOfNulls<Long>(groups.size)
        return dates.map { date ->
            var value = 0L
            var priced = 0
            groups.forEachIndexed { g, (points, quantity) ->
                while (cursor[g] < points.size && !points[cursor[g]].date.isAfter(date)) {
                    last[g] = points[cursor[g]].amountMinor
                    cursor[g]++
                }
                last[g]?.let {
                    value += it * quantity
                    priced += quantity
                }
            }
            PortfolioPoint(date, value, priced)
        }
    }
}
