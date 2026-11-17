// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import de.fabianduerkop.cardindex.recognition.PackIntegrityException
import de.fabianduerkop.cardindex.recognition.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Result of a conditional catalog request. */
sealed interface CatalogFetch {
    data object NotModified : CatalogFetch

    data class Fetched(val body: String, val etag: String?) : CatalogFetch
}

/**
 * The backend's pack routes (backend/README.md): the catalog with `If-None-Match`, and pack files with `Range`
 * resume into [downloadDir]. Files are cached by their SHA-256, so a key the catalog reuses for other bytes (it never
 * should) can't resume into the wrong file.
 */
class PackHttp(
    private val baseUrl: String,
    private val client: Call.Factory,
    private val downloadDir: File,
) {
    private fun url(path: String) = baseUrl.trimEnd('/') + "/v1/packs/" + path

    suspend fun fetchCatalog(etag: String?): CatalogFetch = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url("catalog.json")).apply { etag?.let { header("If-None-Match", it) } }.build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> CatalogFetch.NotModified
                response.code == 429 -> throw IOException("Pack service rate limit, retry after ${response.header("Retry-After") ?: "60"} s")
                !response.isSuccessful -> throw IOException("Pack catalog: HTTP ${response.code}")
                else -> CatalogFetch.Fetched(response.body!!.string(), response.header("ETag"))
            }
        }
    }

    /**
     * Downloads [file] (or finishes a partial download), checks size and SHA-256 and returns the verified file. A file
     * that doesn't match is deleted and fetched again, at most [MAX_REFETCHES] times; then [PackIntegrityException].
     * Network errors throw [IOException] and keep the partial file for the next attempt.
     */
    suspend fun download(file: PackFile, onProgress: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        downloadDir.mkdirs()
        val target = File(downloadDir, "${file.sha256}-${file.path.substringAfterLast('/')}")
        if (target.isFile && target.length() == file.size && sha256Hex(target) == file.sha256) {
            onProgress(file.size)
            return@withContext target
        }
        target.delete()
        val part = File(target.path + ".part")
        repeat(MAX_REFETCHES + 1) {
            fetchInto(file, part, onProgress)
            if (part.length() == file.size && sha256Hex(part) == file.sha256) {
                if (!part.renameTo(target)) throw IOException("Cannot move ${part.name}")
                return@withContext target
            }
            part.delete()
        }
        throw PackIntegrityException("${file.path} does not match its checksum after ${MAX_REFETCHES + 1} downloads")
    }

    /** Fills [part] up to the catalog size, resuming with `Range` (no `If-Range`: a key's bytes never change). */
    private suspend fun fetchInto(file: PackFile, part: File, onProgress: (Long) -> Unit) {
        if (part.length() > file.size) part.delete()
        var have = part.length()
        if (have == file.size) return
        val request = Request.Builder().url(url(file.path)).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(request).execute().use { response ->
            val append = when (response.code) {
                206 -> true
                200 -> false
                // The partial file is already as long as the server's: let the checksum decide.
                416 -> return
                429 -> throw IOException("Pack service rate limit, retry after ${response.header("Retry-After") ?: "60"} s")
                else -> throw IOException("${file.path}: HTTP ${response.code}")
            }
            if (!append) have = 0
            val body = response.body ?: throw IOException("${file.path}: empty response")
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var sinceReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        have += n
                        sinceReport += n
                        // A server sending more than the catalog says is broken; stop instead of filling the disk.
                        if (have > file.size) break
                        if (sinceReport >= PROGRESS_STEP) {
                            onProgress(have)
                            sinceReport = 0
                        }
                    }
                }
            }
            onProgress(minOf(have, file.size))
        }
    }

    companion object {
        /** docs/packs.md: a file that fails its check is downloaded again at most twice. */
        const val MAX_REFETCHES = 2
        private const val PROGRESS_STEP = 256L * 1024
    }
}
