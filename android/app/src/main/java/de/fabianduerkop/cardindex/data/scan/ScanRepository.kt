// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import androidx.room.withTransaction
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.db.ScanSessionCopyEntity
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Printings
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

    /**
     * The finish a copy of a card with these [printings] starts with: the only finish the card was printed in (a
     * holo-only rare, an ex, a full art), else [finish]. Unknown printings (null) and cards printed in several
     * finishes keep [finish]; the user can change it either way (#143).
     */
    fun finishFor(printings: Printings?): Finish = printings?.onlyFinish ?: finish
}

/** A copy [ScanRepository.addHit] made: its row id, and whether it was the first copy of a wanted print. */
data class ScanHit(val copyId: Long, val wishlistFound: Boolean)

/**
 * Writes confident live-scan hits into All Cards, and into the scan target collection if the user chose one (#139).
 * Each hit is its own copy with the [CopyDefaults] (DESIGN.md, Recognition): finish and edition can't be told
 * reliably from a photo, and merging into an existing copy would make Undo and later edits touch copies the user
 * added by hand. A card printed in a single finish gets that finish ([CopyDefaults.finishFor]).
 */

class ScanRepository(private val db: CardIndexDatabase, private val clock: () -> Instant = Instant::now) {
    /**
     * Adds one copy of [card], also to the custom collection [targetUuid] when it still exists (see [ScanTarget]);
     * returns its row id for [undo].
     */
    suspend fun addFromScan(card: RefCard, targetUuid: String? = null): Long = db.withTransaction {
        ScanCatalog.ensure(db.catalogDao(), listOf(card))
        val id = db.copyDao().insert(
            OwnedCopyEntity(
                cardId = card.cardId,
                cardLanguage = card.lang,
                finish = CopyDefaults.finishFor(card.printings),
                edition = CopyDefaults.edition,
                condition = CopyDefaults.condition,
                createdAt = clock(),
            ),
        )
        ScanTarget.addCopy(db, id, targetUuid)
        id
    }

    /**
     * [addFromScan], and whether the copy found a wishlist entry (#162): the print was wanted and not owned before.
     * With a [sessionId] the copy goes into that session's ledger, for its receipt (#163).
     */
    suspend fun addHit(card: RefCard, targetUuid: String? = null, sessionId: String? = null): ScanHit = db.withTransaction {
        val found = db.wishlistDao().isOpenWish(card.cardId, card.lang)
        val id = addFromScan(card, targetUuid)
        if (sessionId != null) db.scanSessionDao().insert(ScanSessionCopyEntity(copyId = id, sessionId = sessionId, addedAt = clock()))
        ScanHit(copyId = id, wishlistFound = found)
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

    /** Removes a copy added by [addFromScan] (and with it its collection membership); false if it is already gone. */
    suspend fun undo(copyId: Long): Boolean = db.copyDao().deleteById(copyId) > 0
}
