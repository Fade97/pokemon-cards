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
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The binder cover (#172): the user's pick while it is in the binder, else the most valuable card. */
class BinderCoversTest {
    private val set = CardSetEntity("sv03.5", "151", "SV", "en", 165, Region.INTERNATIONAL)

    /** Copy [id] of card [number]; copies of one number share the card, and so its art. */
    private fun copy(id: Long, number: Int = id.toInt(), art: Boolean = true, language: String = "en") = CopyWithCard(
        copy = OwnedCopyEntity(
            id = id,
            uuid = "u-$id",
            cardId = "sv03.5-%03d".format(number),
            cardLanguage = language,
            createdAt = Instant.EPOCH,
        ),
        card = CardEntity(
            "sv03.5-%03d".format(number), "sv03.5", "%03d".format(number), "Card $number", language, null, null,
            if (art) "https://assets.tcgdex.net/en/sv/sv03.5/%03d".format(number) else null,
        ),
        set = set,
    )

    private fun eur(amount: Long) = PriceQuote(PriceSource.CARDMARKET, PriceVariant.NORMAL, amount, "EUR", null, Instant.EPOCH)
    private fun usd(amount: Long) = PriceQuote(PriceSource.TCGPLAYER, PriceVariant.NORMAL, amount, "USD", null, Instant.EPOCH)

    @Test
    fun emptyBinderHasNoCover() {
        assertNull(binderCover(emptyList(), null, emptyMap(), "EUR"))
        assertNull(binderCover(emptyList(), "u-1", emptyMap(), "EUR"))
    }

    @Test
    fun automaticCoverIsTheMostValuableCard() {
        val copies = listOf(copy(1), copy(2), copy(3))
        val cover = binderCover(copies, null, mapOf(1L to eur(100), 2L to eur(4_500), 3L to eur(900)), "EUR")!!
        assertEquals(2L, cover.copy.copy.id)
        assertFalse(cover.picked)
    }

    @Test
    fun automaticCoverNeverComparesAcrossCurrencies() {
        // 50 000 USD cents is not "more" than 900 EUR cents: the binder's own currency ranks first, as in its Value sort.
        val copies = listOf(copy(1), copy(2))
        val cover = binderCover(copies, null, mapOf(1L to usd(50_000), 2L to eur(900)), "EUR")!!
        assertEquals(2L, cover.copy.copy.id)
    }

    @Test
    fun aPricedCardWinsOverAnUnpricedOneWithArt() {
        val copies = listOf(copy(1), copy(2, art = false))
        assertEquals(2L, binderCover(copies, null, mapOf(2L to eur(10)), "EUR")!!.copy.copy.id)
    }

    @Test
    fun withoutPricesTheFirstCardWithArtInListOrderIsTheCover() {
        val copies = listOf(copy(1, art = false), copy(2), copy(3))
        assertEquals(2L, binderCover(copies, null, emptyMap(), "EUR")!!.copy.copy.id)
    }

    @Test
    fun withoutPricesOrArtTheFirstCardIsTheCover() {
        val copies = listOf(copy(1, art = false), copy(2, art = false))
        assertEquals(1L, binderCover(copies, null, emptyMap(), "EUR")!!.copy.copy.id)
    }

    @Test
    fun thePickedCardWinsOverTheMostValuable() {
        val copies = listOf(copy(1), copy(2))
        val cover = binderCover(copies, "u-1", mapOf(2L to eur(4_500)), "EUR")!!
        assertEquals(1L, cover.copy.copy.id)
        assertTrue(cover.picked)
    }

    @Test
    fun aPickNoLongerInTheBinderFallsBackToTheAutomaticCover() {
        val copies = listOf(copy(1), copy(2))
        val cover = binderCover(copies, "u-9", mapOf(2L to eur(4_500)), "EUR")!!
        assertEquals(2L, cover.copy.copy.id)
        assertFalse(cover.picked)
    }

    @Test
    fun choicesAreOnePerPrintMostValuableFirst() {
        // Copies 1 and 3 are the same card (number 1); 4 is the same number in German, another print.
        val copies = listOf(copy(1, number = 1), copy(2, number = 2), copy(3, number = 1), copy(4, number = 1, language = "de"))
        val choices = coverChoices(copies, null, mapOf(2L to eur(500), 1L to eur(100), 3L to eur(100)), "EUR")
        assertEquals(listOf(2L, 1L, 4L), choices.map { it.copy.id })
    }

    @Test
    fun choicesKeepThePickedCopyOfItsPrint() {
        val copies = listOf(copy(1, number = 1), copy(2, number = 2), copy(3, number = 1))
        val choices = coverChoices(copies, "u-3", emptyMap(), "EUR")
        assertEquals(listOf(3L, 2L), choices.map { it.copy.id })
    }
}
