// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.Match
import de.fabianduerkop.cardindex.recognition.NumberReading
import de.fabianduerkop.cardindex.recognition.RefCard
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

    private fun ref(localId: String, lang: String = "de", set: String = "sv03.5") = RefCard(
        key = "$lang/$set-$localId", cardId = "$set-$localId", lang = lang, setId = set, setName = "151",
        localId = localId, official = 165, name = "Card $localId", imageUrl = "https://assets.tcgdex.net/x", unseen = false,
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
}
