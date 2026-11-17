// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.model

/** Where a set was printed. JP sets are their own set collections; international prints share one (DESIGN.md). */
enum class Region { INTERNATIONAL, JP }

/** Colour key of a card. Trainer cards have no energy type but get their own data colour. */
enum class EnergyType { GRASS, FIRE, WATER, LIGHTNING, PSYCHIC, FIGHTING, DARKNESS, METAL, DRAGON, COLORLESS, TRAINER }

/**
 * Surface finish of an owned copy. Normal and reverse holo can't be told apart reliably from a photo, so it stays
 * editable. [OTHER] covers finishes without their own price (cosmos, cracked ice, …).
 */
enum class Finish { NORMAL, HOLO, REVERSE_HOLO, OTHER }

/**
 * Print run of an owned copy, independent of [Finish]: a 1st-edition holo is [FIRST_EDITION] + [Finish.HOLO].
 * [SHADOWLESS] is the unlimited Base Set run without the drop shadow; 1st-edition Base Set cards are shadowless too
 * but count as [FIRST_EDITION].
 */
enum class Edition { UNLIMITED, FIRST_EDITION, SHADOWLESS }

/** Raw-card condition. A graded copy has a [Grader] and grade instead. */
enum class Condition { NEAR_MINT, LIGHTLY_PLAYED, MODERATELY_PLAYED, HEAVILY_PLAYED, POOR }

enum class Grader { PSA, CGC, BGS, OTHER }

enum class CollectionKind { ALL, SET, CUSTOM }

enum class PriceSource { CARDMARKET, TCGPLAYER }
