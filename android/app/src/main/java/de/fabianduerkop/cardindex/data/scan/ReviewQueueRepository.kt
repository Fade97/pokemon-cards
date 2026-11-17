// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import androidx.room.withTransaction
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.ReviewCandidateEntity
import de.fabianduerkop.cardindex.data.db.ReviewItemEntity
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.Match
import de.fabianduerkop.cardindex.recognition.NumberReading
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.Instant
import java.util.UUID

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

    /** The crop file of an item. */
    fun cropFile(item: ReviewItemEntity): File = File(filesDir, item.cropPath)

    companion object {
        const val MAX_CANDIDATES = 3
        const val CROP_DIR = "review"
    }
}
