// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import androidx.room.withTransaction
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.db.ReviewCandidateEntity
import de.fabianduerkop.cardindex.data.db.ReviewItemEntity
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.Match
import de.fabianduerkop.cardindex.recognition.NumberReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.time.Instant
import java.util.UUID

/** A candidate of a review item with its catalog card and set. */
data class ReviewCandidate(val rank: Int, val similarity: Float, val card: CardEntity, val set: CardSetEntity)

/** A card the user picked by hand for a review item: its candidate [rank], and the item's variant check. */
data class PickedCard(val rank: Int, val checkVariant: Boolean)

/** A queued scan with its crop and its candidates, best first. */
data class ReviewItem(val entity: ReviewItemEntity, val cropFile: File, val candidates: List<ReviewCandidate>)

/**
 * A confirmed review item: the copy it became ([copyId]) and everything the confirm removed, so
 * [ReviewQueueRepository.undoConfirm] can put the item back as it was. [cropJpeg] is null if the crop couldn't be read.
 */
class ConfirmedReview(
    val copyId: Long,
    val item: ReviewItemEntity,
    val candidates: List<ReviewCandidateEntity>,
    val cropJpeg: ByteArray?,
)

/**
 * The review queue (#9): unsure live scans, persisted in Room with their crop as a JPEG under
 * `files/review/`. Live scanning never waits for the user; these items do.
 */
