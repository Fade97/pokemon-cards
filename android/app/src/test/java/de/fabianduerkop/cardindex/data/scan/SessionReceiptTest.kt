// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.db.SetNumber
import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.data.prices.PriceSelection
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.testing.card
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReceiptTest {
    private val sv151 = CardSetEntity("sv03.5", "151", "Scarlet & Violet", "en", 165, Region.INTERNATIONAL)
    private var nextId = 1L

    private fun copy(localId: String, language: String = "en", quantity: Int = 1, finish: Finish = Finish.NORMAL) = CopyWithCard(
        OwnedCopyEntity(
            id = nextId++,
            cardId = "sv03.5-$localId",
            cardLanguage = language,
            quantity = quantity,
            finish = finish,
            createdAt = Instant.EPOCH,
        ),
        card("sv03.5-$localId", "sv03.5", localId, language),
        sv151,
    )

    private fun price(cardId: String, minor: Long, date: String = "2026-11-17T02:00:00Z") = CachedPriceEntity(
        cardId = cardId, source = PriceSource.CARDMARKET, variant = PriceVariant.NORMAL, currency = "EUR", trend = minor,
        low = null, avg = null, priceDate = Instant.parse(date), fetchedAt = Instant.EPOCH,
    )

    private val prices = mapOf(
        "sv03.5-006" to listOf(price("sv03.5-006", 4000, "2026-11-18T02:00:00Z")),
        "sv03.5-025" to listOf(price("sv03.5-025", 120)),
    )

    @Test
    fun receipt_valuesLikeTheCollection_andNamesTheBestFind() {
        val session = listOf(copy("025", quantity = 2), copy("006"), copy("099"))
        val receipt = SessionReceipt.build(session, before = emptyList(), prices, PriceSource.CARDMARKET)

        assertEquals(4, receipt.cards)
        assertEquals(CollectionValue(4240, PriceSource.CARDMARKET, unpricedCopies = 1, pricedCopies = 3), receipt.value)
        // The same total Collections shows for these copies.
        assertEquals(PriceSelection.total(session.map { it.copy }, prices, PriceSource.CARDMARKET), receipt.value)
        assertEquals("006", receipt.bestFind!!.card.localId)
        assertEquals(Instant.parse("2026-11-18T02:00:00Z"), receipt.priceDate)
        // Most valuable first, the unpriced print last; two copies of one print are one line.
        assertEquals(listOf("006" to 1, "025" to 2, "099" to 1), receipt.lines.map { it.card.localId to it.copies })
        assertNull(receipt.lines.last().quote)
    }

    @Test
    fun newSlots_countOnlyNumbersNobodyOwnedBefore_acrossLanguages() {
        // 006 was owned in German before; 025 twice this session is one slot; 6 and 006 are the same number.
        val session = listOf(copy("006"), copy("025"), copy("025", language = "de"), copy("099"))
        val receipt = SessionReceipt.build(session, before = listOf(SetNumber("sv03.5", "6")), prices, PriceSource.CARDMARKET)

        assertEquals(2, receipt.newSlots)
        assertEquals(1, receipt.sets)
    }

    @Test
    fun tcgplayerReceipt_neverValuesAGermanCopyInUsd() {
        val tcg = mapOf("sv03.5-025" to listOf(price("sv03.5-025", 120).copy(source = PriceSource.TCGPLAYER, currency = "USD")))
        val receipt = SessionReceipt.build(listOf(copy("025", language = "de")), emptyList(), tcg, PriceSource.TCGPLAYER)

        assertEquals(0, receipt.value.pricedCopies)
        assertEquals(1, receipt.value.unpricedCopies)
        assertNull(receipt.bestFind)
    }

    @Test
    fun anEmptySession_isEmpty() {
        assertTrue(SessionReceipt.build(emptyList(), emptyList(), prices, PriceSource.CARDMARKET).isEmpty)
    }
}
