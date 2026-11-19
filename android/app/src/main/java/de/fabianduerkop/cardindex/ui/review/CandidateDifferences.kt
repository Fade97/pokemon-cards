// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.review

import de.fabianduerkop.cardindex.data.collections.numberKey
import de.fabianduerkop.cardindex.data.scan.ReviewCandidate

/**
 * What tells a review item's candidates apart (#161): the collector number, the set and the card language, each
 * true when the candidates don't all share it. The review rows emphasise those parts instead of a similarity
 * score, which says little to the user (the scores stay in Lab). A single candidate differs in nothing.
 */
internal data class CandidateDifferences(val number: Boolean, val set: Boolean, val language: Boolean) {
    companion object {
        val None = CandidateDifferences(number = false, set = false, language = false)
    }
}

internal fun candidateDifferences(candidates: List<ReviewCandidate>): CandidateDifferences {
    if (candidates.size < 2) return CandidateDifferences.None
    fun <T> differs(part: (ReviewCandidate) -> T) = candidates.map(part).distinct().size > 1
    return CandidateDifferences(
        number = differs { numberKey(it.card.localId) },
        set = differs { it.set.id },
        language = differs { it.card.language.lowercase() },
    )
}
