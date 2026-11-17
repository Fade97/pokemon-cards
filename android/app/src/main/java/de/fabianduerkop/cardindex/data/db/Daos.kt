// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Copy columns plus the card and set columns with the prefixes [CopyWithCard] expects. */
private const val COPY_WITH_CARD_COLUMNS = """
    o.*,
    c.id AS card_id, c.setId AS card_setId, c.localId AS card_localId, c.name AS card_name,
    c.language AS card_language, c.rarity AS card_rarity, c.energyType AS card_energyType,
    c.imageUrl AS card_imageUrl, c.retired AS card_retired,
    s.id AS set_id, s.name AS set_name, s.series AS set_series, s.language AS set_language,
    s.printedTotal AS set_printedTotal, s.region AS set_region, s.retired AS set_retired
"""

private const val COPY_WITH_CARD_JOIN = """
    FROM owned_copies o
    JOIN cards c ON c.id = o.cardId AND c.language = o.cardLanguage
    JOIN card_sets s ON s.id = c.setId
"""

/** Catalog data from language packs and the price API. Upserts only, see Entities.kt. */
@Dao
interface CatalogDao {
    @Upsert
    suspend fun upsertSets(sets: List<CardSetEntity>)

    @Upsert
    suspend fun upsertCards(cards: List<CardEntity>)

    @Upsert
    suspend fun upsertPrices(prices: List<CachedPriceEntity>)

    /**
     * Catalog rows the recognition index knows but no language pack has delivered yet. IGNORE keeps richer pack
     * data that is already there; see [de.fabianduerkop.cardindex.data.scan.ScanCatalog].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSetsIfMissing(sets: List<CardSetEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardsIfMissing(cards: List<CardEntity>)

    @Query("SELECT * FROM cached_prices WHERE cardId = :cardId")
    fun observePrices(cardId: String): Flow<List<CachedPriceEntity>>
}

@Dao
interface CopyDao {
    @Insert
    suspend fun insert(copy: OwnedCopyEntity): Long

    @Upsert
    suspend fun upsert(copy: OwnedCopyEntity)

    @Delete
    suspend fun delete(copy: OwnedCopyEntity)

    /** Undo of a scan that was just added. */
    @Query("DELETE FROM owned_copies WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** Export/import and sync address copies by uuid, not by the device-local row id. */
    @Query("SELECT * FROM owned_copies WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): OwnedCopyEntity?

    /** The All Cards collection: every owned copy, newest first. */
    @Query("SELECT $COPY_WITH_CARD_COLUMNS $COPY_WITH_CARD_JOIN ORDER BY o.createdAt DESC, o.id DESC")
    fun observeAllCopies(): Flow<List<CopyWithCard>>

    /**
     * Set collections: every set with at least one owned copy in [languages], with the number of distinct
     * collector numbers owned. EN/DE/FR prints of the same number count once. International sets come before JP
     * sets (region names sort that way), as on the collections screen.
     */
    @Query(
        """
        SELECT s.id AS setId, s.name AS name, s.region AS region, s.printedTotal AS printedTotal,
            COUNT(DISTINCT c.localId) AS ownedNumbers, SUM(o.quantity) AS copies
        FROM card_sets s
        JOIN cards c ON c.setId = s.id
        JOIN owned_copies o ON o.cardId = c.id AND o.cardLanguage = c.language
        WHERE c.language IN (:languages)
        GROUP BY s.id
        ORDER BY s.region, s.name
        """,
    )
    fun observeSetCompletion(languages: List<String>): Flow<List<SetCompletion>>
}

@Dao
interface CollectionDao {
    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addCopy(ref: CollectionCopyCrossRef)

    @Delete
    suspend fun removeCopy(ref: CollectionCopyCrossRef)

    /** Custom collections with the number of cards in each. */
    @Query(
        """
        SELECT col.id, col.name, col.description, col.kind, COALESCE(SUM(o.quantity), 0) AS cards
        FROM collections col
        LEFT JOIN collection_copies cc ON cc.collectionId = col.id
        LEFT JOIN owned_copies o ON o.id = cc.copyId
        WHERE col.kind = 'CUSTOM'
        GROUP BY col.id
        ORDER BY col.name
        """,
    )
    fun observeCustomCollections(): Flow<List<CollectionSummary>>

    /** Copies in one custom collection, newest first. */
    @Query(
        """
        SELECT $COPY_WITH_CARD_COLUMNS $COPY_WITH_CARD_JOIN
        JOIN collection_copies cc ON cc.copyId = o.id
        WHERE cc.collectionId = :collectionId
        ORDER BY o.createdAt DESC, o.id DESC
        """,
    )
    fun observeCopiesInCollection(collectionId: Long): Flow<List<CopyWithCard>>
}
