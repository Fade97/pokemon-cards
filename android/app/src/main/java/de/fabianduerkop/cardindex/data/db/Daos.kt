// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Copy columns plus the card and set columns with the prefixes [CopyWithCard] expects. */
private const val COPY_WITH_CARD_COLUMNS = """
    o.*,
    c.id AS card_id, c.setId AS card_setId, c.localId AS card_localId, c.name AS card_name,
    c.language AS card_language, c.rarity AS card_rarity, c.energyType AS card_energyType,
    c.imageUrl AS card_imageUrl, c.retired AS card_retired, c.numberKey AS card_numberKey,
    c.printings AS card_printings,
    s.id AS set_id, s.name AS set_name, s.series AS set_series, s.language AS set_language,
    s.printedTotal AS set_printedTotal, s.region AS set_region, s.retired AS set_retired
"""

private const val COPY_WITH_CARD_JOIN = """
    FROM owned_copies o
    JOIN cards c ON c.id = o.cardId AND c.language = o.cardLanguage
    JOIN card_sets s ON s.id = c.setId
"""

/*
 * Card search (#99, #124). The inner query filters and sorts only row ids and sort keys, and the outer one joins the
 * page (at most `:limit` rows) back to its card, set and owned count, so the owned subquery doesn't run for every
 * match. Set names are matched once through `card_sets` instead of a set lookup per card row.
 */
private const val SEARCH_HEAD = """
    SELECT c.*, s.name AS setName, s.printedTotal AS printedTotal,
        (SELECT COALESCE(SUM(o.quantity), 0) FROM owned_copies o
            WHERE o.cardId = c.id AND o.cardLanguage = c.language) AS owned
    FROM (
        SELECT c.rowid AS rid,"""

private const val SEARCH_KEYS = """
            (c.name LIKE :prefix ESCAPE '\') AS byPrefix, c.name AS sortName, s.name AS sortSet,
            c.language AS sortLanguage, LENGTH(c.localId) AS sortLength, c.localId AS sortNumber"""

private const val SEARCH_FROM = """
        FROM cards c LEFT JOIN card_sets s ON s.id = c.setId
        WHERE c.retired = 0 AND c.language IN (:languages)
            AND (:total IS NULL OR c.setId IN (SELECT id FROM card_sets WHERE printedTotal = :total))"""

private const val SEARCH_NAME = """
    (c.name LIKE :text ESCAPE '\' OR c.setId IN (SELECT id FROM card_sets WHERE name LIKE :text ESCAPE '\'))"""

private const val SEARCH_ORDER = "byNumber DESC, byPrefix DESC, sortName, sortSet, sortLanguage, sortLength, sortNumber"

