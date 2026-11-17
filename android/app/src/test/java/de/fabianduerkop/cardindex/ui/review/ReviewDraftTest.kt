// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.review

import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
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
    fun checkThisBlocksConfirmUntilFinishAndEditionArePicked() {
        val d = ReviewDraft.forItem(checkVariant = true)
        assertFalse(d.canConfirm)
        assertFalse(d.copy(finish = Finish.HOLO).canConfirm)
        assertFalse(d.copy(edition = Edition.FIRST_EDITION).canConfirm)
        assertTrue(d.copy(finish = Finish.HOLO, edition = Edition.FIRST_EDITION).canConfirm)
    }

    @Test
    fun quantityStaysAtLeastOne() {
        val d = ReviewDraft()
        assertEquals(1, d.withQuantity(-1).quantity)
        assertEquals(3, d.withQuantity(1).withQuantity(1).quantity)
        assertEquals(ReviewDraft.MAX_QUANTITY, d.withQuantity(5000).quantity)
    }
}
