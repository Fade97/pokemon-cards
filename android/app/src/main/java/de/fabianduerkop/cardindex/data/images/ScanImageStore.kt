// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.images

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The user's own photos of their copies, one JPEG per copy named by the copy's uuid in app-private storage. They
 * stand in for the CDN image (ADR 0009), e.g. for JP cards TCGdex has no image of. Keyed by uuid rather than a DB
 * column so the scan flow can store a photo without a schema change. The photos are in no backup: neither the
 * JSON/CSV export nor the Android backup rules include them (README, Backup).
 */
class ScanImageStore(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val uuids = MutableStateFlow<Set<String>?>(null)

    fun fileFor(copyUuid: String): File = File(directory, "$copyUuid.jpg")

    /** Uuids of the copies that have a photo; lets lists pick images without touching the disk per row. */
    val copiesWithPhoto: Flow<Set<String>> = flow {
        if (uuids.value == null) {
            val found = withContext(io) {
                directory.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }.orEmpty()
                    .map { it.name.removeSuffix(".jpg") }.toSet()
            }
            uuids.compareAndSet(null, found)
        }
        emitAll(uuids.filterNotNull())
    }

    /** Copies [source] in as the photo of [copyUuid], replacing an older one. Written to a temp file first. */
    suspend fun save(copyUuid: String, source: InputStream) = withContext(io) {
        directory.mkdirs()
        val target = fileFor(copyUuid)
        val temp = File(directory, "$copyUuid.jpg.tmp")
        source.use { input -> temp.outputStream().use { input.copyTo(it) } }
        check(temp.renameTo(target)) { "Could not store the photo" }
        uuids.update { it?.plus(copyUuid) }
    }

    suspend fun delete(copyUuid: String) = withContext(io) {
        fileFor(copyUuid).delete()
        uuids.update { it?.minus(copyUuid) }
    }
}