private const val SEARCH_TAIL = """
        ORDER BY $SEARCH_ORDER
        LIMIT :limit
    ) p
    JOIN cards c ON c.rowid = p.rid
    LEFT JOIN card_sets s ON s.id = c.setId
    ORDER BY p.byNumber DESC, p.byPrefix DESC, p.sortName, p.sortSet, p.sortLanguage, p.sortLength, p.sortNumber
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
     * Catalog rows that only fill gaps: from the recognition index before a language pack has delivered them
     * (see [de.fabianduerkop.cardindex.data.scan.ScanCatalog]), or from a backup import. IGNORE keeps the richer
     * pack data that is already there.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSetsIfMissing(sets: List<CardSetEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardsIfMissing(cards: List<CardEntity>)

    @Query("SELECT * FROM card_sets WHERE id IN (:ids)")
    suspend fun setsByIds(ids: List<String>): List<CardSetEntity>

    /** Every print (one row per language) of these cards. */
    @Query("SELECT * FROM cards WHERE id IN (:ids)")
    suspend fun cardsByIds(ids: List<String>): List<CardEntity>

    @Query("SELECT * FROM cards WHERE language = :language")
    suspend fun cardsInLanguage(language: String): List<CardEntity>

    /** Before a language pack is written: whatever it doesn't list anymore stays retired (see Entities.kt). */
    @Query("UPDATE cards SET retired = 1 WHERE language = :language")
    suspend fun retireCardsInLanguage(language: String)

    /** A set is retired when none of its cards is current, in any language. */
    @Query(
        """UPDATE card_sets SET retired =
           NOT EXISTS (SELECT 1 FROM cards c WHERE c.setId = card_sets.id AND c.retired = 0)""",
    )
    suspend fun refreshSetRetirement()

    @Query("SELECT * FROM cached_prices WHERE cardId = :cardId")
    fun observePrices(cardId: String): Flow<List<CachedPriceEntity>>

    @Query("SELECT * FROM cached_prices WHERE cardId = :cardId")
    suspend fun prices(cardId: String): List<CachedPriceEntity>

    /** Every cached price; small (a few rows per owned card) and needed whole to value the collection. */
    @Query("SELECT * FROM cached_prices")
    fun observeAllPrices(): Flow<List<CachedPriceEntity>>

    /** When each card's prices were fetched (its oldest row), to refresh only stale cards. */
    @Query("SELECT cardId, MIN(fetchedAt) AS fetchedAt FROM cached_prices WHERE cardId IN (:cardIds) GROUP BY cardId")
    suspend fun priceAges(cardIds: List<String>): List<PriceAge>

    @Query("DELETE FROM cached_prices WHERE cardId IN (:cardIds)")
    suspend fun deletePrices(cardIds: List<String>)

    @Query("SELECT EXISTS(SELECT 1 FROM cards WHERE id = :cardId AND language = :language)")
    suspend fun hasCard(cardId: String, language: String): Boolean

    /**
     * The catalog rows a backup must carry: every card the user owns a copy of, has a price alert on or wants
     * (#134).
     */
    @Query(
        """
        SELECT c.* FROM cards c
        WHERE EXISTS (SELECT 1 FROM owned_copies o WHERE o.cardId = c.id AND o.cardLanguage = c.language)
           OR EXISTS (SELECT 1 FROM price_alerts a WHERE a.cardId = c.id AND a.cardLanguage = c.language)
           OR EXISTS (SELECT 1 FROM wanted_cards w WHERE w.cardId = c.id AND w.cardLanguage = c.language)
        ORDER BY c.setId, c.localId, c.language
        """,
    )
    suspend fun backupCards(): List<CardEntity>

    /** The sets of [backupCards]. */
    @Query(
        """
        SELECT s.* FROM card_sets s
        WHERE s.id IN (
            SELECT c.setId FROM cards c
            WHERE EXISTS (SELECT 1 FROM owned_copies o WHERE o.cardId = c.id AND o.cardLanguage = c.language)
               OR EXISTS (SELECT 1 FROM price_alerts a WHERE a.cardId = c.id AND a.cardLanguage = c.language)
               OR EXISTS (SELECT 1 FROM wanted_cards w WHERE w.cardId = c.id AND w.cardLanguage = c.language)
        )
        ORDER BY s.id
        """,
    )
    suspend fun backupSets(): List<CardSetEntity>

    /** Replaces all cached prices of [cardIds] with [prices], so printings a market dropped don't linger. */
    @Transaction
    suspend fun replacePrices(cardIds: List<String>, prices: List<CachedPriceEntity>) {
        cardIds.chunked(500).forEach { deletePrices(it) }
        upsertPrices(prices)
    }

    @Query("SELECT * FROM card_sets WHERE id = :setId")
    fun observeSet(setId: String): Flow<CardSetEntity?>

    /** Every print of every card in a set, all languages. */
    @Query("SELECT * FROM cards WHERE setId = :setId")
    fun observeCardsInSet(setId: String): Flow<List<CardEntity>>

    /** The prints of one card: one row per language the catalog has it in. */
    @Query("SELECT * FROM cards WHERE id = :cardId ORDER BY language")
    fun observeCardPrints(cardId: String): Flow<List<CardEntity>>

    /**
     * Search (#99) over the current cards in [languages]: cards whose name or set name matches [text] (a LIKE
     * pattern). Matches are ordered by names that start with [prefix], then card name, set name, language and
     * number. A [total] keeps only sets of that printed size. See [de.fabianduerkop.cardindex.data.search.SearchTerms].
     */
    @Query(
        "$SEARCH_HEAD 0 AS byNumber, $SEARCH_KEYS $SEARCH_FROM AND $SEARCH_NAME $SEARCH_TAIL",
    )
    fun searchByName(
        text: String?,
        prefix: String?,
        total: Int?,
        languages: List<String>,
        limit: Int,
    ): Flow<List<CardSearchHit>>

    /**
     * [searchByName] or collector number [number] (without leading zeros, upper case): `151` is a set name and a
     * number. Number matches come first.
     */
    @Query(
        "$SEARCH_HEAD c.numberKey = :number AS byNumber, $SEARCH_KEYS $SEARCH_FROM " +
            "AND (c.numberKey = :number OR $SEARCH_NAME) $SEARCH_TAIL",
    )
    fun searchByNameOrNumber(
        text: String?,
        prefix: String?,
        number: String,
        total: Int?,
        languages: List<String>,
        limit: Int,
    ): Flow<List<CardSearchHit>>

    /** [searchByName] and collector number [number] together ("pikachu 25"); the number index finds the cards. */
    @Query(
        "$SEARCH_HEAD 1 AS byNumber, $SEARCH_KEYS $SEARCH_FROM " +
            "AND c.numberKey = :number AND $SEARCH_NAME $SEARCH_TAIL",
    )
    fun searchByNameAndNumber(
        text: String?,
        prefix: String?,
        number: String,
        total: Int?,
        languages: List<String>,
        limit: Int,
    ): Flow<List<CardSearchHit>>
}

@Dao
interface CopyDao {
    @Insert
    suspend fun insert(copy: OwnedCopyEntity): Long

    @Upsert
    suspend fun upsert(copy: OwnedCopyEntity)

    @Update
    suspend fun update(copy: OwnedCopyEntity)

    @Delete
    suspend fun delete(copy: OwnedCopyEntity)

    /** Undo of a scan that was just added. */
    @Query("DELETE FROM owned_copies WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM owned_copies WHERE id = :id")
    suspend fun byId(id: Long): OwnedCopyEntity?

    /** Every copy, oldest first, for the backup export. */
    @Query("SELECT * FROM owned_copies ORDER BY createdAt, id")
    suspend fun all(): List<OwnedCopyEntity>

    /** Export/import and sync address copies by uuid, not by the device-local row id. */
    @Query("SELECT * FROM owned_copies WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): OwnedCopyEntity?

    /** How many cards the user owns, quantities included; the first-run goal counts toward 10 with it (#174). */
    @Query("SELECT COALESCE(SUM(quantity), 0) FROM owned_copies")
    fun observeCardCount(): Flow<Int>

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

    /** Languages the user owns copies in, for the language filter. */
    @Query("SELECT DISTINCT cardLanguage FROM owned_copies ORDER BY cardLanguage")
    fun observeOwnedLanguages(): Flow<List<String>>

    /** Copies of one card in every language, newest first. */
    @Query("SELECT $COPY_WITH_CARD_COLUMNS $COPY_WITH_CARD_JOIN WHERE o.cardId = :cardId ORDER BY o.createdAt DESC, o.id DESC")
    fun observeCopiesOfCard(cardId: String): Flow<List<CopyWithCard>>

    @Query("SELECT $COPY_WITH_CARD_COLUMNS $COPY_WITH_CARD_JOIN WHERE c.setId = :setId ORDER BY o.createdAt DESC, o.id DESC")
    fun observeCopiesInSet(setId: String): Flow<List<CopyWithCard>>
}

@Dao
interface CollectionDao {
    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addCopy(ref: CollectionCopyCrossRef)

    @Update
    suspend fun update(collection: CollectionEntity)

    @Delete
    suspend fun delete(collection: CollectionEntity)

    @Delete
    suspend fun removeCopy(ref: CollectionCopyCrossRef)

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun byId(id: Long): CollectionEntity?

    @Query("SELECT * FROM collections WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): CollectionEntity?

    @Query("SELECT * FROM collections WHERE kind = 'CUSTOM' ORDER BY name")
    suspend fun allCustom(): List<CollectionEntity>

    /** Custom-collection memberships by uuid, as the backup stores them. */
    @Query(
        """
        SELECT col.uuid AS collectionUuid, o.uuid AS copyUuid
        FROM collection_copies cc
        JOIN collections col ON col.id = cc.collectionId
        JOIN owned_copies o ON o.id = cc.copyId
        WHERE col.kind = 'CUSTOM'
        ORDER BY col.uuid, o.uuid
        """,
    )
    suspend fun membershipUuids(): List<MembershipUuids>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observe(id: Long): Flow<CollectionEntity?>

    @Query("SELECT * FROM collections WHERE uuid = :uuid")
    fun observeByUuid(uuid: String): Flow<CollectionEntity?>

    /** The custom collections a copy is in, for the collection chips of the copy sheet. */
    @Query("SELECT collectionId FROM collection_copies WHERE copyId = :copyId")
    fun observeCollectionIdsOf(copyId: Long): Flow<List<Long>>

    @Query("DELETE FROM collection_copies WHERE copyId = :copyId")
    suspend fun removeCopyFromAll(copyId: Long)

    /** Names of the custom collections each copy is in, for the copy rows ("Trade binder, bought …"). */
    @Query(
        """
        SELECT cc.copyId AS copyId, col.name AS name
        FROM collection_copies cc JOIN collections col ON col.id = cc.collectionId
        ORDER BY col.name
        """,
    )
    fun observeMemberships(): Flow<List<CopyMembership>>

    /** Custom collections with the number of cards in each. */
    @Query(
        """
        SELECT col.id, col.name, col.description, col.kind, COALESCE(SUM(o.quantity), 0) AS cards, col.coverCopyUuid
        FROM collections col
        LEFT JOIN collection_copies cc ON cc.collectionId = col.id
        LEFT JOIN owned_copies o ON o.id = cc.copyId
        WHERE col.kind = 'CUSTOM'
        GROUP BY col.id
        ORDER BY col.name
        """,
    )
    fun observeCustomCollections(): Flow<List<CollectionSummary>>

    /** Every membership of a copy in a custom collection, for the binder covers (#172). */
    @Query("SELECT * FROM collection_copies")
    fun observeAllMemberships(): Flow<List<CollectionCopyCrossRef>>

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

/** The scan session ledger (#163): copies a session added, as they are now. */
@Dao
interface ScanSessionDao {
    @Insert
    suspend fun insert(row: ScanSessionCopyEntity)

    /** The session's copies that still exist, newest first, with their cards and sets. */
    @Query(
        """
        SELECT $COPY_WITH_CARD_COLUMNS $COPY_WITH_CARD_JOIN
        JOIN scan_session_copies ss ON ss.copyId = o.id
        WHERE ss.sessionId = :sessionId
        ORDER BY o.createdAt DESC, o.id DESC
        """,
    )
    fun observeSession(sessionId: String): Flow<List<CopyWithCard>>

    /**
     * Set numbers in [setIds] owned through copies this session did not add: the slots that were filled before
     * (#163). Only the distinct numbers, not whole copies, since the receipt asks again on every change (#175).
     */
    @Query(
        """
        SELECT DISTINCT c.setId AS setId, c.localId AS localId $COPY_WITH_CARD_JOIN
        WHERE c.setId IN (:setIds)
            AND o.id NOT IN (SELECT copyId FROM scan_session_copies WHERE sessionId = :sessionId)
        """,
    )
    suspend fun otherNumbersInSets(sessionId: String, setIds: List<String>): List<SetNumber>
}
