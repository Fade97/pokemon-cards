// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceFreshnessTest {
    private val now = Instant.parse("2026-11-18T09:00:00Z")

    private fun quote(source: PriceSource, priceDate: Instant?, fetchedAt: Instant = now) =
        PriceQuote(source, PriceVariant.NORMAL, 100, source.currency, priceDate, fetchedAt)

    @Test
    fun eachMarketIsJudgedOnItsOwnPriceDate_notTheDownloadTime() {
        // Downloaded a minute ago, but Cardmarket's guide is ten days old (#133).
        val cardmarket = PriceFreshness.of(quote(PriceSource.CARDMARKET, now.minus(Duration.ofDays(10))), now)
        val tcgplayer = PriceFreshness.of(quote(PriceSource.TCGPLAYER, now.minus(Duration.ofHours(20))), now)

        assertTrue(cardmarket.stale)
        assertEquals(now.minus(Duration.ofDays(10)), cardmarket.date)
        assertFalse(tcgplayer.stale)
        assertEquals(PriceSource.TCGPLAYER, tcgplayer.source)
    }

    @Test
    fun freshMarketDate_isNotStale_evenWhenTheDownloadIsOld() {
        val date = PriceFreshness.of(quote(PriceSource.CARDMARKET, now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(30))), now)

        assertFalse(date.stale)
    }

    @Test
    fun withoutAMarketDate_theDownloadTimeStandsIn() {
        val old = now.minus(Duration.ofDays(4))

        val date = PriceFreshness.of(quote(PriceSource.TCGPLAYER, priceDate = null, fetchedAt = old), now)

        assertEquals(old, date.date)
        assertTrue(date.stale)
    }

    @Test
    fun staleOnlyAfterThreeDays() {
        assertFalse(PriceFreshness.of(quote(PriceSource.CARDMARKET, now.minus(PriceFreshness.STALE_AFTER)), now).stale)
        assertTrue(
            PriceFreshness.of(quote(PriceSource.CARDMARKET, now.minus(PriceFreshness.STALE_AFTER).minusSeconds(1)), now).stale,
        )
    }
}