class ReviewQueueRepository(
    private val db: CardIndexDatabase,
    /** The app's files directory; crop paths are stored relative to it. */
    private val filesDir: File,
    private val clock: () -> Instant = Instant::now,
) {
    private val dao get() = db.reviewDao()

    /** Number of items waiting; every one of them is reachable in the review screen. */
    fun observeCount(): Flow<Int> = dao.observeCount()

    /**
     * Queues a scan: [cropJpeg] is the rectified card, [candidates] the pipeline's matches best first (the first
     * [MAX_CANDIDATES] are kept). Returns the item id.
     */
    suspend fun enqueue(
        cropJpeg: ByteArray,
        candidates: List<Match>,
        reading: NumberReading?,
        reason: ReviewReason,
        checkVariant: Boolean,
    ): Long {
        val picks = candidates.distinctBy { it.card.key }.take(MAX_CANDIDATES)
        require(picks.isNotEmpty()) { "a review item needs at least one candidate" }
        val uuid = UUID.randomUUID().toString()
        val relative = "$CROP_DIR/$uuid.jpg"
        val file = File(filesDir, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(cropJpeg)
        val now = clock()
        try {
            return db.withTransaction {
                ScanCatalog.ensure(db.catalogDao(), picks.map { it.card })
                val id = dao.insertItem(
                    ReviewItemEntity(
                        uuid = uuid,
                        cropPath = relative,
                        numberRead = reading?.let { r -> r.total?.let { "${r.number}/$it" } ?: r.number },
                        reason = reason,
                        checkVariant = checkVariant,
                        createdAt = now,
                    ),
                )
                dao.insertCandidates(
                    picks.mapIndexed { rank, m -> ReviewCandidateEntity(id, rank, m.card.cardId, m.card.lang, m.similarity) },
                )
                id
            }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    /** The queue, front first. */
    fun observeItems(): Flow<List<ReviewItem>> = combine(dao.observeItems(), dao.observeCandidates()) { items, rows ->
        val byItem = rows.groupBy { it.itemId }
        items.map { item ->
            ReviewItem(item, cropFile(item), byItem[item.id].orEmpty().map { ReviewCandidate(it.rank, it.similarity, it.card, it.set) })
        }
    }

    /**
     * Adds the candidate at [rank] as an owned copy with the user's variant, condition and quantity, and removes the
     * item. One transaction, so a crash can't leave both the copy and the item (a later confirm would double it).
     * Returns what was changed, for [undoConfirm].
     */
    suspend fun confirm(
        itemId: Long,
        rank: Int,
        finish: Finish,
        edition: Edition,
        condition: Condition,
        quantity: Int,
    ): ConfirmedReview {
        require(quantity >= 1) { "quantity must be at least 1, was $quantity" }
        lateinit var item: ReviewItemEntity
        lateinit var candidates: List<ReviewCandidateEntity>
        val copyId = db.withTransaction {
            item = dao.item(itemId) ?: throw NoSuchElementException("review item $itemId is gone")
            val candidate = dao.candidate(itemId, rank) ?: throw IllegalArgumentException("item $itemId has no candidate $rank")
            candidates = dao.candidates(itemId)
            val id = db.copyDao().insert(
                OwnedCopyEntity(
                    cardId = candidate.cardId,
                    cardLanguage = candidate.cardLanguage,
                    quantity = quantity,
                    finish = finish,
                    edition = edition,
                    condition = condition,
                    createdAt = clock(),
                ),
            )
            dao.delete(itemId)
            id
        }
        val crop = cropFile(item)
        // Kept in memory, so Undo can put the item back with its picture and the caller can contribute it.
        val cropJpeg = runCatching { crop.readBytes() }.getOrNull()
        crop.delete()
        return ConfirmedReview(copyId, item, candidates, cropJpeg)
    }

    /**
     * Takes back a [confirm]: removes exactly the copy it added and puts the item back where it was in the queue, with
     * its candidates and crop. Nothing changes, and the result is false, when that copy is already gone (deleted
     * elsewhere), so an Undo can never remove a different copy.
     */
    suspend fun undoConfirm(confirmed: ConfirmedReview): Boolean {
        val crop = cropFile(confirmed.item)
        confirmed.cropJpeg?.let { crop.parentFile?.mkdirs(); crop.writeBytes(it) }
        val restored = try {
            db.withTransaction {
                if (db.copyDao().deleteById(confirmed.copyId) == 0) return@withTransaction false
                dao.insertItem(confirmed.item)
                dao.insertCandidates(confirmed.candidates)
                true
            }
        } catch (t: Throwable) {
            if (confirmed.cropJpeg != null) crop.delete()
            throw t
        }
        if (!restored && confirmed.cropJpeg != null) crop.delete()
        return restored
    }

    /**
     * The user found the right card in search because none of the matches was (#129): it becomes the item's hand
     * pick at [PICKED_RANK], after the pipeline's candidates, and replaces an earlier pick. A card that already was a
     * pipeline candidate moves there too (its similarity row goes), so the pick is stored and survives a lost draft
     * (#140, round 4). Returns the rank and whether the item asks for a variant check, or null when the item is gone.
     * The card must be in the catalog (search only finds catalog cards).
     */
    suspend fun pickCard(itemId: Long, cardId: String, language: String): PickedCard? = db.withTransaction {
        val item = dao.item(itemId) ?: return@withTransaction null
        dao.candidates(itemId)
            .filter { it.rank == PICKED_RANK || (it.cardId == cardId && it.cardLanguage == language) }
            .forEach { dao.deleteCandidate(itemId, it.rank) }
        dao.insertCandidates(listOf(ReviewCandidateEntity(itemId, PICKED_RANK, cardId, language, ReviewCandidateEntity.PICKED_BY_HAND)))
        PickedCard(PICKED_RANK, item.checkVariant)
    }

    /** Moves an item to the back of the queue; it stays reachable. False if it is gone. */
    suspend fun skip(itemId: Long): Boolean = dao.requeue(itemId, clock()) > 0

    /** Drops an item that isn't a card worth keeping, with its crop. False if it is gone. */
    suspend fun discard(itemId: Long): Boolean {
        val item = dao.item(itemId) ?: return false
        if (dao.delete(itemId) == 0) return false
        cropFile(item).delete()
        return true
    }

    /** The crop file of an item. */
    fun cropFile(item: ReviewItemEntity): File = File(filesDir, item.cropPath)

    companion object {
        const val MAX_CANDIDATES = 3
        /** The rank of a card picked by hand: after every pipeline candidate. */
        const val PICKED_RANK = MAX_CANDIDATES
        const val CROP_DIR = "review"
    }
}
