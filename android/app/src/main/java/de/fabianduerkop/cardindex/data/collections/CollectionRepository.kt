// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.collections

import androidx.room.withTransaction
import de.fabianduerkop.cardindex.data.catalog.CatalogSource
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CollectionCopyCrossRef
import de.fabianduerkop.cardindex.data.db.CollectionEntity
import de.fabianduerkop.cardindex.data.db.CollectionSummary
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.db.SetCompletion
import de.fabianduerkop.cardindex.data.db.WantedCardEntity
import de.fabianduerkop.cardindex.data.db.WantedWithCard
import de.fabianduerkop.cardindex.data.images.ScanImageStore
import de.fabianduerkop.cardindex.model.CollectionKind
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** A set with the slots of its grid, filtered by language. */
data class SetDetail(
    val set: CardSetEntity,
    /** Languages the set has prints or copies in, for the language chips. */
    val languages: List<String>,
    val slots: List<SetSlot>,
    val copies: List<CopyWithCard>,
) {
    /** Every owned slot, secret rares included: what the Owned filter lists. */
    val ownedNumbers: Int get() = slots.count { it.owned }

    /** The header's count and bar: owned base numbers of the printed total, secret rares apart (#132). */
    val progress: SetProgress get() = setProgress(slots.filter { it.owned }.map { it.localId }, set.printedTotal)

    /** What the Missing filter lists, split the same way: base numbers missing and secret rares missing (#161). */
    val missing: SetMissing get() = setMissing(slots, set.printedTotal)
}

/** One card: its print in the requested language (or any), all its prints, and the user's copies of it. */
data class CardDetail(
    val card: CardEntity,
    val set: CardSetEntity?,
    val prints: List<CardEntity>,
    val copies: List<CopyWithCard>,
)

