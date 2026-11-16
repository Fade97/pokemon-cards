// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import org.json.JSONArray
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
    /** Small card image from the TCGdex CDN. */
    val thumbnailUrl: String get() = "$imageUrl/low.webp"
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
    }
}

/** Indices of the [k] largest values, best first; ties keep the lower index first (like a stable argsort). */
fun topKIndices(values: FloatArray, k: Int): List<Int> =
    values.indices.sortedWith(compareByDescending<Int> { values[it] }.thenBy { it }).take(k)
