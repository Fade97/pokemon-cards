// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.ReviewCandidateEntity
import de.fabianduerkop.cardindex.data.db.WantedCardEntity
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Printings
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.Match
import de.fabianduerkop.cardindex.recognition.NumberReading
import de.fabianduerkop.cardindex.recognition.RefCard
import de.fabianduerkop.cardindex.ui.review.ReviewDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ScanRepositoriesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: CardIndexDatabase
    private var clock = 1_000L
    private val now = { Instant.ofEpochMilli(clock) }
    private lateinit var scans: ScanRepository
    private lateinit var queue: ReviewQueueRepository

    private fun ref(localId: String, lang: String = "de", set: String = "sv03.5", printings: List<String>? = null) = RefCard(
        key = "$lang/$set-$localId", cardId = "$set-$localId", lang = lang, setId = set, setName = "151",
        localId = localId, official = 165, name = "Card $localId", imageUrl = "https://assets.tcgdex.net/x", unseen = false,
        printings = printings?.let(Printings::ofKeys),
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scans = ScanRepository(db, now)
        queue = ReviewQueueRepository(db, tmp.root, now)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun theSessionLedgerKeepsEveryHit_andLosesUndoneAndDeletedCopies() = runTest {
        val first = scans.addHit(ref("006"), sessionId = "s1").copyId
        val second = scans.addHit(ref("007"), sessionId = "s1").copyId
        val third = scans.addHit(ref("008"), sessionId = "s1").copyId
        scans.addHit(ref("009"), sessionId = "s2")
        scans.addFromScan(ref("010"))

        assertEquals(setOf(first, second, third), db.scanSessionDao().observeSession("s1").first().map { it.copy.id }.toSet())
        assertTrue(scans.undo(third))
        db.copyDao().deleteById(first)
        assertEquals(listOf(second), db.scanSessionDao().observeSession("s1").first().map { it.copy.id })
    }

    @Test
    fun aScanFindsAWishlistEntryOnlyWithTheFirstCopyOfThatPrint() = runTest {
        // A wishlist entry points at a catalog print, so the card has to be known first.
        ScanCatalog.ensure(db.catalogDao(), listOf(ref("006")))
        db.wishlistDao().insert(WantedCardEntity(cardId = "sv03.5-006", cardLanguage = "de", createdAt = Instant.EPOCH))

        assertTrue(scans.addHit(ref("006")).wishlistFound)
        // Owned now: a second copy finds nothing, and neither does the English print, which wasn't wanted.
        assertFalse(scans.addHit(ref("006")).wishlistFound)
        assertFalse(scans.addHit(ref("006", lang = "en")).wishlistFound)
    }

    @Test
    fun scannedCardIsAddedWithDefaultsAndUndoRemovesIt() = runTest {
        val id = scans.addFromScan(ref("006"))
        val copies = db.copyDao().observeAllCopies().first()
        assertEquals(1, copies.size)
        with(copies.single()) {
            assertEquals("sv03.5-006", copy.cardId)
            assertEquals("de", copy.cardLanguage)
            assertEquals(1, copy.quantity)
            assertEquals(Finish.NORMAL, copy.finish)
            assertEquals(Edition.UNLIMITED, copy.edition)
            assertEquals(Condition.NEAR_MINT, copy.condition)
            assertEquals("Card 006", card.name)
            assertEquals(Region.INTERNATIONAL, set.region)
        }
        assertTrue(scans.undo(id))
        assertFalse(scans.undo(id))
        assertEquals(0, db.copyDao().observeAllCopies().first().size)
    }

    @Test
    fun aCardPrintedInOneFinishIsAddedWithThatFinish_othersKeepTheDefault() = runTest {
        scans.addFromScan(ref("006", printings = listOf("holo")))
        scans.addFromScan(ref("199", printings = listOf("reverse")))
        // 1st edition is a print run, not a finish: a holo-only WotC rare is still holo.
        scans.addFromScan(ref("004", set = "base1", printings = listOf("holo", "firstEdition")))
        scans.addFromScan(ref("001", printings = listOf("normal", "reverse")))
        scans.addFromScan(ref("002", printings = listOf("reverse", "holo")))
        scans.addFromScan(ref("003", printings = null))
        val finishes = db.copyDao().observeAllCopies().first().associate { it.copy.cardId to it.copy.finish }
        assertEquals(
            mapOf(
                "sv03.5-006" to Finish.HOLO, "sv03.5-199" to Finish.REVERSE_HOLO, "base1-004" to Finish.HOLO,
                "sv03.5-001" to Finish.NORMAL, "sv03.5-002" to Finish.NORMAL, "sv03.5-003" to Finish.NORMAL,
            ),
            finishes,
        )
        // The gap-filling catalog row keeps the printings too, and the finish stays editable.
        val id = db.copyDao().observeAllCopies().first().first { it.copy.cardId == "sv03.5-006" }.copy.id
        assertTrue(scans.setVariant(id, Finish.REVERSE_HOLO, Edition.UNLIMITED, Condition.NEAR_MINT))
        assertEquals(Finish.REVERSE_HOLO, db.copyDao().byId(id)!!.finish)
    }

    @Test
    fun aReviewCandidatePrintedInOneFinishStartsTheDraftWithIt() = runTest {
        queue.enqueue(
            byteArrayOf(9),
            listOf(Match(0, ref("006", printings = listOf("holo")), 0.6f), Match(1, ref("001", printings = listOf("normal", "reverse")), 0.5f)),
            reading = null,
            reason = ReviewReason.NUMBER_NOT_READ,
            checkVariant = false,
        )
        val candidates = queue.observeItems().first().single().candidates.associate { it.rank to it.card.printings }
        val draft = ReviewDraft.forItem(checkVariant = false)
        assertEquals(Finish.HOLO, draft.forCard(candidates[0]).finish)
        assertEquals(Finish.NORMAL, draft.copy(rank = 1).forCard(candidates[1]).finish)
    }

    @Test
    fun scanningTheSameCardTwiceMakesTwoCopies() = runTest {
        scans.addFromScan(ref("006"))
        scans.addFromScan(ref("006"))
        assertEquals(2, db.copyDao().observeAllCopies().first().size)
    }

    @Test
    fun catalogDataFromLanguagePacksIsNotOverwritten() = runTest {
        db.catalogDao().upsertSets(listOf(CardSetEntity("sv03.5", "Pokémon 151", "Scarlet & Violet", "en", 165, Region.INTERNATIONAL)))
        scans.addFromScan(ref("006"))
        val set = db.copyDao().observeAllCopies().first().single().set
        assertEquals("Pokémon 151", set.name)
        assertEquals("Scarlet & Violet", set.series)
    }

    @Test
    fun enqueueStoresCropTopThreeCandidatesAndCounts() = runTest {
        val crop = byteArrayOf(1, 2, 3)
        val candidates = listOf(
            Match(0, ref("006"), 0.71f),
            Match(1, ref("006", lang = "en"), 0.70f),
            Match(2, ref("199"), 0.40f),
            Match(3, ref("025"), 0.30f),
        )
        val id = queue.enqueue(crop, candidates, NumberReading("6", "165"), ReviewReason.SIMILAR_CARDS, checkVariant = false)

        val item = db.reviewDao().item(id)!!
        assertEquals("6/165", item.numberRead)
        assertEquals(ReviewReason.SIMILAR_CARDS, item.reason)
        assertFalse(item.cropPath.startsWith("/"))
        assertArrayEquals(crop, queue.cropFile(item).readBytes())
        val stored = db.reviewDao().observeCandidates().first()
        assertEquals(listOf("de/sv03.5-006", "en/sv03.5-006", "de/sv03.5-199"), stored.map { "${it.card.language}/${it.card.id}" })
        assertEquals(1, queue.observeCount().first())
    }

    private suspend fun enqueueOne(localId: String = "006", checkVariant: Boolean = false): Long = queue.enqueue(
        byteArrayOf(9),
        listOf(Match(0, ref(localId), 0.6f), Match(1, ref(localId, lang = "en"), 0.59f)),
        reading = null,
        reason = ReviewReason.NUMBER_NOT_READ,
        checkVariant = checkVariant,
    )

    @Test
    fun confirmSavesTheChosenCandidateVariantConditionAndQuantity() = runTest {
        val id = enqueueOne()
        val crop = queue.cropFile(db.reviewDao().item(id)!!)
        assertTrue(crop.exists())

        queue.confirm(id, rank = 1, Finish.REVERSE_HOLO, Edition.FIRST_EDITION, Condition.LIGHTLY_PLAYED, quantity = 3)

        val copy = db.copyDao().observeAllCopies().first().single().copy
        assertEquals("en", copy.cardLanguage)
        assertEquals(Finish.REVERSE_HOLO, copy.finish)
        assertEquals(Edition.FIRST_EDITION, copy.edition)
        assertEquals(Condition.LIGHTLY_PLAYED, copy.condition)
        assertEquals(3, copy.quantity)
        assertEquals(0, queue.observeCount().first())
        assertTrue(queue.observeItems().first().isEmpty())
        assertTrue(db.reviewDao().observeCandidates().first().isEmpty())
        assertFalse(crop.exists())
    }

    @Test
    fun aCardPickedInSearchBecomesACandidateReplacesAnEarlierPickAndCanBeConfirmed() = runTest {
        val id = enqueueOne(checkVariant = true)
        ScanCatalog.ensure(db.catalogDao(), listOf(ref("025"), ref("026")))

        // A pipeline match found in search moves to the hand-pick slot, so the choice is stored (#140).
        assertEquals(PickedCard(rank = 3, checkVariant = true), queue.pickCard(id, "sv03.5-006", "en"))
        var candidates = queue.observeItems().first().single().candidates
        assertEquals(listOf(0, 3), candidates.map { it.rank })
        assertEquals("en", candidates.last().card.language)
        assertEquals(PickedCard(rank = 3, checkVariant = true), queue.pickCard(id, "sv03.5-025", "de"))
        assertEquals(PickedCard(rank = 3, checkVariant = true), queue.pickCard(id, "sv03.5-026", "de")) // #129: replaces 025
        candidates = queue.observeItems().first().single().candidates
        assertEquals(listOf(0, 3), candidates.map { it.rank })
        assertEquals("sv03.5-026", candidates.last().card.id)
        assertEquals(ReviewCandidateEntity.PICKED_BY_HAND, candidates.last().similarity)

        queue.confirm(id, rank = 3, Finish.HOLO, Edition.UNLIMITED, Condition.NEAR_MINT, quantity = 1)
        assertEquals("sv03.5-026", db.copyDao().observeAllCopies().first().single().copy.cardId)
        assertEquals(null, queue.pickCard(id, "sv03.5-025", "de")) // the item is gone
    }

    /** #141: the draft lives on the item, so a new screen (or a new process) reads it back. */
    @Test
    fun theDraftIsStoredOnTheItem_survivesSkip_andAPickInSearchSetsItsRank() = runTest {
        val id = enqueueOne(checkVariant = true)
        assertEquals(null, db.reviewDao().item(id)!!.draftRank)

        assertTrue(queue.saveDraft(id, rank = 1, Finish.HOLO, edition = null, Condition.POOR, quantity = 4))
        queue.skip(id)
        with(queue.observeItems().first().single().entity) {
            assertEquals(1, draftRank)
            assertEquals(Finish.HOLO, draftFinish)
            assertEquals(null, draftEdition)
            assertEquals(Condition.POOR, draftCondition)
            assertEquals(4, draftQuantity)
        }

        ScanCatalog.ensure(db.catalogDao(), listOf(ref("025")))
        queue.pickCard(id, "sv03.5-025", "de")
        with(db.reviewDao().item(id)!!) {
            assertEquals(ReviewQueueRepository.PICKED_RANK, draftRank)
            assertEquals(Finish.HOLO, draftFinish) // the other choices stay
        }

        queue.confirm(id, rank = 3, Finish.HOLO, Edition.UNLIMITED, Condition.POOR, quantity = 4)
        assertFalse(queue.saveDraft(id, rank = 0, null, null, null, null)) // the item is gone
    }

    @Test
    fun confirmOfAGoneItemOrUnknownCandidateAddsNothing() = runTest {
        val id = enqueueOne()
        val unknownRank = runCatching { queue.confirm(id, 7, Finish.NORMAL, Edition.UNLIMITED, Condition.NEAR_MINT, 1) }
        assertTrue(unknownRank.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, queue.observeCount().first())
        queue.discard(id)
        val gone = runCatching { queue.confirm(id, 0, Finish.NORMAL, Edition.UNLIMITED, Condition.NEAR_MINT, 1) }
        assertTrue(gone.exceptionOrNull() is NoSuchElementException)
        assertEquals(0, db.copyDao().observeAllCopies().first().size)
    }

    @Test
    fun chosenVariantIsSavedOnThatCopyOnly() = runTest {
        val first = scans.addFromScan(ref("006"))
        val second = scans.addFromScan(ref("006"))
        clock += 5
        assertTrue(scans.setVariant(second, Finish.HOLO, Edition.FIRST_EDITION, Condition.LIGHTLY_PLAYED))
        val one = db.copyDao().byId(first)!!
        val two = db.copyDao().byId(second)!!
        assertEquals(Finish.NORMAL, one.finish)
        assertEquals(Finish.HOLO, two.finish)
        assertEquals(Edition.FIRST_EDITION, two.edition)
        assertEquals(Condition.LIGHTLY_PLAYED, two.condition)
        assertEquals(Instant.ofEpochMilli(clock), two.updatedAt)
        scans.undo(second)
        assertFalse(scans.setVariant(second, Finish.HOLO, Edition.UNLIMITED, Condition.NEAR_MINT))
    }

    @Test
    fun undoOfAConfirmationRemovesItsCopyAndPutsTheItemBackInPlace() = runTest {
        val handAdded = scans.addFromScan(ref("025"))
        val first = enqueueOne("001")
        clock += 1
        val second = enqueueOne("002")
        val crop = queue.cropFile(db.reviewDao().item(first)!!)

        val confirmed = queue.confirm(first, rank = 1, Finish.HOLO, Edition.UNLIMITED, Condition.NEAR_MINT, quantity = 2)
        assertArrayEquals(byteArrayOf(9), confirmed.cropJpeg)
        assertFalse(crop.exists())
        assertEquals(2, db.copyDao().observeAllCopies().first().size)

        assertTrue(queue.undoConfirm(confirmed))
        // Back at the front, with both candidates and its crop; only the confirmed copy is gone.
        val items = queue.observeItems().first()
        assertEquals(listOf(first, second), items.map { it.entity.id })
        assertEquals(listOf(0, 1), items.first().candidates.map { it.rank })
        assertArrayEquals(byteArrayOf(9), crop.readBytes())
        assertEquals(listOf(handAdded), db.copyDao().observeAllCopies().first().map { it.copy.id })

        // A second Undo of the same confirmation changes nothing, and can't remove another copy.
        assertFalse(queue.undoConfirm(confirmed))
        assertEquals(2, queue.observeCount().first())
        assertEquals(listOf(handAdded), db.copyDao().observeAllCopies().first().map { it.copy.id })

        // The restored item can be confirmed again.
        queue.confirm(first, rank = 0, Finish.NORMAL, Edition.UNLIMITED, Condition.NEAR_MINT, quantity = 1)
        assertEquals(1, queue.observeCount().first())
    }

    @Test
    fun undoOfAConfirmationWhoseCopyWasDeletedLeavesTheQueueAlone() = runTest {
        val id = enqueueOne()
        val confirmed = queue.confirm(id, 0, Finish.NORMAL, Edition.UNLIMITED, Condition.NEAR_MINT, 1)
        db.copyDao().deleteById(confirmed.copyId)
        assertFalse(queue.undoConfirm(confirmed))
        assertEquals(0, queue.observeCount().first())
        assertFalse(queue.cropFile(confirmed.item).exists())
    }

    @Test
    fun skipMovesTheItemToTheBackAndKeepsItCounted() = runTest {
        val first = enqueueOne("001")
        clock += 1
        val second = enqueueOne("002")
        assertEquals(listOf(first, second), queue.observeItems().first().map { it.entity.id })

        clock += 1
        assertTrue(queue.skip(first))
        val items = queue.observeItems().first()
        assertEquals(listOf(second, first), items.map { it.entity.id })
        // The badge counts every item the review screen can reach, skipped ones included.
        assertEquals(items.size, queue.observeCount().first())
        assertEquals(2, items.first().candidates.size)
    }

    @Test
    fun discardRemovesItemAndCrop() = runTest {
        val id = enqueueOne(checkVariant = true)
        val item = queue.observeItems().first().single()
        assertTrue(item.entity.checkVariant)
        assertTrue(queue.discard(id))
        assertFalse(item.cropFile.exists())
        assertFalse(queue.discard(id))
        assertEquals(0, queue.observeCount().first())
    }
}
