// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.fabianduerkop.cardindex.data.alerts.AlertDirection
import de.fabianduerkop.cardindex.data.search.CardSearch
import de.fabianduerkop.cardindex.data.search.SearchTerms
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.model.Printing
import de.fabianduerkop.cardindex.model.Printings
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every exported schema (`android/app/schemas`) must reach the current version through
 * [CardIndexDatabase.MIGRATIONS] and then match the entities. The build rewrites the schema of the current version,
 * so a released version's JSON must not change in a diff: bump [CardIndexDatabase.VERSION] and add a migration.
 */
@RunWith(AndroidJUnit4::class)
class SchemaMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CardIndexDatabase::class.java)

    @Test
    fun everyExportedVersion_migratesToCurrentEntities() {
        for (version in 1..CardIndexDatabase.VERSION) {
            val name = databasePath("migration-from-$version.db")
            helper.createDatabase(name, version).close()

            // Room validates every table, index and foreign key against the entities when it opens the file.
            val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java, name)
                .addMigrations(*CardIndexDatabase.MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase
            db.close()
        }
    }

    /** v1 → v2 (#17) only adds the price-history cache; a user's copies and catalog stay as they were. */
    @Test
    fun migration1To2_keepsCopies_andAddsAnEmptyHistoryCache() {
        val name = databasePath("migration-1-2.db")
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO card_sets (id, name, series, language, printedTotal, region, retired) VALUES ('sv03.5', '151', 'SV', 'en', 165, 'INTERNATIONAL', 0)")
            execSQL("INSERT INTO cards (id, setId, localId, name, language, rarity, energyType, imageUrl, retired) VALUES ('sv03.5-025', 'sv03.5', '025', 'Pikachu', 'en', NULL, NULL, NULL, 0)")
            execSQL(
                "INSERT INTO owned_copies (uuid, cardId, cardLanguage, quantity, finish, edition, condition, createdAt, updatedAt) " +
                    "VALUES ('u-1', 'sv03.5-025', 'en', 2, 'NORMAL', 'UNLIMITED', 'NEAR_MINT', 0, 0)",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, CardIndexDatabase.MIGRATION_1_2).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java, name)
            .addMigrations(*CardIndexDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertEquals(listOf("u-1" to 2), db.copyDao().observeAllCopies().first().map { it.copy.uuid to it.copy.quantity })
                assertEquals(emptyList<PriceHistoryEntity>(), db.priceHistoryDao().observeAll("2000-01").first())
            }
        } finally {
            db.close()
        }
    }

    /** v2 → v3 (#76) only adds the price-alert table; copies and the history cache stay. */
    @Test
    fun migration2To3_keepsCopiesAndHistory_andStoresAlerts() {
        val name = databasePath("migration-2-3.db")
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO card_sets (id, name, series, language, printedTotal, region, retired) VALUES ('sv03.5', '151', 'SV', 'en', 165, 'INTERNATIONAL', 0)")
            execSQL("INSERT INTO cards (id, setId, localId, name, language, rarity, energyType, imageUrl, retired) VALUES ('sv03.5-025', 'sv03.5', '025', 'Pikachu', 'en', NULL, NULL, NULL, 0)")
            execSQL(
                "INSERT INTO owned_copies (uuid, cardId, cardLanguage, quantity, finish, edition, condition, createdAt, updatedAt) " +
                    "VALUES ('u-1', 'sv03.5-025', 'en', 2, 'NORMAL', 'UNLIMITED', 'NEAR_MINT', 0, 0)",
            )
            execSQL(
                "INSERT INTO price_history (cardId, source, variant, month, days, amounts) " +
                    "VALUES ('sv03.5-025', 'CARDMARKET', 'NORMAL', '2026-11', '17,18', '120,125')",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true, CardIndexDatabase.MIGRATION_2_3).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java, name)
            .addMigrations(*CardIndexDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertEquals(listOf("u-1" to 2), db.copyDao().observeAllCopies().first().map { it.copy.uuid to it.copy.quantity })
                assertEquals(listOf("120,125"), db.priceHistoryDao().observeAll("2026-11").first().map { it.amounts })

                val dao = db.priceAlertDao()
                assertEquals(0, dao.count())
                val alert = PriceAlertEntity(
                    uuid = "a-1",
                    cardId = "sv03.5-025",
                    cardLanguage = "en",
                    source = PriceSource.CARDMARKET,
                    variant = PriceVariant.NORMAL,
                    direction = AlertDirection.BELOW,
                    thresholdMinor = 100,
                    currency = "EUR",
                    createdAt = Instant.parse("2026-11-18T08:00:00Z"),
                    triggeredAt = null,
                    armed = true,
                )
                dao.upsert(alert)
                val fired = alert.copy(armed = false, triggeredAt = Instant.parse("2026-11-19T06:00:00Z"))
                dao.update(listOf(fired))
                assertEquals(listOf(fired), dao.all())
                dao.delete("a-1")
                assertEquals(0, dao.count())
            }
        } finally {
            db.close()
        }
    }

    /** v3 → v4 (#124) adds the indexed number key, filled from the printed number; search finds the card by it. */
    @Test
    fun migration3To4_fillsTheNumberKey() {
        val name = databasePath("migration-3-4.db")
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO card_sets (id, name, series, language, printedTotal, region, retired) VALUES ('sv03.5', '151', 'SV', 'en', 165, 'INTERNATIONAL', 0)")
            execSQL("INSERT INTO cards (id, setId, localId, name, language, rarity, energyType, imageUrl, retired) VALUES ('sv03.5-025', 'sv03.5', '025', 'Pikachu', 'en', NULL, NULL, NULL, 0)")
            execSQL("INSERT INTO cards (id, setId, localId, name, language, rarity, energyType, imageUrl, retired) VALUES ('sv03.5-tg05', 'sv03.5', 'tg05', 'Pikachu', 'en', NULL, NULL, NULL, 0)")
            execSQL(
                "INSERT INTO owned_copies (uuid, cardId, cardLanguage, quantity, finish, edition, condition, createdAt, updatedAt) " +
                    "VALUES ('u-1', 'sv03.5-025', 'en', 2, 'NORMAL', 'UNLIMITED', 'NEAR_MINT', 0, 0)",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, CardIndexDatabase.MIGRATION_3_4).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java, name)
            .addMigrations(*CardIndexDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val cards = db.catalogDao().cardsInLanguage("en").associate { it.localId to it.numberKey }
                assertEquals(mapOf("025" to "25", "tg05" to "TG05"), cards)
                val hits = CardSearch(db.catalogDao()).observe(SearchTerms.parse("pikachu 25")!!, setOf("en")).first().hits
                assertEquals(listOf("sv03.5-025" to 2), hits.map { it.card.id to it.owned })
            }
        } finally {
            db.close()
        }
    }

    /** v3 → v4 also adds the review draft (#141); a queued item keeps its place and has no draft yet. */
    @Test
    fun migration3To4_keepsReviewItems_withoutADraft() {
        val name = databasePath("migration-3-4-review.db")
        helper.createDatabase(name, 3).apply {
            execSQL(
                "INSERT INTO review_items (uuid, cropPath, numberRead, reason, checkVariant, createdAt, queuedAt) " +
                    "VALUES ('r-1', 'review/r-1.jpg', '6/165', 'NUMBER_NOT_READ', 1, 0, 5)",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, CardIndexDatabase.MIGRATION_3_4).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java, name)
            .addMigrations(*CardIndexDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val item = db.reviewDao().observeItems().first().single()
                assertEquals("r-1" to Instant.ofEpochMilli(5), item.uuid to item.queuedAt)
                assertEquals(listOf(null, null, null, null, null), listOf(item.draftRank, item.draftFinish, item.draftEdition, item.draftCondition, item.draftQuantity))
                db.reviewDao().saveDraft(item.id, 0, Finish.HOLO, null, null, 2)
                assertEquals(Finish.HOLO, db.reviewDao().item(item.id)!!.draftFinish)
            }
        } finally {
            db.close()
        }
    }

    /** v4 → v5 (#134) only adds the wishlist table; copies stay, and a wanted card adds none. */
    @Test
    fun migration4To5_keepsCopies_andStoresWantedCards() {
        val name = databasePath("migration-4-5.db")
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO card_sets (id, name, series, language, printedTotal, region, retired) VALUES ('sv03.5', '151', 'SV', 'en', 165, 'INTERNATIONAL', 0)")
            execSQL("INSERT INTO cards (id, setId, localId, name, language, rarity, energyType, imageUrl, retired, numberKey) VALUES ('sv03.5-025', 'sv03.5', '025', 'Pikachu', 'en', NULL, NULL, NULL, 0, '25')")
            execSQL(
                "INSERT INTO owned_copies (uuid, cardId, cardLanguage, quantity, finish, edition, condition, createdAt, updatedAt) " +
                    "VALUES ('u-1', 'sv03.5-025', 'en', 2, 'NORMAL', 'UNLIMITED', 'NEAR_MINT', 0, 0)",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, CardIndexDatabase.MIGRATION_4_5).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java, name)
            .addMigrations(*CardIndexDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                db.wishlistDao().insert(WantedCardEntity(cardId = "sv03.5-025", cardLanguage = "en", createdAt = Instant.EPOCH))
                assertEquals(listOf("u-1" to 2), db.copyDao().observeAllCopies().first().map { it.copy.uuid to it.copy.quantity })
                assertEquals(listOf(2), db.wishlistDao().observeWishlist().first().map { it.owned })
            }
        } finally {
            db.close()
        }
    }

    /** v5 → v6 (#143) adds the cards' printings: unknown (null) until the next pack import, then stored. */
    @Test
    fun migration5To6_keepsCards_withUnknownPrintings() {
        val name = databasePath("migration-5-6.db")
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO card_sets (id, name, series, language, printedTotal, region, retired) VALUES ('sv03.5', '151', 'SV', 'en', 165, 'INTERNATIONAL', 0)")
            execSQL("INSERT INTO cards (id, setId, localId, name, language, rarity, energyType, imageUrl, retired, numberKey) VALUES ('sv03.5-006', 'sv03.5', '006', 'Charizard ex', 'en', NULL, NULL, NULL, 0, '6')")
            close()
        }
        helper.runMigrationsAndValidate(name, 6, true, CardIndexDatabase.MIGRATION_5_6).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java, name)
            .addMigrations(*CardIndexDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val card = db.catalogDao().cardsInLanguage("en").single()
                assertEquals("Charizard ex" to null, card.name to card.printings)
                db.catalogDao().upsertCards(listOf(card.copy(printings = Printings.of(listOf(Printing.HOLO)))))
                assertEquals(Finish.HOLO, db.catalogDao().cardsInLanguage("en").single().printings?.onlyFinish)
            }
        } finally {
            db.close()
        }
    }

    /** v6 → v7 (#163) only adds the scan session ledger; copies stay, and deleting one clears its ledger row. */
    @Test
    fun migration6To7_keepsCopies_andTheLedgerFollowsThem() {
        val name = databasePath("migration-6-7.db")
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO card_sets (id, name, series, language, printedTotal, region, retired) VALUES ('sv03.5', '151', 'SV', 'en', 165, 'INTERNATIONAL', 0)")
            execSQL("INSERT INTO cards (id, setId, localId, name, language, rarity, energyType, imageUrl, retired, numberKey, printings) VALUES ('sv03.5-025', 'sv03.5', '025', 'Pikachu', 'en', NULL, NULL, NULL, 0, '25', NULL)")
            execSQL(
                "INSERT INTO owned_copies (id, uuid, cardId, cardLanguage, quantity, finish, edition, condition, createdAt, updatedAt) " +
                    "VALUES (7, 'u-1', 'sv03.5-025', 'en', 1, 'NORMAL', 'UNLIMITED', 'NEAR_MINT', 0, 0)",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true, CardIndexDatabase.MIGRATION_6_7).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java, name)
            .addMigrations(*CardIndexDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                db.scanSessionDao().insert(ScanSessionCopyEntity(copyId = 7, sessionId = "s", addedAt = Instant.EPOCH))
                assertEquals(listOf("u-1"), db.scanSessionDao().observeSession("s").first().map { it.copy.uuid })
                db.copyDao().deleteById(7)
                assertEquals(emptyList<Any>(), db.scanSessionDao().observeSession("s").first())
            }
        } finally {
            db.close()
        }
    }

    /**
     * v7 → v8 (#172) adds the binder cover: an existing binder keeps its name and cards and has no picked cover
     * (the automatic one), and a picked cover is stored and read back.
     */
    @Test
    fun migration7To8_keepsBinders_withTheAutomaticCover() {
        val name = databasePath("migration-7-8.db")
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO card_sets (id, name, series, language, printedTotal, region, retired) VALUES ('sv03.5', '151', 'SV', 'en', 165, 'INTERNATIONAL', 0)")
            execSQL("INSERT INTO cards (id, setId, localId, name, language, rarity, energyType, imageUrl, retired, numberKey, printings) VALUES ('sv03.5-025', 'sv03.5', '025', 'Pikachu', 'en', NULL, NULL, NULL, 0, '25', NULL)")
            execSQL(
                "INSERT INTO owned_copies (id, uuid, cardId, cardLanguage, quantity, finish, edition, condition, createdAt, updatedAt) " +
                    "VALUES (7, 'u-1', 'sv03.5-025', 'en', 2, 'NORMAL', 'UNLIMITED', 'NEAR_MINT', 0, 0)",
            )
            execSQL("INSERT INTO collections (id, uuid, name, description, kind, setId, updatedAt) VALUES (3, 'c-1', 'Trade binder', 'Doubles', 'CUSTOM', NULL, 0)")
            execSQL("INSERT INTO collection_copies (collectionId, copyId) VALUES (3, 7)")
            close()
        }
        helper.runMigrationsAndValidate(name, 8, true, CardIndexDatabase.MIGRATION_7_8).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java, name)
            .addMigrations(*CardIndexDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val binder = db.collectionDao().observeCustomCollections().first().single()
                assertEquals(listOf("Trade binder", "Doubles", 2, null), listOf(binder.name, binder.description, binder.cards, binder.coverCopyUuid))
                assertEquals(listOf(CollectionCopyCrossRef(3, 7)), db.collectionDao().observeAllMemberships().first())

                db.collectionDao().update(db.collectionDao().byId(3)!!.copy(coverCopyUuid = "u-1"))
                assertEquals("u-1", db.collectionDao().observeCustomCollections().first().single().coverCopyUuid)
            }
        } finally {
            db.close()
        }
    }

    /** v8 → v9 (#186): cached prices stay and have no week-ago price until their next refresh writes one. */
    @Test
    fun migration8To9_keepsCachedPrices_withoutAWeekAgoPrice() {
        val name = databasePath("migration-8-9.db")
        helper.createDatabase(name, 8).apply {
            execSQL(
                "INSERT INTO cached_prices (cardId, source, variant, currency, trendMinor, lowMinor, avgMinor, priceDate, fetchedAt, productId) " +
                    "VALUES ('sv03.5-025', 'CARDMARKET', 'NORMAL', 'EUR', 120, NULL, NULL, NULL, 0, 712345)",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 9, true, CardIndexDatabase.MIGRATION_8_9).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java, name)
            .addMigrations(*CardIndexDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val row = db.catalogDao().prices("sv03.5-025").single()
                assertEquals(Triple(120L, null, null), Triple(row.trend, row.weekAgoMinor, row.weekAgoDate))
                db.catalogDao().replacePrices(listOf(row.cardId), listOf(row.copy(weekAgoMinor = 100, weekAgoDate = LocalDate.parse("2026-11-12"))))
                val refreshed = db.catalogDao().prices("sv03.5-025").single()
                assertEquals(100L to LocalDate.parse("2026-11-12"), refreshed.weekAgoMinor to refreshed.weekAgoDate)
            }
        } finally {
            db.close()
        }
    }

    /** v9 → v10 (#241): sets stay and have no code until the next pack import writes one. */
    @Test
    fun migration9To10_keepsSets_withoutACode() {
        val name = databasePath("migration-9-10.db")
        helper.createDatabase(name, 9).apply {
            execSQL("INSERT INTO card_sets (id, name, series, language, printedTotal, region, retired) VALUES ('30th', '30th Celebration', 'MEG', 'en', 128, 'INTERNATIONAL', 0)")
            close()
        }
        helper.runMigrationsAndValidate(name, 10, true, CardIndexDatabase.MIGRATION_9_10).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CardIndexDatabase::class.java, name)
            .addMigrations(*CardIndexDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val set = db.catalogDao().observeSet("30th").first()!!
                assertEquals("30th Celebration" to null, set.name to set.code)
                db.catalogDao().upsertSets(listOf(set.copy(code = "30C")))
                assertEquals("30C", db.catalogDao().observeSet("30th").first()!!.code)
            }
        } finally {
            db.close()
        }
    }

    /** Absolute: with a bare name, Room's driver check fails on Windows hosts (backslash paths). */
    private fun databasePath(name: String): String =
        ApplicationProvider.getApplicationContext<Context>().getDatabasePath(name).absolutePath
}
