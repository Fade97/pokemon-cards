// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.model

/**
 * One way a card was printed, as TCGdex `variants` names it (docs/packs.md, `printings`). [NORMAL], [REVERSE] and
 * [HOLO] are finishes; [FIRST_EDITION] is a print run of one of them; [W_PROMO] is the "W" stamped promo.
 */
enum class Printing(val key: String, val finish: Finish?) {
    NORMAL("normal", Finish.NORMAL),
    REVERSE("reverse", Finish.REVERSE_HOLO),
    HOLO("holo", Finish.HOLO),
    FIRST_EDITION("firstEdition", null),
    W_PROMO("wPromo", null),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun ofKey(key: String): Printing? = byKey[key]
    }
}

/**
 * The printings of a card from its language pack (#143). A card without them (null where a [Printings] is expected)
 * has unknown printings: TCGdex had no answer, or the pack is older than the field. Unknown is never "normal only".
 */
class Printings private constructor(val values: Set<Printing>) {
    /** The card's finish when TCGdex lists exactly one (holo-only rares, ex/V/full arts); null when there are more. */
    val onlyFinish: Finish? get() = values.mapNotNull { it.finish }.singleOrNull()

    /** The card has a 1st-edition print run, so a scan has to ask which edition the copy is (#145). */
    val hasFirstEdition: Boolean get() = Printing.FIRST_EDITION in values

    /** The keys in [Printing] order, as the pack and the database store them (`holo,firstEdition`). */
    fun keys(): List<String> = Printing.entries.filter { it in values }.map { it.key }

    override fun toString(): String = keys().joinToString(",")

    override fun equals(other: Any?): Boolean = other is Printings && other.values == values

    override fun hashCode(): Int = values.hashCode()

    companion object {
        /** Null for no known printing: keys this app doesn't know are dropped, and nothing left means unknown. */
        fun of(values: Collection<Printing>): Printings? = values.toSet().takeIf { it.isNotEmpty() }?.let(::Printings)

        fun ofKeys(keys: Collection<String>): Printings? = of(keys.mapNotNull(Printing::ofKey))

        /** The stored form ([toString]); null or blank is unknown. */
        fun parse(stored: String?): Printings? =
            stored?.takeIf { it.isNotBlank() }?.let { s -> ofKeys(s.split(',').map(String::trim)) }
    }
}

/**
 * The finish chips for a card with these [printings] (#145): the finishes it was printed in first, then the others,
 * each part in [Finish] order. The others stay selectable, since TCGdex has gaps (#108). Unknown printings keep
 * [Finish] order.
 */
fun finishOrder(printings: Printings?): List<Finish> {
    val printed = printings?.values?.mapNotNullTo(HashSet()) { it.finish } ?: return Finish.entries
    return Finish.entries.sortedBy { it !in printed }
}
