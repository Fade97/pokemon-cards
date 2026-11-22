// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.review

import de.fabianduerkop.cardindex.data.db.ReviewItemEntity
import de.fabianduerkop.cardindex.data.scan.CopyDefaults
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Printings

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

    /**
     * The draft for the chosen card (#143, #219). [checkVariant] is that card's own check ([de.fabianduerkop.cardindex.model.needsVariantCheck]), not
     * the item's: switching to a candidate or a search pick with a 1st edition opens finish and edition, so Confirm
     * waits for the user, and switching to one without goes back to the defaults. Until the user picks a finish, it
     * is the card's only finish if it has one, else the default ([CopyDefaults.finishFor]). Picked values stay.
     */
    fun forCard(printings: Printings?, checkVariant: Boolean = printings?.hasFirstEdition == true): ReviewDraft = copy(
        finish = when {
            finishChosen -> finish
            checkVariant -> null
            else -> CopyDefaults.finishFor(printings)
        },
        edition = when {
            editionChosen -> edition
            checkVariant -> null
            else -> CopyDefaults.edition
        },
    )

    companion object {
        const val MAX_QUANTITY = 999

        fun forItem(checkVariant: Boolean): ReviewDraft =
            if (checkVariant) ReviewDraft(finish = null, edition = null) else ReviewDraft()

        /**
         * The draft stored on [item] (#141), on top of [forItem]. A stored rank whose candidate is gone falls back to
         * the card found in search, if any (#140), else to the best match: a wrong pipeline match must not take the
         * place of a hand pick.
         */
        fun restore(item: ReviewItemEntity, ranks: Collection<Int>, pickedRank: Int?): ReviewDraft {
            val base = forItem(item.checkVariant)
            return base.copy(
                rank = item.draftRank?.takeIf { it in ranks } ?: pickedRank ?: base.rank,
                finish = item.draftFinish ?: base.finish,
                edition = item.draftEdition ?: base.edition,
                condition = item.draftCondition ?: base.condition,
                quantity = item.draftQuantity?.coerceIn(1, MAX_QUANTITY) ?: base.quantity,
                finishChosen = item.draftFinish != null,
                editionChosen = item.draftEdition != null,
                conditionChosen = item.draftCondition != null,
            )
        }
    }
}

/** What [ReviewItemEntity] stores of a draft: only the picked fields, the rank and the quantity (#141). */
fun ReviewDraft.stored(): StoredDraft = StoredDraft(
    rank = rank,
    finish = finish.takeIf { finishChosen },
    edition = edition.takeIf { editionChosen },
    condition = condition.takeIf { conditionChosen },
    quantity = quantity,
)

/** The columns of a stored draft; null is not picked. */
data class StoredDraft(
    val rank: Int?,
    val finish: Finish?,
    val edition: Edition?,
    val condition: Condition?,
    val quantity: Int?,
)
