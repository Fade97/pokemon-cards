// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.model

/** Where a set was printed. JP sets are their own set collections; international prints share one (DESIGN.md). */
enum class Region { INTERNATIONAL, JP }

/** Colour key of a card. Trainer cards have no energy type but get their own data colour. */
enum class EnergyType { GRASS, FIRE, WATER, LIGHTNING, PSYCHIC, FIGHTING, DARKNESS, METAL, DRAGON, COLORLESS, TRAINER }

/** Print variant of an owned copy. Normal and reverse holo can't be told apart reliably, so it stays editable. */
enum class Variant { NORMAL, HOLO, REVERSE_HOLO, FIRST_EDITION, SHADOWLESS, OTHER }

/** Raw-card condition. A graded copy has a [Grader] and grade instead. */
enum class Condition { NEAR_MINT, LIGHTLY_PLAYED, MODERATELY_PLAYED, HEAVILY_PLAYED, POOR }

enum class Grader { PSA, CGC, BGS, OTHER }

enum class CollectionKind { ALL, SET, CUSTOM }

enum class PriceSource { CARDMARKET, TCGPLAYER }
