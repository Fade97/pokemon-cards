// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Embedded
import de.fabianduerkop.cardindex.model.CollectionKind
import de.fabianduerkop.cardindex.model.Region

/** An owned copy together with its card and set, for lists of copies. */
data class CopyWithCard(
    @Embedded val copy: OwnedCopyEntity,
    @Embedded(prefix = "card_") val card: CardEntity,
    @Embedded(prefix = "set_") val set: CardSetEntity,
)

/**
 * Completion of one set: how many distinct collector numbers the user owns, against the printed total.
 * Secret rares numbered above the printed total count too, so [ownedNumbers] can exceed [printedTotal].
 */
data class SetCompletion(
    val setId: String,
    val name: String,
    val region: Region,
    val printedTotal: Int,
    val ownedNumbers: Int,
    /** Sum of quantities of all copies in the set. */
    val copies: Int,
)

/** A collection with the number of cards in it (sum of quantities). */
data class CollectionSummary(
    val id: Long,
    val name: String,
    val description: String?,
    val kind: CollectionKind,
    val cards: Int,
)
