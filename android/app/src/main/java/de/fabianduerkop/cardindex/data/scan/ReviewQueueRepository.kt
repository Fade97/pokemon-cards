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

/** A queued scan with its crop and its candidates, best first. */
data class ReviewItem(val entity: ReviewItemEntity, val cropFile: File, val candidates: List<ReviewCandidate>)

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
     * Returns the copy's row id.
     */
    suspend fun confirm(
        itemId: Long,
        rank: Int,
        finish: Finish,
        edition: Edition,
        condition: Condition,
        quantity: Int,
    ): Long {
        require(quantity >= 1) { "quantity must be at least 1, was $quantity" }
        lateinit var crop: File
        val copyId = db.withTransaction {
            val item = dao.item(itemId) ?: throw NoSuchElementException("review item $itemId is gone")
            val candidate = dao.candidate(itemId, rank) ?: throw IllegalArgumentException("item $itemId has no candidate $rank")
            crop = cropFile(item)
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
        crop.delete()
        return copyId
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
        const val CROP_DIR = "review"
    }
}
