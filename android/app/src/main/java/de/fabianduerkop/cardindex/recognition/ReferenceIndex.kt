// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import de.fabianduerkop.cardindex.data.images.ImageQuality
import de.fabianduerkop.cardindex.data.images.cardImageUrl
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 * The reference index: one L2-normalized embedding per card. Brute-force cosine kNN is fast enough for a few
 * thousand cards (1,028 × 256 is ~0.3 M multiply-adds per query).
 */
class ReferenceIndex(val cards: List<RefCard>, private val vectors: FloatArray, val dim: Int) {
    init {
        require(vectors.size == cards.size * dim) { "index has ${vectors.size / dim} rows, cards.json ${cards.size}" }
        // RecognitionResult.margin compares the two best candidates.
        require(cards.size >= 2) { "index needs at least 2 cards, has ${cards.size}" }
    }

    val size: Int get() = cards.size

    /** Languages in the index, e.g. `[de, en, ja]`. */
    val languages: List<String> = cards.map { it.lang }.distinct().sorted()

    /**
     * Cosine similarity of [query] (L2-normalized) to every card. Cards outside [languages] (null = all) get -2, so
     * they never win: the lab's stand-in for installed language packs (ADR 0003).
     */
    fun similarities(query: FloatArray, languages: Set<String>? = null): FloatArray {
        require(query.size == dim)
        val out = FloatArray(cards.size)
        for (i in cards.indices) {
            if (languages != null && cards[i].lang !in languages) {
                out[i] = -2f
                continue
            }
            var s = 0f
            val base = i * dim
            for (j in 0 until dim) s += vectors[base + j] * query[j]
            out[i] = s
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

        /**
         * Reads a whole file of float32 little-endian rows into [dest] at [offset], through a small buffer instead
         * of a copy of the file.
         */
        fun readVectors(file: File, dest: FloatArray, offset: Int) {
            val floats = file.length() / 4
            require(file.length() % 4 == 0L && offset + floats <= dest.size) { "${file.name}: ${file.length()} bytes don't fit" }
            val buf = ByteBuffer.allocate(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
            var at = offset
            FileInputStream(file).channel.use { ch ->
                while (true) {
                    val n = ch.read(buf)
                    if (n < 0 && buf.position() == 0) break
                    buf.flip()
                    val f = buf.asFloatBuffer()
                    val count = f.remaining()
                    f.get(dest, at, count)
                    at += count
                    buf.position(count * 4)
                    buf.compact()
                    if (n < 0) break
                }
            }
            check(at - offset.toLong() == floats) { "${file.name}: read ${at - offset} floats, expected $floats" }
        }
    }
}

/** Indices of the [k] largest values, best first; ties keep the lower index first (like a stable argsort). */
fun topKIndices(values: FloatArray, k: Int): List<Int> =
    values.indices.sortedWith(compareByDescending<Int> { values[it] }.thenBy { it }).take(k)
