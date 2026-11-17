// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import androidx.room.withTransaction
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.recognition.RefCard
import java.time.Instant

/**
 * Writes confident live-scan hits into All Cards. Each hit is its own copy with the default variant and condition
 * (DESIGN.md, Recognition): finish and edition can't be told reliably from a photo, and merging into an existing
 * copy would make Undo and later edits touch copies the user added by hand.
 */
class ScanRepository(private val db: CardIndexDatabase, private val clock: () -> Instant = Instant::now) {
    /** Adds one copy of [card]; returns its row id for [undo]. */
    suspend fun addFromScan(card: RefCard): Long = db.withTransaction {
        ScanCatalog.ensure(db.catalogDao(), listOf(card))
        db.copyDao().insert(OwnedCopyEntity(cardId = card.cardId, cardLanguage = card.lang, createdAt = clock()))
    }

    /** Removes a copy added by [addFromScan]; false if it is already gone. */
    suspend fun undo(copyId: Long): Boolean = db.copyDao().deleteById(copyId) > 0
}
