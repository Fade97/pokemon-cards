// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
            val name = "migration-from-$version.db"
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
}
