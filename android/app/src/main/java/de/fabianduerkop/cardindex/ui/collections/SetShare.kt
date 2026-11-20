// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.data.collections.SetDetail
import de.fabianduerkop.cardindex.data.collections.SetMissing
import de.fabianduerkop.cardindex.data.collections.SetProgress
import de.fabianduerkop.cardindex.data.collections.numberKey
import de.fabianduerkop.cardindex.ui.components.padCollectorNumber
import kotlin.math.ceil
import kotlin.math.min

/** One slot on the shared slot map (#170): its short number, owned or not, a secret rare, on the wishlist. */
data class ShareSlot(val label: String, val owned: Boolean, val secret: Boolean, val wanted: Boolean)

/** One line of the missing-card checklist: "003/165", the card's name when the catalog knows it, wanted or not. */
data class MissingLine(val number: String, val name: String?, val wanted: Boolean)

/** What both share images and the text checklist of a set show (#170): no card art, only numbers and names. */
data class SetShareData(
    val setName: String,
    /** "EN" or "DE, EN" when the user narrowed the languages, else all of the set's languages; null for none. */
    val languages: String?,
    val progress: SetProgress,
    val missing: SetMissing,
    val slots: List<ShareSlot>,
    val missingLines: List<MissingLine>,
) {
    val anyWanted: Boolean get() = missingLines.any { it.wanted }
}

object SetShare {
    /**
     * The share data of [detail] as the set screen shows it now (its chosen languages), every slot in print order,
     * secret rares included. [wantedKeys] are the [numberKey]s of this set's still-wanted wishlist prints; a missing
     * slot among them is marked, so friends see what matters most.
     */
    fun build(detail: SetDetail, languages: String?, wantedKeys: Set<String>): SetShareData {
        val total = detail.set.printedTotal
        val slots = detail.slots.map { slot ->
            val key = numberKey(slot.localId)
            ShareSlot(label = key, owned = slot.owned, secret = isSecret(key, total), wanted = !slot.owned && key in wantedKeys)
        }
        val missing = detail.slots.filterNot { it.owned }.map { slot ->
            MissingLine(
                number = numberText(slot.localId, total),
                name = slot.card?.name,
                wanted = numberKey(slot.localId) in wantedKeys,
            )
        }
        return SetShareData(detail.set.name, languages, detail.progress, detail.missing, slots, missing)
    }

    /** "003/165 Venusaur ★" per missing card, for the text checklist; wanted ones carry the star. */
    fun checklistLines(lines: List<MissingLine>): List<String> = lines.map { line ->
        listOfNotNull(line.number, line.name, if (line.wanted) WANTED_MARK else null).joinToString(" ")
    }

    /**
     * How many columns of square cells fit [count] slots into a [width] × [height] area with the largest cells:
     * a 165-card set fills the page, a 30-card one gets big squares.
     */
    fun gridColumns(count: Int, width: Float, height: Float): Int {
        if (count <= 0 || width <= 0f || height <= 0f) return 1
        return (1..count).maxBy { columns ->
            val rows = ceil(count / columns.toFloat())
            min(width / columns, height / rows)
        }
    }

    const val WANTED_MARK = "★"

    /** Numbers past the printed total are secret rares; letters (TG05) too, as the set header counts them. */
    private fun isSecret(key: String, printedTotal: Int): Boolean =
        printedTotal > 0 && key.toIntOrNull()?.let { it in 1..printedTotal } != true

    private fun numberText(localId: String, printedTotal: Int): String {
        val number = padCollectorNumber(localId, printedTotal.takeIf { it > 0 })
        return if (printedTotal > 0) number + "/" + padCollectorNumber(printedTotal.toString(), printedTotal) else number
    }
}
