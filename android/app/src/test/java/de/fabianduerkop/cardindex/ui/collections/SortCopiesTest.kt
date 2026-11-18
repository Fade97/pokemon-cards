// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.prices.PriceQuote
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.model.Region
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SortCopiesTest {
    private val set = CardSetEntity("sv03.5", "151", "SV", "en", 165, Region.INTERNATIONAL)

    private fun copy(id: Long, language: String = "en") = CopyWithCard(
        copy = OwnedCopyEntity(id = id, cardId = "sv03.5-%03d".format(id), cardLanguage = language, createdAt = Instant.EPOCH),
        card = CardEntity("sv03.5-%03d".format(id), "sv03.5", "%03d".format(id), "Card $id", language, null, null, null),
        set = set,
    )

    private fun usd(amount: Long) = PriceQuote(PriceSource.TCGPLAYER, PriceVariant.NORMAL, amount, "USD", null, Instant.EPOCH)
    private fun eur(amount: Long) = PriceQuote(PriceSource.CARDMARKET, PriceVariant.NORMAL, amount, "EUR", null, Instant.EPOCH)

    private fun valueOrder(quotes: Map<Long, PriceQuote>, totalCurrency: String?, ids: List<Long> = listOf(1L, 2L, 3L, 4L, 5L)) =
        sortCopies(ids.map { copy(it) }, CopySort.VALUE, quotes, totalCurrency).map { it.copy.id }

    @Test
    fun mixedBinderPutsTheTotalsCurrencyFirstAndDoesNotCompareAcrossCurrencies() {
        // A dollar total: the German copies are priced in euros by Cardmarket.
        val quotes = mapOf(1L to usd(67), 2L to eur(787), 3L to usd(1_250), 4L to eur(120), 5L to usd(300))

        assertEquals(listOf(3L, 5L, 1L, 2L, 4L), valueOrder(quotes, "USD"))
    }

    @Test
    fun euroTotalPutsTheDollarCopiesAfterTheEuroCopies() {
        val quotes = mapOf(1L to usd(5_000), 2L to eur(787), 3L to eur(120))

        assertEquals(listOf(2L, 3L, 1L), valueOrder(quotes, "EUR", listOf(1L, 2L, 3L)))
    }

    @Test
    fun oneCurrencyBinderSortsByAmountAsBefore() {
        val quotes = mapOf(1L to eur(67), 2L to eur(787), 3L to eur(1_250), 4L to eur(120), 5L to eur(300))
        val before = listOf(1L, 2L, 3L, 4L, 5L).map { copy(it) }
            .sortedByDescending { quotes[it.copy.id]?.amountMinor ?: -1 }.map { it.copy.id }

        assertEquals(listOf(3L, 2L, 5L, 4L, 1L), valueOrder(quotes, "EUR"))
        assertEquals(before, valueOrder(quotes, "EUR"))
    }

    @Test
    fun copiesWithoutAPriceComeLastInTheirListOrder() {
        val quotes = mapOf(2L to usd(67), 4L to eur(787), 5L to usd(300))

        assertEquals(listOf(5L, 2L, 4L, 1L, 3L), valueOrder(quotes, "USD"))
    }

    @Test
    fun otherSortsIgnorePrices() {
        val quotes = mapOf(1L to usd(67), 2L to eur(787))
        val copies = listOf(copy(2), copy(1))

        assertEquals(listOf(1L, 2L), sortCopies(copies, CopySort.NUMBER, quotes, "USD").map { it.copy.id })
        assertEquals(listOf(2L, 1L), sortCopies(copies, CopySort.NEWEST, quotes, "USD").map { it.copy.id })
    }
}
