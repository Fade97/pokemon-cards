// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.review

import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish

/**
 * The user's choices for one review item. Finish and edition are null while the item asks to "check this": then
 * Confirm stays blocked until both are picked, so a guess can't slip into the collection unseen.
 */
data class ReviewDraft(
    /** Rank of the chosen candidate; 0 is the pipeline's best match. */
    val rank: Int = 0,
    val finish: Finish? = Finish.NORMAL,
    val edition: Edition? = Edition.UNLIMITED,
    val condition: Condition = Condition.NEAR_MINT,
    val quantity: Int = 1,
) {
    val variantMissing: Boolean get() = finish == null || edition == null
    val canConfirm: Boolean get() = !variantMissing && quantity in 1..MAX_QUANTITY

    fun withQuantity(delta: Int): ReviewDraft = copy(quantity = (quantity + delta).coerceIn(1, MAX_QUANTITY))

    companion object {
        const val MAX_QUANTITY = 999

        fun forItem(checkVariant: Boolean): ReviewDraft =
            if (checkVariant) ReviewDraft(finish = null, edition = null) else ReviewDraft()
    }
}
