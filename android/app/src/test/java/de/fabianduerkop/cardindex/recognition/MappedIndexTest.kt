// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** The index read through memory-mapped `index.bin` files (issue #74) against a plain array loop. */
class MappedIndexTest {
    private fun file(values: FloatArray): File {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { bytes.putFloat(it) }
        return File.createTempFile("index", ".bin").apply { deleteOnExit(); writeBytes(bytes.array()) }
    }

    private fun card(i: Int, lang: String) = RefCard("$lang/$i", "$i", lang, "s", "S", "$i", 10, "c$i", "", false)

    private fun normalized(rnd: Random, n: Int): FloatArray {
        val v = FloatArray(n) { rnd.nextFloat() * 2 - 1 }
        val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(n) { v[it] / norm }
    }

    /** What ReferenceIndex.similarities did on one FloatArray before #74. */
    private fun reference(vectors: FloatArray, cards: List<RefCard>, dim: Int, q: FloatArray, langs: Set<String>?) =
        FloatArray(cards.size) { i ->
            if (langs != null && cards[i].lang !in langs) -2f
            else { var s = 0f; for (j in 0 until dim) s += vectors[i * dim + j] * q[j]; s }
        }

    @Test
    fun mapVectorsReadsLikeParseVectors() {
        val values = FloatArray(300_001) { it * 0.5f - 7f }
        val f = file(values)
        val mapped = ReferenceIndex.mapVectors(f)
        val out = FloatArray(mapped.remaining()).also { mapped.get(it) }
        assertArrayEquals(ReferenceIndex.parseVectors(f.readBytes()), out, 0f)
    }

    @Test
    fun mappedSegmentsGiveBitIdenticalSimilarities() {
        val dim = 16
        val rnd = Random(74)
        // Row counts around the chunk size, so chunks end inside and at the end of segments.
        val langs = listOf("de" to ReferenceIndex.CHUNK_ROWS * 2 + 7, "en" to 1, "ja" to ReferenceIndex.CHUNK_ROWS, "pt" to 130)
        val cards = langs.flatMap { (lang, n) -> List(n) { card(it, lang) } }
        val perLang = langs.map { (_, n) -> FloatArray(n * dim).also { a -> for (r in 0 until n) normalized(rnd, dim).copyInto(a, r * dim) } }
        val all = perLang.reduce { a, b -> a + b }
        val mapped = ReferenceIndex(cards, perLang.map { ReferenceIndex.mapVectors(file(it)) }, dim)
        val heap = ReferenceIndex(cards, all, dim)
        repeat(5) {
            val q = normalized(rnd, dim)
            for (filter in listOf(null, setOf("en"), setOf("de", "pt"), setOf("ja"), emptySet())) {
                val expected = reference(all, cards, dim, q, filter)
                assertArrayEquals("filter $filter", expected, mapped.similarities(q, filter), 0f)
                assertArrayEquals("filter $filter", expected, heap.similarities(q, filter), 0f)
            }
        }
    }

    @Test
    fun repeatedAndConcurrentCallsAgree() {
        val dim = 8
        val rnd = Random(1)
        val cards = List(500) { card(it, "en") }
        val v = FloatArray(cards.size * dim) { rnd.nextFloat() }
        val index = ReferenceIndex(cards, listOf(ReferenceIndex.mapVectors(file(v))), dim)
        val q = normalized(rnd, dim)
        val first = index.similarities(q)
        val results = List(4) { arrayOfNulls<FloatArray>(1) }
        val threads = results.map { slot -> Thread { slot[0] = index.similarities(q) }.apply { start() } }
        threads.forEach { it.join() }
        results.forEach { assertArrayEquals(first, it[0], 0f) }
        assertArrayEquals(first, index.similarities(q), 0f)
    }

    @Test
    fun refusesRowsThatDoNotMatchTheCards() {
        val cards = List(3) { card(it, "en") }
        assertThrows(IllegalArgumentException::class.java) { ReferenceIndex(cards, listOf(ReferenceIndex.mapVectors(file(FloatArray(8)))), 2) }
        // 5 floats are not whole rows of 2.
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceIndex(cards, listOf(ReferenceIndex.mapVectors(file(FloatArray(5))), ReferenceIndex.mapVectors(file(FloatArray(1)))), 2)
        }
        assertEquals(3, ReferenceIndex(cards, listOf(ReferenceIndex.mapVectors(file(FloatArray(4))), ReferenceIndex.mapVectors(file(FloatArray(2)))), 2).size)
    }
}
