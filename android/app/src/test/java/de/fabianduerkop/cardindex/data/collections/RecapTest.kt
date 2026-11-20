// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.collections

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.prices.PriceHistoryCodec
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.testing.card
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapTest {
    private val utc = ZoneOffset.UTC
    private val set = CardSetEntity("s", "151", "SV", "en", 3, Region.INTERNATIONAL)
    private val cm = PriceSource.CARDMARKET
    private val november = RecapPeriod.month(YearMonth.of(2026, 11))

    private fun copy(localId: String, day: String, language: String = "en", quantity: Int = 1) = CopyWithCard(
        OwnedCopyEntity(cardId = "s-$localId", cardLanguage = language, quantity = quantity, createdAt = Instant.parse("${day}T12:00:00Z")),
        card("s-$localId", "s", localId, language),
        set,
    )

    private fun price(localId: String, minor: Long) = "s-$localId" to listOf(
        CachedPriceEntity(
            cardId = "s-$localId", source = cm, variant = PriceVariant.NORMAL, currency = "EUR",
            trend = minor, low = null, avg = null, priceDate = null, fetchedAt = Instant.EPOCH,
        ),
    )

    private fun history(localId: String, vararg points: Pair<String, Long>) = "s-$localId" to points
        .map { LocalDate.parse(it.first) to it.second }
        .groupBy { YearMonth.from(it.first) }
        .map { (month, days) -> PriceHistoryCodec.entity("s-$localId", cm, PriceVariant.NORMAL, month, days) }

    @Test
    fun readyRecaps_lastMonthAlways_lastYearInJanuary() {
        assertEquals(listOf(november), RecapPeriod.ready(LocalDate.of(2026, 12, 3)))
        assertEquals(
            listOf(RecapPeriod.month(YearMonth.of(2026, 12)), RecapPeriod.year(2026)),
            RecapPeriod.ready(LocalDate.of(2027, 1, 20)),
        )
        assertEquals("2026-11", november.key)
        assertEquals("2026", RecapPeriod.year(2026).key)
    }

    @Test
    fun countsWhatWasAddedInThePeriod_valueNow_andTheTopFind() {
        val copies = listOf(copy("001", "2026-10-30"), copy("002", "2026-11-02", quantity = 2), copy("003", "2026-11-30"), copy("003", "2026-12-01"))
        val prices = mapOf(price("001", 100), price("002", 250), price("003", 900))

        val recap = Recaps.build(november, copies, prices, cm, history = null, zone = utc)

        assertEquals(3, recap.cardsAdded)
        assertEquals(2 * 250L + 900L, recap.valueAdded.amountMinor)
        assertEquals("003", recap.topFind!!.card.localId)
        // Without Pro there is no history, so no value change and no riser.
        assertNull(recap.valueChange)
        assertNull(recap.bestRiser)
    }

    @Test
    fun aSetCompletedInThePeriodIsListed_perLanguage() {
        val copies = listOf(copy("001", "2026-10-02"), copy("002", "2026-10-05"), copy("003", "2026-11-12"), copy("001", "2026-11-13", language = "de"))

        val recap = Recaps.build(november, copies, emptyMap(), cm, history = null, zone = utc)

        assertEquals(listOf(RecapSet("151", "en")), recap.setsCompleted)
        assertEquals(LocalDate.of(2026, 11, 12), Recaps.completionDate(copies.filter { it.copy.cardLanguage == "en" }, 3, utc))
        assertTrue(Recaps.build(RecapPeriod.month(YearMonth.of(2026, 10)), copies, emptyMap(), cm, null, utc).setsCompleted.isEmpty())
    }

    @Test
    fun withProHistory_valueChangeCountsCopiesOwnedThen_andFindsTheBestRiser() {
        val copies = listOf(copy("001", "2026-10-01"), copy("002", "2026-10-01"), copy("003", "2026-11-20"))
        val history = mapOf(
            history("001", "2026-10-31" to 100, "2026-11-01" to 110, "2026-11-30" to 160),
            history("002", "2026-11-01" to 500, "2026-11-30" to 450),
            history("003", "2026-11-01" to 300, "2026-11-30" to 320),
        )

        val recap = Recaps.build(november, copies, emptyMap(), cm, history, utc)

        // 1 Nov: 001 and 002 owned (110 + 500); 30 Nov: all three (160 + 450 + 320).
        assertEquals(RecapValueChange(610, 930, "EUR"), recap.valueChange)
        // 001 rose by 50, 003 by 20 (owned by the end), 002 fell.
        assertEquals("001", recap.bestRiser!!.card.localId)
        assertEquals(50L, recap.bestRiser!!.deltaMinor)
    }
}
