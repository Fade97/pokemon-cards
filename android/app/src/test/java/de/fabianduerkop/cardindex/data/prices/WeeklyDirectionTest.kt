// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun d(day: String) = LocalDate.parse(day)

/** A cached price row as the free price files give it (#186): today's price and the one of [weekAgoDate]. */
private fun price(
    cardId: String,
    source: PriceSource,
    variant: PriceVariant,
    trend: Long?,
    weekAgo: Long?,
    weekAgoDate: String? = "2026-11-12",
) = CachedPriceEntity(
    cardId = cardId,
    source = source,
    variant = variant,
    currency = source.currency,
    trend = trend,
    low = null,
    avg = null,
    priceDate = null,
    fetchedAt = Instant.EPOCH,
    weekAgoMinor = weekAgo,
    weekAgoDate = weekAgoDate?.let(::d),
)

private fun copy(cardId: String, language: String = "en", quantity: Int = 1, finish: Finish = Finish.NORMAL) =
    OwnedCopyEntity(cardId = cardId, cardLanguage = language, quantity = quantity, finish = finish, createdAt = Instant.EPOCH)

private fun quote(source: PriceSource, variant: PriceVariant, amount: Long) =
    PriceQuote(source, variant, amount, source.currency, null, Instant.EPOCH)

/** The free weekly direction (#186): only a direction, by the same coverage rules as the Pro change. */
class WeeklyDirectionTest {
    private val cm = PriceSource.CARDMARKET
    private val tcg = PriceSource.TCGPLAYER
    private val today = d("2026-11-19")

    @Test
    fun quote_upDownAndFlat_withoutAmounts() {
        fun direction(weekAgo: Long, now: Long) = WeeklyPriceChange.directionOfQuote(
            listOf(price("a-1", cm, PriceVariant.NORMAL, now, weekAgo)),
            quote(cm, PriceVariant.NORMAL, now),
            today,
        )

        assertEquals(WeeklyDirection(PriceDirection.UP, 1, 1), direction(100, 120))
        assertEquals(WeeklyDirection(PriceDirection.DOWN, 1, 1), direction(120, 100))
        assertEquals(WeeklyDirection(PriceDirection.FLAT, 1, 1), direction(100, 100))
    }

    @Test
    fun quote_followsTheQuotedMarketAndPrinting() {
        // The reverse holo moved up, the normal print down, TCGplayer has no week-ago price at all.
        val prices = listOf(
            price("a-1", cm, PriceVariant.NORMAL, 90, 100),
            price("a-1", cm, PriceVariant.REVERSE_HOLO, 300, 200),
            price("a-1", tcg, PriceVariant.NORMAL, 80, null, null),
        )

        assertEquals(
            PriceDirection.UP,
            WeeklyPriceChange.directionOfQuote(prices, quote(cm, PriceVariant.REVERSE_HOLO, 300), today)!!.direction,
        )
        assertEquals(
            PriceDirection.DOWN,
            WeeklyPriceChange.directionOfQuote(prices, quote(cm, PriceVariant.NORMAL, 90), today)!!.direction,
        )
        assertNull(WeeklyPriceChange.directionOfQuote(prices, quote(tcg, PriceVariant.NORMAL, 80), today))
    }

    @Test
    fun quote_staleFilesShowNothing() {
        // The file's week ended 15 Nov, four days before today: the import stopped, the direction says nothing now.
        val stale = listOf(price("a-1", cm, PriceVariant.NORMAL, 120, 100, "2026-11-08"))
        assertNull(WeeklyPriceChange.directionOfQuote(stale, quote(cm, PriceVariant.NORMAL, 120), today))

        // Three days is still fine (one skipped refresh or a missed night).
        val recent = listOf(price("a-1", cm, PriceVariant.NORMAL, 120, 100, "2026-11-09"))
        assertEquals(
            PriceDirection.UP,
            WeeklyPriceChange.directionOfQuote(recent, quote(cm, PriceVariant.NORMAL, 120), today)!!.direction,
        )
    }

