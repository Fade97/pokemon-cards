// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import de.fabianduerkop.cardindex.model.ReviewReason
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

/**
 * A scan the app was unsure about, waiting for the user (#9). It lives here until it is confirmed (it becomes an
 * owned copy) or discarded; skipping only moves it to the back of the queue, so every row is reachable and the
 * badge count is simply the row count. Like copies, it carries a uuid for export and sync.
 */
@Entity(tableName = "review_items", indices = [Index("uuid", unique = true), Index("queuedAt")])
data class ReviewItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    /**
     * The rectified card crop as a JPEG, relative to the app's files directory. Relative, so a restored backup on
     * another device still finds it.
     */
    val cropPath: String,
    /** The collector number as read, e.g. `6/165`; null when OCR found none. */
    val numberRead: String?,
    val reason: ReviewReason,
    /** The variant can't be guessed: Confirm stays blocked until the user picks finish and edition. */
    val checkVariant: Boolean,
    val createdAt: Instant,
    /** Queue order, oldest first. Skip sets it to now, which moves the item to the back. */
    val queuedAt: Instant = createdAt,
)

/** One of the (up to three) cards the user picks from, best first. */
@Entity(
    tableName = "review_candidates",
    primaryKeys = ["itemId", "rank"],
    foreignKeys = [
        ForeignKey(
            entity = ReviewItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["id", "language"],
            childColumns = ["cardId", "cardLanguage"],
        ),
    ],
    indices = [Index("cardId", "cardLanguage")],
)
data class ReviewCandidateEntity(
    val itemId: Long,
    /** 0 = the pipeline's best match after the number rerank. */
    val rank: Int,
    val cardId: String,
    val cardLanguage: String,
    /** Cosine similarity of the crop to this card's reference image. */
    val similarity: Float,
)

/** A candidate with its card and set, for the picker. */
data class ReviewCandidateWithCard(
    val itemId: Long,
    val rank: Int,
    val similarity: Float,
    @Embedded(prefix = "card_") val card: CardEntity,
    @Embedded(prefix = "set_") val set: CardSetEntity,
)

@Dao
interface ReviewDao {
    @Insert
    suspend fun insertItem(item: ReviewItemEntity): Long

    @Insert
    suspend fun insertCandidates(candidates: List<ReviewCandidateEntity>)

    @Query("SELECT * FROM review_items WHERE id = :id")
    suspend fun item(id: Long): ReviewItemEntity?

    @Query("SELECT * FROM review_candidates WHERE itemId = :itemId AND rank = :rank")
    suspend fun candidate(itemId: Long, rank: Int): ReviewCandidateEntity?

    @Query("DELETE FROM review_items WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("UPDATE review_items SET queuedAt = :queuedAt WHERE id = :id")
    suspend fun requeue(id: Long, queuedAt: Instant): Int

    /** The queue, front first; the id breaks ties of items queued in the same millisecond. */
    @Query("SELECT * FROM review_items ORDER BY queuedAt, id")
    fun observeItems(): Flow<List<ReviewItemEntity>>

    @Query("SELECT COUNT(*) FROM review_items")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT rc.itemId, rc.rank, rc.similarity,
            c.id AS card_id, c.setId AS card_setId, c.localId AS card_localId, c.name AS card_name,
            c.language AS card_language, c.rarity AS card_rarity, c.energyType AS card_energyType,
            c.imageUrl AS card_imageUrl, c.retired AS card_retired,
            s.id AS set_id, s.name AS set_name, s.series AS set_series, s.language AS set_language,
            s.printedTotal AS set_printedTotal, s.region AS set_region, s.retired AS set_retired
        FROM review_candidates rc
        JOIN cards c ON c.id = rc.cardId AND c.language = rc.cardLanguage
        JOIN card_sets s ON s.id = c.setId
        ORDER BY rc.itemId, rc.rank
        """,
    )
    fun observeCandidates(): Flow<List<ReviewCandidateWithCard>>
}
