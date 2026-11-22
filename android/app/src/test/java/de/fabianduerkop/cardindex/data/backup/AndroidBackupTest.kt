// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.catalog.RoomCatalogSource
import de.fabianduerkop.cardindex.data.collections.CollectionRepository
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.testing.TestClock
import de.fabianduerkop.cardindex.testing.inMemoryDatabase
import de.fabianduerkop.cardindex.testing.seedCatalog
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The collection's Android backup as a JSON snapshot instead of the database (launch review, 2026-11-22). */
@RunWith(AndroidJUnit4::class)
class AndroidBackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = TestClock()
    private lateinit var oldPhone: CardIndexDatabase
    private lateinit var newPhone: CardIndexDatabase

    @Before
    fun setUp() {
        oldPhone = inMemoryDatabase()
        newPhone = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        oldPhone.close()
        newPhone.close()
    }

    private suspend fun addCopy(db: CardIndexDatabase) {
        db.seedCatalog()
        CollectionRepository(db, RoomCatalogSource(db.catalogDao()), clock = clock)
            .saveCopy(OwnedCopyEntity(cardId = "sv03.5-006", cardLanguage = "de", quantity = 1, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH), emptySet())
    }

    @Test
    fun aRestoredSnapshotFillsTheEmptyCollectionOnce() = runTest {
        addCopy(oldPhone)
        val files = tmp.newFolder("files")
        AndroidBackup.writeSnapshot(files, BackupRepository(oldPhone, clock))
        assertTrue(AndroidBackup.snapshotFile(files).length() > 0)

        // The new phone got filesDir/android-backup from the restore, but neither the database nor noBackupFilesDir.
        val noBackup = tmp.newFolder("no_backup")
        val target = BackupRepository(newPhone, clock)
        val empty = suspend { newPhone.copyDao().all().isEmpty() }
        assertTrue(AndroidBackup.restoreIfNeeded(files, noBackup, target, empty))
        assertEquals(1, newPhone.copyDao().all().size)

        // Later starts leave the collection alone, even after the user emptied it.
        newPhone.copyDao().all().forEach { newPhone.copyDao().delete(it) }
        assertFalse(AndroidBackup.restoreIfNeeded(files, noBackup, target, empty))
        assertTrue(newPhone.copyDao().all().isEmpty())
    }

    @Test
    fun aCollectionThatIsNotEmptyIsNeverOverwritten() = runTest {
        addCopy(oldPhone)
        addCopy(newPhone)
        val files = tmp.newFolder("files")
        AndroidBackup.writeSnapshot(files, BackupRepository(oldPhone, clock))
        val noBackup = tmp.newFolder("no_backup")
        assertFalse(AndroidBackup.restoreIfNeeded(files, noBackup, BackupRepository(newPhone, clock)) { newPhone.copyDao().all().isEmpty() })
        assertEquals(1, newPhone.copyDao().all().size)
    }
}
