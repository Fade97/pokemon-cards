// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.review

import de.fabianduerkop.cardindex.data.db.ReviewItemEntity
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Printings
import de.fabianduerkop.cardindex.model.ReviewReason
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewDraftTest {
    @Test
    fun ordinaryItemStartsWithDefaultVariantAndCanBeConfirmed() {
        val d = ReviewDraft.forItem(checkVariant = false)
        assertEquals(Finish.NORMAL, d.finish)
        assertEquals(Edition.UNLIMITED, d.edition)
        assertTrue(d.canConfirm)
    }

    @Test
    fun theFinishFollowsTheChosenCardUntilTheUserPicksOne() {
        val holoOnly = Printings.ofKeys(listOf("holo"))
        val several = Printings.ofKeys(listOf("normal", "reverse"))
        val d = ReviewDraft.forItem(checkVariant = false)
        assertEquals(Finish.HOLO, d.forCard(holoOnly).finish)
        assertFalse(d.forCard(holoOnly).finishChosen)
        // Switching to a card with several printings, or unknown ones, goes back to the default.
        assertEquals(Finish.NORMAL, d.forCard(holoOnly).forCard(several).finish)
        assertEquals(Finish.NORMAL, d.forCard(holoOnly).forCard(null).finish)
        // The user's pick stays.
        assertEquals(Finish.REVERSE_HOLO, d.withFinish(Finish.REVERSE_HOLO).forCard(holoOnly).finish)
        // Only a picked finish is stored, so the card's finish is not taken for the user's.
        assertEquals(null, d.forCard(holoOnly).stored().finish)
    }

    /** #219: the variant check follows the chosen candidate, not the item's first match. */
    @Test
    fun theVariantCheckFollowsTheChosenCard() {
        val firstEdition = Printings.ofKeys(listOf("holo", "firstEdition"))
        val unlimited = Printings.ofKeys(listOf("normal", "reverse"))
        // An item whose best match had no 1st edition: switching to a card with one asks again.
        val switched = ReviewDraft.forItem(checkVariant = false).copy(rank = 1).forCard(firstEdition)
        assertEquals(null, switched.finish)
        assertEquals(null, switched.edition)
        assertFalse(switched.canConfirm)
        // An item that asked: switching to a card without a 1st edition goes back to the defaults.
        val back = ReviewDraft.forItem(checkVariant = true).forCard(unlimited, checkVariant = false)
        assertEquals(Finish.NORMAL, back.finish)
        assertEquals(Edition.UNLIMITED, back.edition)
        assertTrue(back.canConfirm)
        // What the user picked stays; only the open field blocks Confirm.
        val picked = ReviewDraft.forItem(checkVariant = false).withFinish(Finish.HOLO).forCard(firstEdition)
        assertEquals(Finish.HOLO, picked.finish)
        assertEquals(null, picked.edition)
        assertTrue(picked.withEdition(Edition.FIRST_EDITION).forCard(firstEdition).canConfirm)
    }

    @Test
    fun checkThisBlocksConfirmUntilFinishAndEditionArePicked() {
        val d = ReviewDraft.forItem(checkVariant = true)
        assertFalse(d.canConfirm)
        assertFalse(d.copy(finish = Finish.HOLO).canConfirm)
        assertFalse(d.copy(edition = Edition.FIRST_EDITION).canConfirm)
        assertTrue(d.copy(finish = Finish.HOLO, edition = Edition.FIRST_EDITION).canConfirm)
    }

    @Test
    fun defaultsStayMarkedAsDefaultsUntilTheUserPicks() {
        val d = ReviewDraft.forItem(checkVariant = false)
        assertFalse(d.finishChosen || d.editionChosen || d.conditionChosen)
        // Picking the value it already has still counts as the user's choice.
        val picked = d.withFinish(Finish.NORMAL).withCondition(Condition.POOR)
        assertTrue(picked.finishChosen)
        assertFalse(picked.editionChosen)
        assertTrue(picked.conditionChosen)
        assertEquals(Condition.POOR, picked.condition)
    }

    @Test
    fun quantityStaysAtLeastOne() {
        val d = ReviewDraft()
        assertEquals(1, d.withQuantity(-1).quantity)
        assertEquals(3, d.withQuantity(1).withQuantity(1).quantity)
        assertEquals(ReviewDraft.MAX_QUANTITY, d.withQuantity(5000).quantity)
    }

    /** #141: what is stored comes back as it was; defaults stay marked as defaults. */
    @Test
    fun aStoredDraftComesBackAsItWas() {
        val draft = ReviewDraft.forItem(checkVariant = true)
            .copy(rank = 2).withFinish(Finish.HOLO).withCondition(Condition.POOR).withQuantity(2)
        val stored = draft.stored()
        assertEquals(StoredDraft(rank = 2, Finish.HOLO, edition = null, Condition.POOR, quantity = 3), stored)

        val item = item(checkVariant = true).copy(
            draftRank = stored.rank, draftFinish = stored.finish, draftEdition = stored.edition,
            draftCondition = stored.condition, draftQuantity = stored.quantity,
        )
        assertEquals(draft, ReviewDraft.restore(item, ranks = listOf(0, 1, 2), pickedRank = null))
    }

    @Test
    fun nothingStored_isTheNewItemDraft_orTheCardFoundInSearch() {
        val item = item(checkVariant = false)
        assertEquals(ReviewDraft.forItem(false), ReviewDraft.restore(item, ranks = listOf(0, 1), pickedRank = null))
        assertEquals(3, ReviewDraft.restore(item, ranks = listOf(0, 1, 3), pickedRank = 3).rank)
        // A stored rank whose candidate is gone falls back the same way.
        assertEquals(3, ReviewDraft.restore(item.copy(draftRank = 1), ranks = listOf(0, 3), pickedRank = 3).rank)
        assertEquals(0, ReviewDraft.restore(item.copy(draftRank = 2), ranks = listOf(0, 1), pickedRank = null).rank)
        assertEquals(1, ReviewDraft.restore(item.copy(draftRank = 1), ranks = listOf(0, 1, 3), pickedRank = 3).rank)
    }

    private fun item(checkVariant: Boolean) = ReviewItemEntity(
        cropPath = "review/x.jpg",
        numberRead = null,
        reason = ReviewReason.NUMBER_NOT_READ,
        checkVariant = checkVariant,
        createdAt = Instant.EPOCH,
    )
}
