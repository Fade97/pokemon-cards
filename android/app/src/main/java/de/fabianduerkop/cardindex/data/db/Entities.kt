// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.fabianduerkop.cardindex.model.CollectionKind
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.EnergyType
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Grader
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.model.Region
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * Local-first schema (ADR 0006). Two kinds of rows:
 *
 * - Catalog rows (sets, cards, prices) come from language packs and the price API and are written with upserts: a
 *   REPLACE would delete the old row first and break the user's copies that point to it. Catalog rows are never
 *   deleted. A card or set that drops out of a pack is marked `retired` and stays, so every owned copy keeps its
 *   card; the foreign keys have no ON DELETE action, so a delete of a referenced row fails instead of orphaning it.
 * - User rows (copies, collections) carry a random [UUID] next to the local row id, plus `updatedAt`. The row id
 *   is only valid on this device; export/import (#13) and sync (#16) match rows by uuid and resolve conflicts by
 *   updatedAt. Whoever changes a user row sets updatedAt.
 */

/**
 * A set, e.g. TCGdex `sv03.5` ("151"). EN/DE/FR/… prints of an international set share one row; JP sets are their
 * own rows with [Region.JP]. [language] is the language of [name].
 */
@Entity(tableName = "card_sets")
data class CardSetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val series: String,
    val language: String,
    val printedTotal: Int,
    val region: Region,
    /** No longer in the language pack. Kept for owned copies; see the policy at the top of this file. */
    val retired: Boolean = false,
)

/**
 * One card in one language. [id] is the TCGdex card id (e.g. `sv03.5-006`), which is the same for every language,
 * so the key is ([id], [language]). [localId] is the number printed on the card (e.g. `006`).
 */
@Entity(
    tableName = "cards",
    primaryKeys = ["id", "language"],
    foreignKeys = [ForeignKey(entity = CardSetEntity::class, parentColumns = ["id"], childColumns = ["setId"])],
    indices = [Index("setId"), Index("setId", "language", "localId")],
)
data class CardEntity(
    val id: String,
    val setId: String,
    val localId: String,
    val name: String,
    val language: String,
    val rarity: String?,
    val energyType: EnergyType?,
    val imageUrl: String?,
    /** No longer in the language pack. Kept for owned copies; see the policy at the top of this file. */
    val retired: Boolean = false,
)

/** A physical copy the user owns. Either [condition] (raw card) or [grader] + [grade] (slab) is set. */
@Entity(
    tableName = "owned_copies",
    foreignKeys = [
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["id", "language"],
            childColumns = ["cardId", "cardLanguage"],
        ),
    ],
    indices = [Index("cardId", "cardLanguage"), Index("uuid", unique = true)],
)
data class OwnedCopyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable id across devices, exports and sync. */
    val uuid: String = UUID.randomUUID().toString(),
    val cardId: String,
    val cardLanguage: String,
    val quantity: Int = 1,
    val finish: Finish = Finish.NORMAL,
    val edition: Edition = Edition.UNLIMITED,
    val condition: Condition? = Condition.NEAR_MINT,
    val grader: Grader? = null,
    /** Grade as printed on the slab, e.g. `9.5`. */
    val grade: String? = null,
    /** Price per copy in minor units (cents) of [purchaseCurrency]. */
    val purchasePriceCents: Long? = null,
    /** ISO 4217 code, e.g. `EUR`. */
    val purchaseCurrency: String? = null,
    val purchaseDate: LocalDate? = null,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
)

/** A collection. ALL and SET collections are created automatically; [setId] is set only for [CollectionKind.SET]. */
@Entity(
    tableName = "collections",
    foreignKeys = [ForeignKey(entity = CardSetEntity::class, parentColumns = ["id"], childColumns = ["setId"])],
    indices = [Index("setId"), Index("uuid", unique = true)],
)
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable id across devices, exports and sync. */
    val uuid: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val kind: CollectionKind,
    val setId: String? = null,
    val updatedAt: Instant,
)

/** Membership of a copy in a custom collection (a binder, a trade pile, a wishlist). */
@Entity(
    tableName = "collection_copies",
    primaryKeys = ["collectionId", "copyId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = OwnedCopyEntity::class,
            parentColumns = ["id"],
            childColumns = ["copyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("copyId")],
)
data class CollectionCopyCrossRef(
    val collectionId: Long,
    val copyId: Long,
)

/**
 * Last known price of one printing of a card from one source, kept for offline use. Prices are per TCGdex card id,
 * not per language: the Cardmarket price guide mixes all languages (DESIGN.md, Prices). Amounts are in minor units.
 * [PriceVariant.candidates] picks the rows that value an owned copy.
 */
@Entity(tableName = "cached_prices", primaryKeys = ["cardId", "source", "variant"])
data class CachedPriceEntity(
    val cardId: String,
    val source: PriceSource,
    val variant: PriceVariant,
    /** ISO 4217 code: EUR for Cardmarket, USD for TCGplayer. */
    val currency: String,
    @ColumnInfo(name = "trendMinor") val trend: Long?,
    @ColumnInfo(name = "lowMinor") val low: Long?,
    @ColumnInfo(name = "avgMinor") val avg: Long?,
    /** When the source published this price (the price guide's date), as opposed to [fetchedAt]. */
    val priceDate: Instant?,
    val fetchedAt: Instant,
)
