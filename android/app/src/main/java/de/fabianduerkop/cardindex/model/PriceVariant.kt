// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.model

/**
 * The printing a cached price belongs to. Both markets price one product per card and split it by printing
 * (backend `prices.ts`): Cardmarket into its normal and foil (`-holo`) fields, TCGplayer into sub-types.
 */
enum class PriceVariant {
    NORMAL,
    HOLO,
    REVERSE_HOLO,
    FIRST_EDITION,
    FIRST_EDITION_HOLO,
    ;

    companion object {
        /** TCGplayer `subTypeName` → printing; null for sub-types the app doesn't price. */
        fun fromTcgplayerSubType(subType: String): PriceVariant? = when (subType) {
            "Normal", "Unlimited" -> NORMAL
            "Holofoil", "Unlimited Holofoil" -> HOLO
            "Reverse Holofoil" -> REVERSE_HOLO
            "1st Edition", "1st Edition Normal" -> FIRST_EDITION
            "1st Edition Holofoil" -> FIRST_EDITION_HOLO
            else -> null
        }

        /**
         * The single mapping from an owned copy to the cached prices that may value it, best match first.
         *
         * - Cardmarket's guide has one product per card: its normal fields price the card as printed (a holo rare
         *   is a holo), its `-holo` fields the foil, which is reverse holo for Pokémon. Prices are stored as
         *   [NORMAL] and [REVERSE_HOLO]. The guide does not split editions, so 1st-edition and shadowless copies
         *   get no Cardmarket price rather than the much lower unlimited one.
         * - TCGplayer splits by printing. A card that exists only as holo has no "Normal" price (and the other way
         *   round), and the default finish after a scan is a guess, so NORMAL and HOLO fall back to each other.
         *   Shadowless cards are a separate TCGplayer product the mapping doesn't know, so they get no price.
         */
        fun candidates(source: PriceSource, finish: Finish, edition: Edition): List<PriceVariant> = when (source) {
            PriceSource.CARDMARKET -> when {
                edition != Edition.UNLIMITED -> emptyList()
                finish == Finish.REVERSE_HOLO -> listOf(REVERSE_HOLO)
                else -> listOf(NORMAL)
            }
            PriceSource.TCGPLAYER -> when (edition) {
                Edition.SHADOWLESS -> emptyList()
                Edition.FIRST_EDITION -> when (finish) {
                    Finish.HOLO -> listOf(FIRST_EDITION_HOLO)
                    Finish.REVERSE_HOLO -> emptyList() // never printed
                    else -> listOf(FIRST_EDITION, FIRST_EDITION_HOLO)
                }
                Edition.UNLIMITED -> when (finish) {
                    Finish.HOLO -> listOf(HOLO, NORMAL)
                    Finish.REVERSE_HOLO -> listOf(REVERSE_HOLO)
                    else -> listOf(NORMAL, HOLO)
                }
            }
        }
    }
}
