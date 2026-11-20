// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.prices.PriceQuote

/**
 * The card on a binder's cover (#172): the copy the user picked while it is still in the binder, else the automatic
 * cover. Null for an empty binder.
 */
data class BinderCover(
    val copy: CopyWithCard,
    /** Whether the user picked [copy]; false for the automatic cover. */
    val picked: Boolean,
)

/**
 * The cover of a binder holding [copies] (#172). The user's [pickedUuid] wins while that copy is still in the binder;
 * a picked copy that left the binder (moved, deleted) falls back to the automatic cover rather than an empty one.
 *
 * The automatic cover is the most valuable copy, ranked like the binder's Value sort ([sortCopies]: the total's
 * [currency] first, never comparing across currencies), so the cover and the top of the list agree. Without any price
 * it is the first copy in list order (newest first) that has card art, so the cover shows a picture where it can.
 */
internal fun binderCover(
    copies: List<CopyWithCard>,
    pickedUuid: String?,
    quotes: Map<Long, PriceQuote>,
    currency: String?,
): BinderCover? {
    if (pickedUuid != null) {
        copies.firstOrNull { it.copy.uuid == pickedUuid }?.let { return BinderCover(it, picked = true) }
    }
    val ranked = sortCopies(copies, CopySort.VALUE, quotes, currency)
    val auto = ranked.firstOrNull { it.copy.id in quotes }
        ?: ranked.firstOrNull { it.card.imageUrl != null }
        ?: ranked.firstOrNull()
    return auto?.let { BinderCover(it, picked = false) }
}

/**
 * The choices of the cover picker (#172): one per print in the binder (a second copy of the same card looks the
 * same), most valuable first like [binderCover]. The copy kept for a print is the picked one if it is among them,
 * else the first in that order, so the current pick always stays selectable.
 */
internal fun coverChoices(
    copies: List<CopyWithCard>,
    pickedUuid: String?,
    quotes: Map<Long, PriceQuote>,
    currency: String?,
): List<CopyWithCard> {
    val ranked = sortCopies(copies, CopySort.VALUE, quotes, currency)
    val picked = ranked.firstOrNull { it.copy.uuid == pickedUuid }
    return ranked
        .distinctBy { it.card.id to it.card.language }
        .map { choice -> if (picked != null && picked.card.id == choice.card.id && picked.card.language == choice.card.language) picked else choice }
}
