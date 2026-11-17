// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.collections

import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.model.EnergyType

/**
 * One numbered slot of a set grid (ADR 0010): filled when the user owns that number in one of the filtered
 * languages. [card] is the print to show and open, null when neither the catalog nor a copy knows the card yet.
 */
data class SetSlot(
    /** The number as printed, from the catalog or a copy; `"24"` for a slot only known from the printed total. */
    val localId: String,
    val card: CardEntity?,
    val owned: Boolean,
    /** Sum of quantities of the owned copies in the filtered languages. */
    val copies: Int,
    /** Uuids of those copies, newest first, to find the user's own photo of the card. */
    val copyUuids: List<String> = emptyList(),
) {
    val energyType: EnergyType? get() = card?.energyType
}

/**
 * Builds the slot grid of a set: one slot per number from 1 to [printedTotal], then the numbers the catalog or the
 * copies know beyond that (secret rares like 180/165, or ids like `TG05`), in print order.
 *
 * EN/DE/FR prints of the same number share a slot, so [languages] (null = all) decides which copies fill it and
 * which print it shows: an owned print first, then a catalog print in a filtered language, then any print.
 */
fun buildSetSlots(
    printedTotal: Int,
    catalogCards: List<CardEntity>,
    copies: List<CopyWithCard>,
    languages: Set<String>?,
): List<SetSlot> {
    fun inFilter(language: String) = languages == null || language in languages

    val ownedCopies = copies.filter { inFilter(it.card.language) }
    val prints = (catalogCards + copies.map { it.card }).distinctBy { it.id to it.language }
    val printsByNumber = prints.groupBy { numberKey(it.localId) }
    val copiesByNumber = ownedCopies.groupBy { numberKey(it.card.localId) }

    val keys = LinkedHashSet<String>()
    for (n in 1..printedTotal) keys += n.toString()
    (printsByNumber.keys + copiesByNumber.keys).sortedWith(PrintOrder).forEach { keys += it }

    return keys.map { key ->
        val owned = copiesByNumber[key].orEmpty()
        val candidates = printsByNumber[key].orEmpty()
        val card = owned.firstOrNull()?.card
            ?: candidates.firstOrNull { inFilter(it.language) }
            ?: candidates.firstOrNull()
        SetSlot(
            localId = card?.localId ?: key,
            card = card,
            owned = owned.isNotEmpty(),
            copies = owned.sumOf { it.copy.quantity },
            copyUuids = owned.map { it.copy.uuid },
        )
    }
}

/** `"006"` and `"6"` are the same slot; non-numeric ids (`TG05`) stay as they are. */
internal fun numberKey(localId: String): String =
    if (localId.isNotEmpty() && localId.all(Char::isDigit)) localId.trimStart('0').ifEmpty { "0" } else localId

/** Numeric ids by value first, then the rest alphabetically, as sets list them. */
internal object PrintOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val na = a.toIntOrNull()
        val nb = b.toIntOrNull()
        return when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            else -> a.compareTo(b)
        }
    }
}
