// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.model

/** Why a scan went to the review queue instead of straight into the collection. Stored by name. */
enum class ReviewReason {
    /** The collector number could not be read, so the picture alone decided. */
    NUMBER_NOT_READ,

    /** The number was read but no close candidate carries it. */
    NUMBER_DIFFERS,

    /** Two candidates with the read number are about as similar, typically the same card in two languages. */
    SIMILAR_CARDS,

    /** The card is clear, but the set has prints that a photo can't tell apart (e.g. 1st edition). */
    CHECK_VARIANT,
}

/**
 * Sets with 1st-edition prints, by TCGdex set id: Base Set, Jungle, Fossil, Team Rocket, both Gym sets and the four
 * Neo sets. Base Set 2 and Legendary Collection had none. Only the fallback for cards with unknown printings (a pack
 * from before #143, or no TCGdex answer); every card from these sets then asks for the edition.
 */
private val FIRST_EDITION_SETS = setOf("base1", "base2", "base3", "base5", "gym1", "gym2", "neo1", "neo2", "neo3", "neo4")

/**
 * Whether a scan of a card has to ask for the variant before it is added: when the card has a 1st-edition printing
 * (#145), or, with unknown [printings], when its set [setId] had 1st-edition prints.
 */
fun needsVariantCheck(setId: String, printings: Printings?): Boolean =
    printings?.hasFirstEdition ?: (setId.lowercase() in FIRST_EDITION_SETS)