    @Test
    fun copies_sumsQuantitiesInTheListMarket() {
        // 3 × (100 → 90) = −30, 1 × (50 → 75) = +25: the list's value went down although one card went up.
        val prices = mapOf(
            "a-1" to listOf(price("a-1", cm, PriceVariant.NORMAL, 90, 100)),
            "a-2" to listOf(price("a-2", cm, PriceVariant.NORMAL, 75, 50)),
        )

        val direction = WeeklyPriceChange.directionOfCopies(listOf(copy("a-1", quantity = 3), copy("a-2")), prices, cm, today)!!

        assertEquals(PriceDirection.DOWN, direction.direction)
        assertEquals(4, direction.coveredCopies)
        assertEquals(4, direction.pricedCopies)
        assertFalse(direction.partial)
    }

    @Test
    fun copies_partialCoverage_atLeastHalfTheValue() {
        // a-2 has no week-ago price. Covered: 100 of 160 (62 %), enough for a direction.
        val prices = mapOf(
            "a-1" to listOf(price("a-1", cm, PriceVariant.NORMAL, 100, 80)),
            "a-2" to listOf(price("a-2", cm, PriceVariant.NORMAL, 60, null, null)),
        )
        val direction = WeeklyPriceChange.directionOfCopies(listOf(copy("a-1"), copy("a-2")), prices, cm, today)!!
        assertEquals(PriceDirection.UP, direction.direction)
        assertEquals(1, direction.coveredCopies)
        assertEquals(2, direction.pricedCopies)
        assertTrue(direction.partial)

        // The chase card lacks it: 30 bulk copies with a direction hold too little of the value to speak for the set.
        val chase = prices + ("a-2" to listOf(price("a-2", cm, PriceVariant.NORMAL, 5000, null, null)))
        val bulk = List(30) { copy("a-1") }
        assertNull(WeeklyPriceChange.directionOfCopies(bulk + copy("a-2"), chase, cm, today))
    }

    @Test
    fun copies_neverMixCurrencies() {
        // A TCGplayer list: the German copy has no TCGplayer price and is left out, never valued with its euro price.
        val prices = mapOf(
            "a-1" to listOf(price("a-1", tcg, PriceVariant.NORMAL, 100, 80), price("a-1", cm, PriceVariant.NORMAL, 50, 900)),
        )

        val direction = WeeklyPriceChange.directionOfCopies(listOf(copy("a-1"), copy("a-1", language = "de")), prices, tcg, today)!!

        assertEquals(PriceDirection.UP, direction.direction)
        assertEquals(1, direction.pricedCopies)
        // The same copies in a Cardmarket list: both priced in EUR, 900 → 50 is down.
        val euros = WeeklyPriceChange.directionOfCopies(listOf(copy("a-1"), copy("a-1", language = "de")), prices, cm, today)!!
        assertEquals(PriceDirection.DOWN, euros.direction)
        assertEquals(2, euros.pricedCopies)
    }

    @Test
    fun copies_followTheQuotedPrinting() {
        // A holo copy on Cardmarket is valued with the normal row (PriceVariant.candidates), so its direction is too.
        val prices = mapOf(
            "a-1" to listOf(price("a-1", cm, PriceVariant.NORMAL, 100, 120), price("a-1", cm, PriceVariant.REVERSE_HOLO, 300, 100)),
        )
        val direction = WeeklyPriceChange.directionOfCopies(listOf(copy("a-1", finish = Finish.HOLO)), prices, cm, today)!!
        assertEquals(PriceDirection.DOWN, direction.direction)
    }

    @Test
    fun copies_nothingWithoutWeekAgoPrices() {
        // Rows cached before #186 (or files from before it) have no week-ago price: no direction, not a flat one.
        val prices = mapOf("a-1" to listOf(price("a-1", cm, PriceVariant.NORMAL, 100, null, null)))
        assertNull(WeeklyPriceChange.directionOfCopies(listOf(copy("a-1")), prices, cm, today))
        assertNull(WeeklyPriceChange.directionOfCopies(emptyList(), prices, cm, today))
    }
}
