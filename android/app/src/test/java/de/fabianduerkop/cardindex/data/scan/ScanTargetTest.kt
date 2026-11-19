// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.CollectionEntity
import de.fabianduerkop.cardindex.data.settings.AppSettings
import de.fabianduerkop.cardindex.model.CollectionKind
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.Match
import de.fabianduerkop.cardindex.recognition.RefCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Instant

/** Scanning into a chosen collection (#139): live scans and review confirmations, and a deleted target. */
@RunWith(AndroidJUnit4::class)
class ScanTargetTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: CardIndexDatabase
    private lateinit var settings: AppSettings
    private lateinit var choice: ScanTargetChoice
    private lateinit var scans: ScanRepository
    private lateinit var queue: ReviewQueueRepository
    private val now = { Instant.ofEpochMilli(1_000) }

    private fun ref(localId: String) = RefCard(
        key = "de/sv03.5-$localId", cardId = "sv03.5-$localId", lang = "de", setId = "sv03.5", setName = "151",
        localId = localId, official = 165, name = "Card $localId", imageUrl = "https://assets.tcgdex.net/x", unseen = false,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, CardIndexDatabase::class.java).allowMainThreadQueries().build()
        settings = AppSettings(context)
        choice = ScanTargetChoice(db, settings)
        scans = ScanRepository(db, now)
        queue = ReviewQueueRepository(db, tmp.root, now)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun custom(name: String): CollectionEntity {
        val id = db.collectionDao().insert(CollectionEntity(name = name, kind = CollectionKind.CUSTOM, updatedAt = now()))
        return db.collectionDao().byId(id)!!
    }

    private suspend fun membersOf(collection: CollectionEntity): List<Long> =
        db.collectionDao().observeCopiesInCollection(collection.id).first().map { it.copy.id }

    @Test
    fun byDefaultAScanGoesIntoAllCardsOnly() = runTest {
        val binder = custom("Binder")
        assertNull(choice.uuid)
        scans.addFromScan(ref("006"), choice.uuid)
        assertEquals(1, db.copyDao().observeAllCopies().first().size)
        assertTrue(membersOf(binder).isEmpty())
        assertNull(choice.observe().first())
    }

    @Test
    fun aLiveScanGoesIntoAllCardsAndTheChosenCollectionAndUndoTakesBothBack() = runTest {
        val binder = custom("Binder")
        val other = custom("Trade")
        choice.choose(binder.id)
        assertEquals(binder.uuid, choice.uuid)
        assertEquals("Binder", choice.observe().first()?.name)

        val id = scans.addFromScan(ref("006"), choice.uuid)
        assertEquals(listOf(id), db.copyDao().observeAllCopies().first().map { it.copy.id })
        assertEquals(listOf(id), membersOf(binder))
        assertTrue(membersOf(other).isEmpty())

        assertTrue(scans.undo(id))
        assertTrue(membersOf(binder).isEmpty())
    }

    @Test
    fun aReviewConfirmationGoesIntoTheCollectionChosenWhenConfirming() = runTest {
        val binder = custom("Binder")
        val item = queue.enqueue(byteArrayOf(1), listOf(Match(0, ref("006"), 0.6f)), null, ReviewReason.NUMBER_NOT_READ, false)
        choice.choose(binder.id) // chosen after the card was queued
        val confirmed = queue.confirm(item, 0, Finish.NORMAL, Edition.UNLIMITED, Condition.NEAR_MINT, 1, choice.uuid)
        assertEquals(listOf(confirmed.copyId), membersOf(binder))

        assertTrue(queue.undoConfirm(confirmed))
        assertTrue(membersOf(binder).isEmpty())
        assertEquals(1, queue.observeCount().first())
    }

    @Test
    fun aDeletedTargetFallsBackToAllCardsAndClearsTheSetting() = runTest {
        val binder = custom("Binder")
        choice.choose(binder.id)
        db.collectionDao().delete(binder)

        // Saving still works, into All Cards only.
        scans.addFromScan(ref("006"), choice.uuid)
        val item = queue.enqueue(byteArrayOf(1), listOf(Match(0, ref("025"), 0.6f)), null, ReviewReason.NUMBER_NOT_READ, false)
        queue.confirm(item, 0, Finish.NORMAL, Edition.UNLIMITED, Condition.NEAR_MINT, 1, choice.uuid)
        assertEquals(2, db.copyDao().observeAllCopies().first().size)
        assertTrue(db.collectionDao().observeMemberships().first().isEmpty())

        // The chip shows All Cards and the setting forgets the collection.
        assertNull(choice.observe().first())
        assertNull(choice.uuid)
    }

    @Test
    fun onlyCustomCollectionsCanBeTheTarget() = runTest {
        val all = db.collectionDao().byId(
            db.collectionDao().insert(CollectionEntity(name = "All", kind = CollectionKind.ALL, updatedAt = now())),
        )!!
        choice.choose(all.id)
        assertNull(choice.uuid)
        choice.choose(4711)
        assertNull(choice.uuid)

        // A stored uuid of a non-custom collection (an old or hand-edited setting) is ignored too.
        scans.addFromScan(ref("006"), all.uuid)
        assertTrue(db.collectionDao().observeMemberships().first().isEmpty())
    }

    @Test
    fun clearingOnlyForgetsTheCollectionItWasAskedFor() = runTest {
        val binder = custom("Binder")
        val trade = custom("Trade")
        choice.choose(trade.id)
        settings.clearScanTarget(binder.uuid) // a stale clear must not drop a newer choice
        assertEquals(trade.uuid, choice.uuid)
        choice.choose(null)
        assertNull(choice.uuid)
    }
}
