// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.collections

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.catalog.RoomCatalogSource
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Grader
import de.fabianduerkop.cardindex.testing.TestClock
import de.fabianduerkop.cardindex.testing.inMemoryDatabase
import de.fabianduerkop.cardindex.testing.seedCatalog
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionRepositoryTest {
    private lateinit var db: CardIndexDatabase
    private lateinit var repository: CollectionRepository
    private val clock = TestClock()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repository = CollectionRepository(db, RoomCatalogSource(db.catalogDao()), clock = clock)
    }

    @After
    fun tearDown() = db.close()

    private fun copy(cardId: String, language: String, quantity: Int = 1) =
        OwnedCopyEntity(cardId = cardId, cardLanguage = language, quantity = quantity, createdAt = Instant.EPOCH)

    @Test
    fun saveCopy_stampsCreatedAndUpdatedAt_andEditsBumpOnlyUpdatedAt() = runTest {
        db.seedCatalog()
        val id = repository.saveCopy(copy("sv03.5-006", "de"), emptySet())
        val created = db.copyDao().byId(id)!!
        assertEquals(clock.now, created.createdAt)
        assertEquals(clock.now, created.updatedAt)

        clock.advance(60)
        repository.saveCopy(created.copy(quantity = 3), emptySet())

        val edited = db.copyDao().byId(id)!!
        assertEquals(3, edited.quantity)
        assertEquals(created.createdAt, edited.createdAt)
        assertEquals(clock.now, edited.updatedAt)
        assertEquals(created.uuid, edited.uuid)
    }

    @Test
    fun saveCopy_keepsACopyEitherRawOrGraded() = runTest {
        db.seedCatalog()
        val id = repository.saveCopy(
            copy("sv03.5-006", "en").copy(grader = Grader.PSA, grade = " 9 ", condition = Condition.LIGHTLY_PLAYED, quantity = 0),
            emptySet(),
        )
        val graded = db.copyDao().byId(id)!!
        assertNull(graded.condition)
        assertEquals("9", graded.grade)
        assertEquals(1, graded.quantity)

        repository.saveCopy(graded.copy(grader = null, condition = Condition.NEAR_MINT), emptySet())
        val raw = db.copyDao().byId(id)!!
        assertNull(raw.grade)
        assertEquals(Condition.NEAR_MINT, raw.condition)
    }

    @Test
    fun saveCopy_putsTheCopyIntoExactlyTheChosenCollections() = runTest {
        db.seedCatalog()
        val binder = repository.createCollection("Trade binder", null)
        val wishlist = repository.createCollection(" Wishlist ", "  ")
        val id = repository.saveCopy(copy("sv03.5-001", "en", quantity = 2), setOf(binder, wishlist))

        assertEquals(setOf(binder, wishlist), repository.observeCollectionIdsOf(id).first())
        assertEquals(mapOf(id to listOf("Trade binder", "Wishlist")), repository.observeMemberships().first())

        repository.saveCopy(db.copyDao().byId(id)!!, setOf(wishlist))
        assertEquals(setOf(wishlist), repository.observeCollectionIdsOf(id).first())

        val summaries = repository.observeCustomCollections().first()
        assertEquals(listOf("Trade binder" to 0, "Wishlist" to 2), summaries.map { it.name to it.cards })
        assertNull(summaries.single { it.name == "Wishlist" }.description)
    }

    @Test
    fun deleteCollection_keepsItsCopiesInAllCards() = runTest {
        db.seedCatalog()
        val binder = repository.createCollection("Binder", null)
        repository.saveCopy(copy("sv03.5-001", "en"), setOf(binder))

        repository.deleteCollection(binder)

        assertEquals(1, repository.observeAllCopies().first().size)
        assertTrue(repository.observeCustomCollections().first().isEmpty())
    }

    @Test
    fun setCompletion_withoutFilterCoversEveryOwnedLanguage_JpSetsSeparate() = runTest {
        db.seedCatalog()
        repository.saveCopy(copy("sv03.5-006", "en"), emptySet())
        repository.saveCopy(copy("sv03.5-006", "de"), emptySet())
        repository.saveCopy(copy("sv03.5-025", "de"), emptySet())
        repository.saveCopy(copy("SV2a-006", "ja"), emptySet())

        val all = repository.observeSetCompletion(null).first()
        assertEquals(listOf("sv03.5" to 2, "SV2a" to 1), all.map { it.setId to it.ownedNumbers })
        assertEquals(165, all.first().printedTotal)

        val english = repository.observeSetCompletion(setOf("en")).first()
        assertEquals(listOf("sv03.5" to 1), english.map { it.setId to it.ownedNumbers })
        assertEquals(listOf("de", "en", "ja"), repository.observeOwnedLanguages().first())
    }

    @Test
    fun setDetail_showsEverySlotAndCountsOwnedNumbersInTheFilter() = runTest {
        db.seedCatalog()
        repository.saveCopy(copy("sv03.5-006", "de"), emptySet())
        repository.saveCopy(copy("sv03.5-180", "en"), emptySet()) // secret rare above the printed total

        val all = repository.observeSetDetail("sv03.5", null).first()!!
        assertEquals(166, all.slots.size)
        assertEquals(2, all.ownedNumbers)
        assertEquals(listOf("de", "en"), all.languages)
        assertEquals("180", all.slots.last().localId)

        val english = repository.observeSetDetail("sv03.5", setOf("en")).first()!!
        assertEquals(1, english.ownedNumbers)

        assertNull(repository.observeSetDetail("unknown", null).first())
    }

    @Test
    fun cardDetail_picksTheRequestedPrint_andListsCopiesInEveryLanguage() = runTest {
        db.seedCatalog()
        repository.saveCopy(copy("sv03.5-006", "de"), emptySet())
        repository.saveCopy(copy("sv03.5-006", "en", quantity = 2), emptySet())

        val german = repository.observeCardDetail("sv03.5-006", "de").first()!!
        assertEquals("de", german.card.language)
        assertEquals("151", german.set?.name)
        assertEquals(listOf("de", "en"), german.prints.map { it.language })
        assertEquals(3, german.copies.sumOf { it.copy.quantity })

        // A language the catalog lacks falls back to a print it has.
        assertEquals("de", repository.observeCardDetail("sv03.5-006", "fr").first()!!.card.language)
        assertNull(repository.observeCardDetail("nope", "en").first())
    }

    @Test
    fun deleteCopy_removesIt() = runTest {
        db.seedCatalog()
        val id = repository.saveCopy(copy("sv03.5-001", "en"), emptySet())
        repository.deleteCopy(id)
        assertTrue(repository.observeAllCopies().first().isEmpty())
    }

    /** #134: a wanted card is no copy, so it counts nowhere; a copy of that print later marks it found. */
    @Test
    fun wishlist_addsNoCopies_countsOnlyStillWantedPrints_andFindsAnAddedCopy() = runTest {
        db.seedCatalog()
        repository.addToWishlist("sv03.5-025", "de")
        repository.addToWishlist("sv03.5-025", "de") // once per print
        repository.addToWishlist("sv03.5-006", "en")

        assertTrue(repository.observeAllCopies().first().isEmpty())
        assertTrue(repository.observeSetCompletion(null).first().isEmpty())
        assertTrue(repository.observeSetCompletion(setOf("de", "en")).first().isEmpty())
        assertTrue(repository.observeWanted("sv03.5-025", "de").first())
        assertEquals(false, repository.observeWanted("sv03.5-025", "en").first())
        assertEquals(2, repository.observeWishlistCount().first())

        repository.saveCopy(copy("sv03.5-006", "en"), emptySet())
        val list = repository.observeWishlist().first()
        assertEquals(listOf("sv03.5-025" to 0, "sv03.5-006" to 1), list.map { it.card.id to it.owned })
        assertEquals(1, repository.observeWishlistCount().first())
        // The completion counts the copy, not the wanted 025.
        assertEquals(1, repository.observeSetCompletion(null).first().single().ownedNumbers)

        repository.removeFromWishlist("sv03.5-025", "de")
        assertEquals(listOf("sv03.5-006"), repository.observeWishlist().first().map { it.card.id })
        assertEquals(0, repository.observeWishlistCount().first())
    }
}
