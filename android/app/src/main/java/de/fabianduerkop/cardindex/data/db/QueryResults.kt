// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Embedded
import de.fabianduerkop.cardindex.model.CollectionKind
import de.fabianduerkop.cardindex.model.Region
import java.time.Instant

/** A card found by search (#99), with its set's name and size and how many copies the user owns of this print. */
data class CardSearchHit(
    @Embedded val card: CardEntity,
    val setName: String?,
    val printedTotal: Int?,
    val owned: Int,
)

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

/** One copy's membership in a named custom collection. */
data class CopyMembership(val copyId: Long, val name: String)

/** When a card's cached prices were fetched. */
data class PriceAge(val cardId: String, val fetchedAt: Instant)

/** A custom-collection membership by uuids, for export and import. */
data class MembershipUuids(val collectionUuid: String, val copyUuid: String)
