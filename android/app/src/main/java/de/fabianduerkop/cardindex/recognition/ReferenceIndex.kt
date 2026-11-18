// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import de.fabianduerkop.cardindex.data.images.ImageQuality
import de.fabianduerkop.cardindex.data.images.cardImageUrl
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/** One reference card of the index (TCGdex data, see ml/app_assets.py). */
data class RefCard(
    val key: String,
    val cardId: String,
    val lang: String,
    val setId: String,
    val setName: String,
    val localId: String,
    val official: Int,
    val name: String,
    val imageUrl: String,
    /** True for sets the model never saw in training (only added to the index). */
    val unseen: Boolean,
) {
    /**
     * Small card image from the TCGdex CDN; null when the card has none (`imageUrl` is empty, e.g. set mee, whose
     * index row comes from another reference source the app never loads).
     */
    val thumbnailUrl: String? get() = cardImageUrl(imageUrl, ImageQuality.LOW)
}

/** A candidate card and its cosine similarity to the query. */
data class Match(val index: Int, val card: RefCard, val similarity: Float)

/**
 * The reference index: one L2-normalized embedding per card, brute-force cosine kNN.
 *
 * The rows live in [segments], read one after the other: usually one memory-mapped `index.bin` per language pack
 * ([mapVectors]), so the ~100 MB of seven languages stay off the Java heap (issue #74) and the kernel can drop the
 * clean pages under memory pressure. A heap [FloatArray] works too ([FloatBuffer.wrap], the secondary constructor).
 */
class ReferenceIndex(val cards: List<RefCard>, segments: List<FloatBuffer>, val dim: Int) {
    constructor(cards: List<RefCard>, vectors: FloatArray, dim: Int) : this(cards, listOf(FloatBuffer.wrap(vectors)), dim)

    /** Read-only views starting at row 0; [similarities] reads through duplicates, so calls may run concurrently. */
    private val segments: List<FloatBuffer> = segments.map { it.slice().asReadOnlyBuffer() }

    init {
        require(dim > 0)
        this.segments.forEach { require(it.capacity() % dim == 0) { "segment of ${it.capacity()} floats is not whole rows of $dim" } }
        val rows = this.segments.sumOf { it.capacity().toLong() } / dim
        require(rows == cards.size.toLong()) { "index has $rows rows, cards.json ${cards.size}" }
        // RecognitionResult.margin compares the two best candidates.
        require(cards.size >= 2) { "index needs at least 2 cards, has ${cards.size}" }
    }

    val size: Int get() = cards.size

    /** Languages in the index, e.g. `[de, en, ja]`. */
    val languages: List<String> = cards.map { it.lang }.distinct().sorted()

    /**
     * Cosine similarity of [query] (L2-normalized) to every card. Cards outside [languages] (null = all) get -2, so
     * they never win: the lab's stand-in for installed language packs (ADR 0003).
     *
     * Rows are copied [CHUNK_ROWS] at a time into a small array (one bulk copy, cache-resident) and summed there in
     * the same order as a plain array loop, so the results are bit-identical to it. On talos (107k × 256) this
     * takes 48 ms against 57 ms for the old heap array; element-wise `FloatBuffer.get` on the mapping took 1.3 s
     * (ReferenceIndexBenchmark).
     */
    fun similarities(query: FloatArray, languages: Set<String>? = null): FloatArray {
        require(query.size == dim)
        val out = FloatArray(cards.size)
        val chunk = FloatArray(CHUNK_ROWS * dim)
        var row = 0
        for (segment in segments) {
            val buf = segment.duplicate()
            while (buf.hasRemaining()) {
                val rows = minOf(CHUNK_ROWS, buf.remaining() / dim)
                val wanted = languages == null || (row until row + rows).any { cards[it].lang in languages }
                if (!wanted) {
                    out.fill(-2f, row, row + rows)
                    buf.position(buf.position() + rows * dim)
                    row += rows
                    continue
                }
                buf.get(chunk, 0, rows * dim)
                for (r in 0 until rows) {
                    val i = row + r
                    if (languages != null && cards[i].lang !in languages) {
                        out[i] = -2f
                        continue
                    }
                    var s = 0f
                    val base = r * dim
                    for (j in 0 until dim) s += chunk[base + j] * query[j]
                    out[i] = s
                }
                row += rows
            }
        }
        return out
    }

    fun topK(sims: FloatArray, k: Int): List<Match> = topKIndices(sims, k).map { Match(it, cards[it], sims[it]) }

    companion object {
        fun parseCards(json: String): List<RefCard> {
            val arr = JSONArray(json)
            return List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RefCard(
                    key = o.getString("key"),
                    cardId = o.getString("cardId"),
                    lang = o.getString("lang"),
                    setId = o.getString("setId"),
                    setName = o.getString("setName"),
                    localId = o.getString("localId"),
                    official = o.getInt("official"),
                    name = o.getString("name"),
                    imageUrl = o.getString("imageUrl"),
                    unseen = o.optBoolean("unseen", false),
                )
            }
        }

        /** Reads float32 little-endian rows as written by numpy `astype("<f4").tobytes()`. */
        fun parseVectors(bytes: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buf.remaining()).also { buf.get(it) }
        }

        /** Rows per bulk copy in [similarities]: 64 × 256 floats = 64 KB, small enough to stay in the cache. */
        const val CHUNK_ROWS = 64

        /**
         * Maps a file of float32 little-endian rows read-only, without reading it onto the Java heap. The mapping
         * outlives the channel and a later delete of the file (pack directories are replaced, never rewritten).
         * The pages are faulted in here ([java.nio.MappedByteBuffer.load], on the loading thread), so the first scan
         * does not pay for it (~240 ms for seven languages on talos).
         */
        fun mapVectors(file: File): FloatBuffer {
            require(file.length() % 4 == 0L) { "${file.name}: ${file.length()} bytes is not whole floats" }
            return FileInputStream(file).channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).load().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            }
        }
    }
}

/** Indices of the [k] largest values, best first; ties keep the lower index first (like a stable argsort). */
fun topKIndices(values: FloatArray, k: Int): List<Int> =
    values.indices.sortedWith(compareByDescending<Int> { values[it] }.thenBy { it }).take(k)
