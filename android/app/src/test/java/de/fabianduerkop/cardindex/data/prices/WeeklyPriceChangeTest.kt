// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.db.PriceHistoryEntity
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun d(day: String) = LocalDate.parse(day)

/** History rows of one card and printing; split by month like the cache. */
private fun history(cardId: String, source: PriceSource, variant: PriceVariant, vararg points: Pair<String, Long?>) =
    points.map { d(it.first) to it.second }
        .groupBy { YearMonth.from(it.first) }
        .map { (month, days) -> PriceHistoryCodec.entity(cardId, source, variant, month, days) }

private fun current(cardId: String, source: PriceSource, variant: PriceVariant, trend: Long) = CachedPriceEntity(
    cardId = cardId,
    source = source,
    variant = variant,
    currency = source.currency,
    trend = trend,
    low = null,
    avg = null,
    priceDate = null,
    fetchedAt = Instant.EPOCH,
)

private fun copy(cardId: String, language: String = "en", quantity: Int = 1, finish: Finish = Finish.NORMAL) =
    OwnedCopyEntity(cardId = cardId, cardLanguage = language, quantity = quantity, finish = finish, createdAt = Instant.EPOCH)

private fun quote(source: PriceSource, variant: PriceVariant, amount: Long) =
    PriceQuote(source, variant, amount, source.currency, null, Instant.EPOCH)

class WeeklyPriceChangeTest {
    private val cm = PriceSource.CARDMARKET
    private val tcg = PriceSource.TCGPLAYER
    private val today = d("2026-11-19")

    @Test
    fun quote_comparesTheNewestDayWithSevenDaysEarlier() {
        val rows = history("a-1", cm, PriceVariant.NORMAL, "2026-11-11" to 90, "2026-11-12" to 100, "2026-11-15" to 105, "2026-11-19" to 120)

        val change = WeeklyPriceChange.ofQuote(rows, quote(cm, PriceVariant.NORMAL, 120), today)!!

        assertEquals(100L, change.startMinor)
        assertEquals(120L, change.endMinor)
        assertEquals(20L, change.deltaMinor)
        assertEquals(PriceDirection.UP, change.direction)
        assertEquals(d("2026-11-12"), change.start)
        assertEquals(d("2026-11-19"), change.end)
        assertEquals("EUR", change.currency)
        assertFalse(change.partial)
    }

    @Test
    fun quote_fallingAndFlat() {
        val down = history("a-1", cm, PriceVariant.NORMAL, "2026-11-11" to 200, "2026-11-18" to 150)
        assertEquals(PriceDirection.DOWN, WeeklyPriceChange.ofQuote(down, quote(cm, PriceVariant.NORMAL, 150), today)!!.direction)

        val flat = history("a-1", cm, PriceVariant.NORMAL, "2026-11-11" to 150, "2026-11-18" to 150)
        val change = WeeklyPriceChange.ofQuote(flat, quote(cm, PriceVariant.NORMAL, 150), today)!!
        assertEquals(PriceDirection.FLAT, change.direction)
        assertEquals(0L, change.deltaMinor)
    }

    @Test
    fun quote_skippedDaysCarryOverAtMostThreeDays() {
        // Start day 12 Nov missing: 10 Nov is two days earlier, so it counts.
        val near = history("a-1", cm, PriceVariant.NORMAL, "2026-11-10" to 100, "2026-11-19" to 110)
        assertEquals(100L, WeeklyPriceChange.ofQuote(near, quote(cm, PriceVariant.NORMAL, 110), today)!!.startMinor)

        // 8 Nov is four days before the start: too old to say anything about this week.
        val far = history("a-1", cm, PriceVariant.NORMAL, "2026-11-08" to 100, "2026-11-19" to 110)
        assertNull(WeeklyPriceChange.ofQuote(far, quote(cm, PriceVariant.NORMAL, 110), today))
    }

    @Test
    fun quote_lessThanAWeekOfHistoryShowsNothing() {
        val rows = history("a-1", cm, PriceVariant.NORMAL, "2026-11-15" to 100, "2026-11-19" to 110)
        assertNull(WeeklyPriceChange.ofQuote(rows, quote(cm, PriceVariant.NORMAL, 110), today))
        assertNull(WeeklyPriceChange.ofQuote(emptyList(), quote(cm, PriceVariant.NORMAL, 110), today))
    }

    @Test
    fun quote_staleHistoryShowsNothing() {
        // The import stopped on 14 Nov: a change ending then isn't this week's.
        val rows = history("a-1", cm, PriceVariant.NORMAL, "2026-11-07" to 100, "2026-11-14" to 110)
        assertNull(WeeklyPriceChange.ofQuote(rows, quote(cm, PriceVariant.NORMAL, 110), today))
    }

    @Test
    fun quote_followsTheQuotedPrintingAndMarketOnly() {
        val rows = history("a-1", cm, PriceVariant.NORMAL, "2026-11-12" to 100, "2026-11-19" to 300) +
            history("a-1", cm, PriceVariant.REVERSE_HOLO, "2026-11-12" to 500, "2026-11-19" to 400) +
            history("a-1", tcg, PriceVariant.NORMAL, "2026-11-12" to 900, "2026-11-19" to 990)

        val holo = WeeklyPriceChange.ofQuote(rows, quote(cm, PriceVariant.REVERSE_HOLO, 400), today)!!
        assertEquals(500L to 400L, holo.startMinor to holo.endMinor)

        val usd = WeeklyPriceChange.ofQuote(rows, quote(tcg, PriceVariant.NORMAL, 990), today)!!
        assertEquals("USD", usd.currency)
        assertEquals(900L to 990L, usd.startMinor to usd.endMinor)
    }

