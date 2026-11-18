// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.review

import de.fabianduerkop.cardindex.data.scan.CopyDefaults
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish

/**
 * The user's choices for one review item. Finish, edition and condition start at [CopyDefaults], the same values a
 * live-scanned copy gets: the camera never detects them, so the screen calls them defaults until the user picks one.
 * Finish and edition are null while the item asks to "check this": then Confirm stays blocked until both are picked,
 * so a guess can't slip into the collection unseen.
 */
data class ReviewDraft(
    /** Rank of the chosen candidate; 0 is the pipeline's best match. */
    val rank: Int = 0,
    val finish: Finish? = CopyDefaults.finish,
    val edition: Edition? = CopyDefaults.edition,
    val condition: Condition = CopyDefaults.condition,
    val quantity: Int = 1,
    /** Which of finish, edition and condition the user picked; the others still show as defaults. */
    val finishChosen: Boolean = false,
    val editionChosen: Boolean = false,
    val conditionChosen: Boolean = false,
) {
    val variantMissing: Boolean get() = finish == null || edition == null
    val canConfirm: Boolean get() = !variantMissing && quantity in 1..MAX_QUANTITY

    fun withQuantity(delta: Int): ReviewDraft = copy(quantity = (quantity + delta).coerceIn(1, MAX_QUANTITY))
    fun withFinish(value: Finish): ReviewDraft = copy(finish = value, finishChosen = true)
    fun withEdition(value: Edition): ReviewDraft = copy(edition = value, editionChosen = true)
    fun withCondition(value: Condition): ReviewDraft = copy(condition = value, conditionChosen = true)

    companion object {
        const val MAX_QUANTITY = 999

        fun forItem(checkVariant: Boolean): ReviewDraft =
            if (checkVariant) ReviewDraft(finish = null, edition = null) else ReviewDraft()
    }
}
