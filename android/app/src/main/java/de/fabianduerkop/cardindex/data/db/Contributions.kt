// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * A scan the user contributed to the open dataset (issue #14). Queued with [cropPath] until it is uploaded
 * ([uploadedAt] set). [remoteId] (a UUID v4) and [withdrawToken] are chosen by the app when the scan is queued, so a
 * retried upload is recognised by the server, and kept so the user can withdraw it. Not a foreign key to `cards`: the
 * contribution outlives whatever happens to the catalog row.
 */
@Entity(tableName = "contributions", indices = [Index("remoteId", unique = true)])
data class ContributionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The TCGdex id the user confirmed. */
    val cardId: String,
    /** The language of the scanned print. */
    val language: String,
    /** The metadata-free crop below the files directory while it waits for upload; null once uploaded. */
    val cropPath: String?,
    /** The consent text version the user accepted when the scan was queued. */
    val consentVersion: String,
    val createdAt: Instant,
    val remoteId: String? = null,
    val withdrawToken: String? = null,
    val uploadedAt: Instant? = null,
    /** The user withdrew it; the DELETE is still to be sent. */
    val withdrawRequested: Boolean = false,
) {
    /** The token is a credential: keep it out of logs and crash reports. */
    override fun toString() =
        "ContributionEntity(id=$id, cardId=$cardId, language=$language, remoteId=$remoteId, uploadedAt=$uploadedAt, " +
            "withdrawRequested=$withdrawRequested)"
}

/** A contribution with its card's name and number, when the catalog has the card. */
data class ContributionItem(
    @Embedded val contribution: ContributionEntity,
    val cardName: String?,
    val localId: String?,
)

@Dao
interface ContributionDao {
    @Insert
    suspend fun insert(contribution: ContributionEntity): Long

    @Query("SELECT * FROM contributions WHERE id = :id")
    suspend fun byId(id: Long): ContributionEntity?

    @Query(
        """SELECT co.*, c.name AS cardName, c.localId AS localId FROM contributions co
           LEFT JOIN cards c ON c.id = co.cardId AND c.language = co.language
           ORDER BY co.createdAt DESC, co.id DESC""",
    )
    fun observeAll(): Flow<List<ContributionItem>>

    @Query("SELECT * FROM contributions WHERE uploadedAt IS NULL AND withdrawRequested = 0 ORDER BY id")
    suspend fun pendingUploads(): List<ContributionEntity>

    @Query("SELECT * FROM contributions WHERE uploadedAt IS NOT NULL AND withdrawRequested = 1 ORDER BY id")
    suspend fun pendingWithdrawals(): List<ContributionEntity>

    @Query(
        """UPDATE contributions SET remoteId = :remoteId, withdrawToken = :token, uploadedAt = :at, cropPath = NULL
           WHERE id = :id""",
    )
    suspend fun markUploaded(id: Long, remoteId: String, token: String, at: Instant)

    @Query("UPDATE contributions SET withdrawRequested = 1 WHERE id = :id")
    suspend fun requestWithdrawal(id: Long)

    /** Marks every uploaded contribution for withdrawal; returns how many. */
    @Query("UPDATE contributions SET withdrawRequested = 1 WHERE uploadedAt IS NOT NULL")
    suspend fun requestWithdrawalOfAll(): Int

    @Query("DELETE FROM contributions WHERE id = :id")
    suspend fun delete(id: Long): Int
}
