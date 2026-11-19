// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * The session ledger (#163): which copies a scanning session added. A row lives exactly as long as its copy (CASCADE),
 * so Undo and a later deletion in Collections take a card off the session receipt without any bookkeeping, and a
 * finish or edition changed later is what the receipt prices. Unlike the Scan tray's Undo history it has no limit.
 */
@Entity(
    tableName = "scan_session_copies",
    foreignKeys = [
        ForeignKey(
            entity = OwnedCopyEntity::class,
            parentColumns = ["id"],
            childColumns = ["copyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ScanSessionCopyEntity(
    @PrimaryKey val copyId: Long,
    /** A random id per scanning session (one visit of the Scan screen). */
    val sessionId: String,
    val addedAt: Instant,
)
