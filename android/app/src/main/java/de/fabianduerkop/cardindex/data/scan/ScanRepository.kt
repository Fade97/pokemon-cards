// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import androidx.room.withTransaction
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.recognition.RefCard
import java.time.Instant

/**
 * Finish, edition and condition a scanned copy starts with. The camera can't tell any of them reliably, so these are
 * starting values for the user to change, never a detection result. There is no user setting for them yet; they are
 * the same values a copy added by hand starts with.
 */
object CopyDefaults {
    val finish: Finish = Finish.NORMAL
    val edition: Edition = Edition.UNLIMITED
    val condition: Condition = Condition.NEAR_MINT
}

/**
 * Writes confident live-scan hits into All Cards. Each hit is its own copy with the [CopyDefaults] (DESIGN.md,
 * Recognition): finish and edition can't be told reliably from a photo, and merging into an existing copy would make
 * Undo and later edits touch copies the user added by hand.
 */
class ScanRepository(private val db: CardIndexDatabase, private val clock: () -> Instant = Instant::now) {
    /** Adds one copy of [card]; returns its row id for [undo]. */
    suspend fun addFromScan(card: RefCard): Long = db.withTransaction {
        ScanCatalog.ensure(db.catalogDao(), listOf(card))
        db.copyDao().insert(
            OwnedCopyEntity(
                cardId = card.cardId,
                cardLanguage = card.lang,
                finish = CopyDefaults.finish,
                edition = CopyDefaults.edition,
                condition = CopyDefaults.condition,
                createdAt = clock(),
            ),
        )
    }

    /**
     * Sets the finish, edition and condition the user chose for a copy added by [addFromScan]. Touches only that
     * copy; false if it is gone.
     */
    suspend fun setVariant(copyId: Long, finish: Finish, edition: Edition, condition: Condition): Boolean =
        db.withTransaction {
            val copy = db.copyDao().byId(copyId) ?: return@withTransaction false
            db.copyDao().update(copy.copy(finish = finish, edition = edition, condition = condition, updatedAt = clock()))
            true
        }

    /** Removes a copy added by [addFromScan]; false if it is already gone. */
    suspend fun undo(copyId: Long): Boolean = db.copyDao().deleteById(copyId) > 0
}
