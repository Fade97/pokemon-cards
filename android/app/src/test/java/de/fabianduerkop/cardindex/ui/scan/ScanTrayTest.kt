// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.RefCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScanTrayTest {
    private fun ref(localId: String, lang: String = "en") = RefCard(
        key = "$lang/sv03.5-$localId", cardId = "sv03.5-$localId", lang = lang, setId = "sv03.5", setName = "151",
        localId = localId, official = 165, name = "Card $localId", imageUrl = "", unseen = false,
    )

    private fun price(source: PriceSource, variant: PriceVariant, minor: Long) = CachedPriceEntity(
        cardId = "c", source = source, variant = variant, currency = if (source == PriceSource.CARDMARKET) "EUR" else "USD",
        trend = minor, low = null, avg = null, priceDate = null, fetchedAt = Instant.EPOCH,
    )

    private val prices = listOf(
        price(PriceSource.CARDMARKET, PriceVariant.NORMAL, 840),
        price(PriceSource.CARDMARKET, PriceVariant.REVERSE_HOLO, 1500),
        price(PriceSource.TCGPLAYER, PriceVariant.NORMAL, 900),
    )

    @Test
    fun sessionValue_sumsAddedCardsInOneMarket_countsUnpricedOnes_andUndoTakesThemOut() {
        var tray = ScanTray()
            .record(LastScan.Added(ref("001"), copyId = 1, prices = prices))
            .record(LastScan.Added(ref("002"), copyId = 2))
            .record(LastScan.Queued(ref("003"), ReviewReason.NUMBER_NOT_READ, itemId = 9))
            .record(LastScan.Added(ref("004", lang = "de"), copyId = 3, prices = prices))

        assertEquals(CollectionValue(1680, PriceSource.CARDMARKET, unpricedCopies = 1, pricedCopies = 2), tray.value(PriceSource.CARDMARKET))
        // TCGplayer doesn't price the German copy: it counts as unpriced in USD, never as its EUR price.
        assertEquals(CollectionValue(900, PriceSource.TCGPLAYER, unpricedCopies = 2, pricedCopies = 1), tray.value(PriceSource.TCGPLAYER))
        // The hit itself still shows a price: Cardmarket's, as the card screen does.
        assertEquals(PriceSource.CARDMARKET, (tray.latest as LastScan.Added).ownQuote(PriceSource.TCGPLAYER)!!.source)

        tray = tray.undo()!!.second
        assertEquals(840L, tray.value(PriceSource.CARDMARKET).amountMinor)
    }

    @Test
    fun sessionValue_followsTheChosenFinish_andPricesArrivingLater() {
        val tray = ScanTray()
            .record(LastScan.Added(ref("001"), copyId = 1))
            .withPrices(1, prices)
        assertEquals(840L, tray.value(PriceSource.CARDMARKET).amountMinor)

        val reverse = tray.withVariant(1, CopyVariant().withFinish(Finish.REVERSE_HOLO))
        assertEquals(1500L, reverse.value(PriceSource.CARDMARKET).amountMinor)
        // TCGplayer has no reverse holo price here: the card is unpriced there, not valued as the normal print.
        assertEquals(0, reverse.value(PriceSource.TCGPLAYER).pricedCopies)
    }

    @Test
    fun sessionValue_keepsCardsPastTheUndoLimit() {
        var tray = ScanTray()
        repeat(ScanTray.MAX_UNDO + 3) { tray = tray.record(LastScan.Added(ref("001"), copyId = it.toLong(), prices = prices)) }

        assertEquals(ScanTray.MAX_UNDO, tray.history.size)
        val value = tray.value(PriceSource.CARDMARKET)
        assertEquals((ScanTray.MAX_UNDO + 3) * 840L, value.amountMinor)
        assertEquals(ScanTray.MAX_UNDO + 3, value.pricedCopies)
        assertEquals((ScanTray.MAX_UNDO + 3) * 900L, tray.value(PriceSource.TCGPLAYER).amountMinor)
    }

    @Test
    fun undoGoesBackOneDecisionAtATimeWithTheChosenFinishAndThenStops() {
        val holo = CopyVariant().withFinish(Finish.HOLO)
        var tray = ScanTray()
            .record(LastScan.Added(ref("001"), copyId = 11))
            .withVariant(11, holo)
            .record(LastScan.Queued(ref("002"), ReviewReason.NUMBER_NOT_READ, itemId = 5))
            .record(LastScan.Added(ref("003"), copyId = 12))
        assertEquals(3, tray.scanned)
        assertEquals(2 to 1, tray.added to tray.queued)
        assertEquals(3L, tray.decisions)

        val (third, afterThird) = tray.undo()!!
        assertEquals(12L, (third as LastScan.Added).copyId)
        tray = afterThird
        assertEquals(2, tray.scanned)
        assertEquals(ref("003"), tray.removed)
        // Undo is not a new decision: no haptic.
        assertEquals(3L, tray.decisions)

        val (second, afterSecond) = tray.undo()!!
        assertEquals(5L, (second as LastScan.Queued).itemId)
        tray = afterSecond
        // Undoing the queued card takes it off the "need checking" count, not the added one.
        assertEquals(1 to 0, tray.added to tray.queued)
        // The card before comes back with the finish the user chose for it.
        val latest = tray.latest as LastScan.Added
        assertEquals(11L, latest.copyId)
        assertEquals(holo, latest.variant)

        tray = tray.undo()!!.second
        assertEquals(0, tray.scanned)
        assertFalse(tray.canUndo)
        // Exhausted: a further press has nothing to take back, so it can't reach older copies.
        assertNull(tray.undo())
    }

    @Test
    fun aNewDecisionClearsTheTakenBackNote() {
        val tray = ScanTray().record(LastScan.Added(ref("001"), copyId = 1)).undo()!!.second
        assertEquals(ref("001"), tray.removed)
        val next = tray.record(LastScan.Added(ref("002"), copyId = 2))
        assertNull(next.removed)
        assertTrue(next.canUndo)
    }

    @Test
    fun variantChangesTouchOnlyTheirCopy() {
        val tray = ScanTray()
            .record(LastScan.Added(ref("001"), copyId = 1))
            .record(LastScan.Added(ref("001"), copyId = 2))
            .withVariant(2, CopyVariant().withFinish(Finish.REVERSE_HOLO))
        val (first, second) = tray.history.map { it as LastScan.Added }
        assertEquals(CopyVariant(), first.variant)
        assertFalse(first.variant.chosen)
        assertEquals(Finish.REVERSE_HOLO, second.variant.finish)
        assertTrue(second.variant.finishChosen)
        // Edition and condition are still the defaults, and say so.
        assertFalse(second.variant.editionChosen || second.variant.conditionChosen)
        assertEquals(Edition.UNLIMITED, second.variant.edition)
        assertEquals(Condition.NEAR_MINT, second.variant.condition)
    }

    @Test
    fun historyKeepsOnlyTheNewestDecisions() {
        var tray = ScanTray()
        repeat(ScanTray.MAX_UNDO + 5) { tray = tray.record(LastScan.Added(ref("001"), copyId = it.toLong())) }
        assertEquals(ScanTray.MAX_UNDO, tray.history.size)
        assertEquals(5L, (tray.history.first() as LastScan.Added).copyId)
        assertEquals(ScanTray.MAX_UNDO + 5, tray.scanned)
    }

    @Test
    fun aFailedSaveIsShownWithoutTouchingTheHistoryAndClearedByTheNextDecision() {
        val added = ScanTray().record(LastScan.Added(ref("001"), copyId = 11))
        val failed = added.failed(ref("002"))
        assertEquals(ref("002"), failed.failed)
        assertEquals(added.history, failed.history)
        assertEquals(1, failed.scanned)
        assertEquals(added.decisions, failed.decisions)
        assertNull(failed.record(LastScan.Added(ref("002"), copyId = 12)).failed)
        assertNull(failed.undo()!!.second.failed)
    }
}
