// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.fabianduerkop.cardindex.data.alerts.AlertDirection
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import java.time.Instant
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

    /** Absolute: with a bare name, Room's driver check fails on Windows hosts (backslash paths). */
    private fun databasePath(name: String): String =
        ApplicationProvider.getApplicationContext<Context>().getDatabasePath(name).absolutePath
}
