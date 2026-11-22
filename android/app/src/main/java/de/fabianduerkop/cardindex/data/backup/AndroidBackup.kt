// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The collection in the user's Android backup (ADR 0006) as a small JSON snapshot instead of the database. The
 * database is mostly the card catalog (21.8 MB with seven languages on the S25, 2026-11-22), which the language packs
 * rebuild; above Android's 25 MB backup quota the whole backup would be skipped without a word. The snapshot is the
 * Settings → Export JSON, so it holds the copies, collections, wishlist, alerts and the catalog rows they need.
 */
object AndroidBackup {
    private const val TAG = "AndroidBackup"

    /** Under filesDir; `data_extraction_rules.xml` and `backup_rules.xml` back up only this folder of it. */
    const val DIR = "android-backup"
    const val FILE = "collection.json"

    /** In noBackupFilesDir, so a restore never brings it: set once this install has had its chance to restore. */
    private const val CHECKED = "android-backup-checked"

    fun snapshotFile(filesDir: File) = File(File(filesDir, DIR), FILE)

    /** Writes the snapshot atomically: a backup taken meanwhile sees the old file or the new one, never half of one. */
    suspend fun writeSnapshot(filesDir: File, backup: BackupRepository) {
        val target = snapshotFile(filesDir)
        target.parentFile!!.mkdirs()
        val tmp = File(target.parentFile, "$FILE.tmp")
        tmp.outputStream().use { backup.export(BackupFileType.JSON, it) }
        check(tmp.renameTo(target)) { "could not replace $target" }
    }

    /**
     * Once per install: imports the snapshot a restore brought, if the collection is still empty. Later starts do
     * nothing, so a collection the user emptied on purpose never comes back from an old snapshot.
     */
    suspend fun restoreIfNeeded(filesDir: File, noBackupFilesDir: File, backup: BackupRepository, collectionIsEmpty: suspend () -> Boolean): Boolean {
        val checked = File(noBackupFilesDir, CHECKED)
        if (checked.exists()) return false
        val snapshot = snapshotFile(filesDir)
        val imported = if (snapshot.isFile && collectionIsEmpty()) {
            runCatching { snapshot.inputStream().use { backup.import(it) } }
                .onSuccess { Log.i(TAG, "restored from the Android backup: ${it.copiesAdded} copies") }
                .onFailure { Log.e(TAG, "restoring the Android backup snapshot failed", it) }
                .isSuccess
        } else {
            false
        }
        noBackupFilesDir.mkdirs()
        checked.createNewFile()
        return imported
    }
}

/**
 * Writes [AndroidBackup]'s snapshot right before Android's full backup reads the files. Auto Backup stays on
 * (`fullBackupOnly`), with the same include rules; only key/value backup, which this app doesn't use, is empty.
 */
class FoilarkBackupAgent : BackupAgent() {
    override fun onFullBackup(data: FullBackupDataOutput) {
        runCatching {
            runBlocking { AndroidBackup.writeSnapshot(filesDir, BackupRepository(CardIndexDatabase.get(applicationContext))) }
        }.onFailure { Log.e("AndroidBackup", "writing the backup snapshot failed; the last one is backed up", it) }
        super.onFullBackup(data)
    }

    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit
}
