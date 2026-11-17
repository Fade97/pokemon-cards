// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.model.CollectionKind
import android.database.sqlite.SQLiteConstraintException
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.EnergyType
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Grader
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.model.Region
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardIndexDatabaseTest {
    private lateinit var db: CardIndexDatabase
    private val catalog get() = db.catalogDao()
    private val copies get() = db.copyDao()
    private val collections get() = db.collectionDao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    /** 151 (international, 165 cards) in EN and DE, and SV2a (JP, 165 cards). */
    private suspend fun seedCatalog() {
        catalog.upsertSets(
            listOf(
                CardSetEntity("sv03.5", "151", "Scarlet & Violet", "en", 165, Region.INTERNATIONAL),
                CardSetEntity("SV2a", "ポケモンカード151", "SV", "ja", 165, Region.JP),
            ),
        )
        fun card(id: String, setId: String, localId: String, lang: String, type: EnergyType) =
            CardEntity(id, setId, localId, "Card $localId", lang, "Common", type, imageUrl = null)
        catalog.upsertCards(
            listOf(
                card("sv03.5-001", "sv03.5", "001", "en", EnergyType.GRASS),
                card("sv03.5-004", "sv03.5", "004", "en", EnergyType.FIRE),
                card("sv03.5-006", "sv03.5", "006", "en", EnergyType.FIRE),
                card("sv03.5-006", "sv03.5", "006", "de", EnergyType.FIRE),
                card("sv03.5-025", "sv03.5", "025", "de", EnergyType.LIGHTNING),
                card("SV2a-006", "SV2a", "006", "ja", EnergyType.FIRE),
            ),
        )
    }

    private var clock = 0L
    private suspend fun own(cardId: String, lang: String, quantity: Int = 1): Long = copies.insert(
        OwnedCopyEntity(
            cardId = cardId,
            cardLanguage = lang,
            quantity = quantity,
            createdAt = Instant.ofEpochMilli(++clock),
        ),
    )

    private fun collection(name: String, kind: CollectionKind) =
        CollectionEntity(name = name, kind = kind, updatedAt = Instant.ofEpochMilli(++clock))

    @Test
    fun setCompletion_countsDistinctNumbersAcrossLanguages() = runTest {
        seedCatalog()
        own("sv03.5-001", "en")
        own("sv03.5-006", "en", quantity = 2)
        own("sv03.5-006", "de") // same number in another language counts once
        own("sv03.5-025", "de")
        own("SV2a-006", "ja")

        val all = copies.observeSetCompletion(listOf("en", "de", "ja")).first()

        assertEquals(listOf("sv03.5", "SV2a"), all.map { it.setId }) // international before JP
        val set151 = all.first()
        assertEquals(3, set151.ownedNumbers) // 001, 006, 025
        assertEquals(165, set151.printedTotal)
        assertEquals(5, set151.copies) // 1 + 2 + 1 + 1
        assertEquals(1, all[1].ownedNumbers)
        assertEquals(Region.JP, all[1].region)
    }

    @Test
    fun setCompletion_languageFilter() = runTest {
        seedCatalog()
        own("sv03.5-001", "en")
        own("sv03.5-006", "de")
        own("sv03.5-025", "de")
        own("SV2a-006", "ja")

        val english = copies.observeSetCompletion(listOf("en")).first()
        assertEquals(1, english.single().ownedNumbers)

        val german = copies.observeSetCompletion(listOf("de")).first()
        assertEquals(2, german.single().ownedNumbers)

        val japanese = copies.observeSetCompletion(listOf("ja")).first()
        assertEquals("SV2a", japanese.single().setId)

        assertEquals(emptyList<SetCompletion>(), copies.observeSetCompletion(listOf("fr")).first())
    }

    @Test
    fun allCopies_joinCardAndSet_newestFirst() = runTest {
        seedCatalog()
        own("sv03.5-001", "en")
        copies.insert(
            OwnedCopyEntity(
                cardId = "sv03.5-006",
                cardLanguage = "de",
                finish = Finish.HOLO,
                edition = Edition.FIRST_EDITION,
                condition = null,
                grader = Grader.PSA,
                grade = "9",
                purchasePriceCents = 8500,
                purchaseCurrency = "EUR",
                purchaseDate = LocalDate.of(2026, 6, 20),
                createdAt = Instant.ofEpochMilli(100),
            ),
        )

        val all = copies.observeAllCopies().first()

        assertEquals(2, all.size)
        val graded = all.first()
        assertEquals("sv03.5-006", graded.card.id)
        assertEquals("de", graded.card.language)
        assertEquals("151", graded.set.name)
        assertEquals(Grader.PSA, graded.copy.grader)
        assertEquals(Finish.HOLO to Edition.FIRST_EDITION, graded.copy.finish to graded.copy.edition)
        assertEquals(null, graded.copy.condition)
        assertEquals(LocalDate.of(2026, 6, 20), graded.copy.purchaseDate)
        assertEquals(Condition.NEAR_MINT, all[1].copy.condition)
    }

    @Test
    fun customCollection_listsItsCopiesAndCounts() = runTest {
        seedCatalog()
        val a = own("sv03.5-001", "en", quantity = 3)
        val b = own("sv03.5-006", "de")
        own("sv03.5-025", "de") // not in the binder
        val binder = collections.insert(collection("Trade binder", kind = CollectionKind.CUSTOM))
        collections.insert(collection("Wishlist", kind = CollectionKind.CUSTOM))
        collections.insert(collection("All cards", kind = CollectionKind.ALL))
        collections.addCopy(CollectionCopyCrossRef(binder, a))
        collections.addCopy(CollectionCopyCrossRef(binder, b))
        collections.addCopy(CollectionCopyCrossRef(binder, b)) // ignored duplicate

        val inBinder = collections.observeCopiesInCollection(binder).first()
        assertEquals(listOf(b, a), inBinder.map { it.copy.id })

        val summaries = collections.observeCustomCollections().first()
        assertEquals(listOf("Trade binder" to 4, "Wishlist" to 0), summaries.map { it.name to it.cards })
    }

    @Test
    fun deletingCopy_removesItFromCollections() = runTest {
        seedCatalog()
        val id = own("sv03.5-001", "en")
        val binder = collections.insert(collection("Binder", kind = CollectionKind.CUSTOM))
        collections.addCopy(CollectionCopyCrossRef(binder, id))

        copies.delete(copies.observeAllCopies().first().single().copy)

        assertEquals(emptyList<CopyWithCard>(), collections.observeCopiesInCollection(binder).first())
    }

    @Test
    fun upsertingCatalog_keepsOwnedCopies() = runTest {
        seedCatalog()
        own("sv03.5-006", "en")

        seedCatalog() // a language-pack update rewrites the same rows

        assertEquals(1, copies.observeAllCopies().first().size)
    }

    @Test
    fun copies_haveStableUniqueUuids() = runTest {
        seedCatalog()
        val first = own("sv03.5-001", "en")
        own("sv03.5-001", "en")
        val stored = copies.observeAllCopies().first().map { it.copy }

        assertNotEquals(stored[0].uuid, stored[1].uuid)
        val byUuid = copies.byUuid(stored.single { it.id == first }.uuid)
        assertEquals(first, byUuid?.id)
        assertEquals(byUuid?.createdAt, byUuid?.updatedAt) // a new copy was last changed when it was created

        // An import that brings the same uuid again must update, not duplicate.
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { copies.insert(stored[0].copy(id = 0)) }
        }
    }

    @Test
    fun cachedPrices_keepEveryPrintingOfACard() = runTest {
        fun price(source: PriceSource, variant: PriceVariant, trend: Long) = CachedPriceEntity(
            cardId = "sv03.5-006",
            source = source,
            variant = variant,
            currency = if (source == PriceSource.CARDMARKET) "EUR" else "USD",
            trend = trend,
            low = null,
            avg = null,
            priceDate = Instant.parse("2026-11-16T02:00:00Z"),
            fetchedAt = Instant.parse("2026-11-16T08:00:00Z"),
        )
        catalog.upsertPrices(
            listOf(
                price(PriceSource.CARDMARKET, PriceVariant.NORMAL, 120),
                price(PriceSource.CARDMARKET, PriceVariant.REVERSE_HOLO, 240),
                price(PriceSource.TCGPLAYER, PriceVariant.NORMAL, 80),
                price(PriceSource.TCGPLAYER, PriceVariant.REVERSE_HOLO, 160),
            ),
        )
        catalog.upsertPrices(listOf(price(PriceSource.CARDMARKET, PriceVariant.NORMAL, 130))) // next night

        val prices = catalog.observePrices("sv03.5-006").first()
            .associate { (it.source to it.variant) to it.trend }
        assertEquals(4, prices.size)
        assertEquals(130L, prices[PriceSource.CARDMARKET to PriceVariant.NORMAL])
        assertEquals(240L, prices[PriceSource.CARDMARKET to PriceVariant.REVERSE_HOLO])
    }

    @Test
    fun retiredCard_keepsItsCopies_andCannotBeDeleted() = runTest {
        seedCatalog()
        own("sv03.5-006", "en")

        catalog.upsertCards(
            listOf(CardEntity("sv03.5-006", "sv03.5", "006", "Card 006", "en", null, null, null, retired = true)),
        )
        val copy = copies.observeAllCopies().first().single()
        assertTrue(copy.card.retired)

        assertThrows(SQLiteConstraintException::class.java) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM cards WHERE id = 'sv03.5-006' AND language = 'en'")
        }
    }
}
