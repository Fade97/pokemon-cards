// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.RefCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTrayTest {
    private fun ref(localId: String) = RefCard(
        key = "en/sv03.5-$localId", cardId = "sv03.5-$localId", lang = "en", setId = "sv03.5", setName = "151",
        localId = localId, official = 165, name = "Card $localId", imageUrl = "", unseen = false,
    )

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