/**
 * Collections over Room (ADR 0006): All Cards, automatic set collections, custom collections, the copies in
 * them, and the wishlist (#134), which holds wanted prints instead of copies. Every write to a user row sets its `updatedAt`, which export/import (#13) and sync (#16) rely on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionRepository(
    private val db: CardIndexDatabase,
    private val catalog: CatalogSource,
    /** The copies' own photos, deleted with their copy. */
    private val scans: ScanImageStore? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val copyDao = db.copyDao()
    private val collectionDao = db.collectionDao()
    private val wishlistDao = db.wishlistDao()

    fun observeAllCopies(): Flow<List<CopyWithCard>> = copyDao.observeAllCopies()

    fun observeOwnedLanguages(): Flow<List<String>> = copyDao.observeOwnedLanguages()

    /** Set collections in [languages]; null means every language the user owns. */
    fun observeSetCompletion(languages: Set<String>?): Flow<List<SetCompletion>> =
        if (languages != null) {
            copyDao.observeSetCompletion(languages.toList())
        } else {
            copyDao.observeOwnedLanguages().flatMapLatest { owned ->
                if (owned.isEmpty()) flowOf(emptyList()) else copyDao.observeSetCompletion(owned)
            }
        }

    /** Emits null while the set is unknown (e.g. a stale link). */
    fun observeSetDetail(setId: String, languages: Set<String>?): Flow<SetDetail?> = combine(
        catalog.observeSet(setId),
        catalog.observeCardsInSet(setId),
        copyDao.observeCopiesInSet(setId),
    ) { set, cards, copies ->
        set ?: return@combine null
        SetDetail(
            set = set,
            languages = (cards.map { it.language } + copies.map { it.card.language }).distinct().sorted(),
            slots = buildSetSlots(set.printedTotal, cards, copies, languages),
            copies = copies,
        )
    }

    fun observeCardDetail(cardId: String, language: String): Flow<CardDetail?> = combine(
        catalog.observePrints(cardId),
        copyDao.observeCopiesOfCard(cardId),
    ) { prints, copies -> prints to copies }
        .flatMapLatest { (prints, copies) ->
            val card = prints.firstOrNull { it.language == language }
                ?: copies.firstOrNull { it.card.language == language }?.card
                ?: prints.firstOrNull()
                ?: copies.firstOrNull()?.card
                ?: return@flatMapLatest flowOf(null)
            catalog.observeSet(card.setId).map { set -> CardDetail(card, set, prints, copies) }
        }

    fun observeCustomCollections(): Flow<List<CollectionSummary>> = collectionDao.observeCustomCollections()

    fun observeCollection(id: Long): Flow<CollectionEntity?> = collectionDao.observe(id)

    fun observeCopiesInCollection(id: Long): Flow<List<CopyWithCard>> = collectionDao.observeCopiesInCollection(id)

    /** Collection id → ids of the copies in it, for the binder covers (#172). */
    fun observeCollectionCopyIds(): Flow<Map<Long, Set<Long>>> =
        collectionDao.observeAllMemberships().map { rows -> rows.groupBy({ it.collectionId }, { it.copyId }).mapValues { it.value.toSet() } }

    fun observeCollectionIdsOf(copyId: Long): Flow<Set<Long>> =
        collectionDao.observeCollectionIdsOf(copyId).map { it.toSet() }

    /** Copy id → names of the custom collections it is in. */
    fun observeMemberships(): Flow<Map<Long, List<String>>> =
        collectionDao.observeMemberships().map { rows -> rows.groupBy({ it.copyId }, { it.name }) }

    suspend fun createCollection(name: String, description: String?): Long = collectionDao.insert(
        CollectionEntity(
            name = name.trim(),
            description = description?.trim()?.ifEmpty { null },
            kind = CollectionKind.CUSTOM,
            updatedAt = now(),
        ),
    )

    /** Renames a collection and sets its cover (#172): a copy uuid, or null for the automatic cover. */
    suspend fun updateCollection(id: Long, name: String, description: String?, coverCopyUuid: String?) {
        val current = collectionDao.byId(id) ?: return
        collectionDao.update(
            current.copy(
                name = name.trim(),
                description = description?.trim()?.ifEmpty { null },
                coverCopyUuid = coverCopyUuid,
                updatedAt = now(),
            ),
        )
    }

    /** Deletes a custom collection; its copies stay in All Cards. */
    suspend fun deleteCollection(id: Long) {
        val current = collectionDao.byId(id) ?: return
        require(current.kind == CollectionKind.CUSTOM) { "Only custom collections can be deleted" }
        collectionDao.delete(current)
    }

    /**
     * Adds a copy (id 0) or saves an edited one, and puts it into exactly [collectionIds]. Returns the row id.
     * The copy is normalized so it is either raw (condition) or graded (grader + grade), never both.
     */
    suspend fun saveCopy(copy: OwnedCopyEntity, collectionIds: Set<Long>): Long = db.withTransaction {
        val now = now()
        val normalized = copy.normalized()
        val id = if (normalized.id == 0L) {
            copyDao.insert(normalized.copy(createdAt = now, updatedAt = now))
        } else {
            copyDao.update(normalized.copy(updatedAt = now))
            normalized.id
        }
        collectionDao.removeCopyFromAll(id)
        collectionIds.forEach { collectionDao.addCopy(CollectionCopyCrossRef(it, id)) }
        id
    }

    /** The wishlist (#134): wanted prints, still wanted ones first. */
    fun observeWishlist(): Flow<List<WantedWithCard>> = wishlistDao.observeWishlist()

    /** Wishlist entries the user has no copy of yet. */
    fun observeWishlistCount(): Flow<Int> = wishlistDao.observeOpenCount()

    fun observeWanted(cardId: String, language: String): Flow<Boolean> = wishlistDao.observeIsWanted(cardId, language)

    /** Puts a print on the wishlist; nothing changes when it is there already. No copy is made. */
    suspend fun addToWishlist(cardId: String, language: String) {
        wishlistDao.insert(WantedCardEntity(cardId = cardId, cardLanguage = language, createdAt = now()))
    }

    suspend fun removeFromWishlist(cardId: String, language: String) {
        wishlistDao.delete(cardId, language)
    }

    suspend fun deleteCopy(copyId: Long) {
        val copy = copyDao.byId(copyId) ?: return
        copyDao.delete(copy)
        scans?.delete(copy.uuid)
    }

    private fun now(): Instant = clock.instant()
}

internal fun OwnedCopyEntity.normalized(): OwnedCopyEntity {
    val graded = grader != null
    return copy(
        quantity = quantity.coerceAtLeast(1),
        condition = if (graded) null else condition,
        grade = if (graded) grade?.trim()?.ifEmpty { null } else null,
        purchaseCurrency = if (purchasePriceCents == null) null else purchaseCurrency,
    )
}