    @Test
    fun quote_acrossAMonthBoundary() {
        val rows = history("a-1", cm, PriceVariant.NORMAL, "2026-10-29" to 100, "2026-11-05" to 130)
        val change = WeeklyPriceChange.ofQuote(rows, quote(cm, PriceVariant.NORMAL, 130), d("2026-11-05"))!!
        assertEquals(100L to 130L, change.startMinor to change.endMinor)
    }

    @Test
    fun copies_sumTimesQuantityAndCountWhatIsCovered() {
        val prices = mapOf(
            "a-1" to listOf(current("a-1", cm, PriceVariant.NORMAL, 120)),
            "a-2" to listOf(current("a-2", cm, PriceVariant.NORMAL, 50)),
            "a-3" to listOf(current("a-3", cm, PriceVariant.NORMAL, 80)),
        )
        val history = mapOf(
            "a-1" to history("a-1", cm, PriceVariant.NORMAL, "2026-11-12" to 100, "2026-11-19" to 120),
            "a-2" to history("a-2", cm, PriceVariant.NORMAL, "2026-11-12" to 60, "2026-11-19" to 50),
            // a-3 is new on the market: no price a week ago.
            "a-3" to history("a-3", cm, PriceVariant.NORMAL, "2026-11-17" to 80, "2026-11-19" to 80),
        )
        val copies = listOf(copy("a-1", quantity = 2), copy("a-2"), copy("a-3"), copy("a-unpriced"))

        val change = WeeklyPriceChange.ofCopies(copies, prices, history, cm, today)!!

        assertEquals(2 * 100L + 60L, change.startMinor)
        assertEquals(2 * 120L + 50L, change.endMinor)
        assertEquals(PriceDirection.UP, change.direction)
        // 2× a-1 and a-2 covered; a-3 priced today but without a week of history; a-unpriced not in the total at all.
        assertEquals(3, change.coveredCopies)
        assertEquals(4, change.pricedCopies)
        assertTrue(change.partial)
    }

    @Test
    fun copies_followTheirCurrentPriceSPrinting() {
        // A holo copy is priced by the reverse-holo price today, so its change must not come from the normal series.
        val prices = mapOf(
            "a-1" to listOf(current("a-1", cm, PriceVariant.NORMAL, 100), current("a-1", cm, PriceVariant.REVERSE_HOLO, 400)),
        )
        val history = mapOf(
            "a-1" to history("a-1", cm, PriceVariant.NORMAL, "2026-11-12" to 100, "2026-11-19" to 200) +
                history("a-1", cm, PriceVariant.REVERSE_HOLO, "2026-11-12" to 500, "2026-11-19" to 400),
        )

        val change = WeeklyPriceChange.ofCopies(listOf(copy("a-1", finish = Finish.REVERSE_HOLO)), prices, history, cm, today)!!

        assertEquals(500L to 400L, change.startMinor to change.endMinor)
        assertEquals(PriceDirection.DOWN, change.direction)
    }

    @Test
    fun copies_neverMixCurrencies() {
        // A German copy has no TCGplayer price, so a TCGplayer change leaves it out instead of adding euros.
        val prices = mapOf(
            "a-1" to listOf(current("a-1", tcg, PriceVariant.NORMAL, 100), current("a-1", cm, PriceVariant.NORMAL, 90)),
        )
        val history = mapOf(
            "a-1" to history("a-1", tcg, PriceVariant.NORMAL, "2026-11-12" to 80, "2026-11-19" to 100) +
                history("a-1", cm, PriceVariant.NORMAL, "2026-11-12" to 50, "2026-11-19" to 90),
        )

        val change = WeeklyPriceChange.ofCopies(listOf(copy("a-1", "en"), copy("a-1", "de")), prices, history, tcg, today)!!

        assertEquals("USD", change.currency)
        assertEquals(80L to 100L, change.startMinor to change.endMinor)
        assertEquals(1, change.coveredCopies)
        assertEquals(1, change.pricedCopies)
    }

    @Test
    fun copies_nothingWhenNoCopyHasAWeek() {
        val prices = mapOf("a-1" to listOf(current("a-1", cm, PriceVariant.NORMAL, 100)))
        val history = mapOf("a-1" to history("a-1", cm, PriceVariant.NORMAL, "2026-11-18" to 100, "2026-11-19" to 100))

        assertNull(WeeklyPriceChange.ofCopies(listOf(copy("a-1")), prices, history, cm, today))
        assertNull(WeeklyPriceChange.ofCopies(listOf(copy("a-1")), prices, emptyMap(), cm, today))
        assertNull(WeeklyPriceChange.ofCopies(emptyList(), prices, history, cm, today))
    }

    @Test
    fun copies_endOnTheNewestDayOfAnyCard() {
        // a-2's market skipped the newest day: its price two days earlier carries over to 19 Nov.
        val prices = mapOf(
            "a-1" to listOf(current("a-1", cm, PriceVariant.NORMAL, 120)),
            "a-2" to listOf(current("a-2", cm, PriceVariant.NORMAL, 70)),
        )
        val history = mapOf(
            "a-1" to history("a-1", cm, PriceVariant.NORMAL, "2026-11-12" to 100, "2026-11-19" to 120),
            "a-2" to history("a-2", cm, PriceVariant.NORMAL, "2026-11-12" to 60, "2026-11-17" to 70),
        )

        val change = WeeklyPriceChange.ofCopies(listOf(copy("a-1"), copy("a-2")), prices, history, cm, today)!!

        assertEquals(d("2026-11-19"), change.end)
        assertEquals(160L to 190L, change.startMinor to change.endMinor)
        assertEquals(2, change.coveredCopies)
    }

    @Test
    fun from_coversTheWeekAndTheGap() {
        assertEquals(d("2026-11-09"), WeeklyPriceChange.from(today))
    }
}
