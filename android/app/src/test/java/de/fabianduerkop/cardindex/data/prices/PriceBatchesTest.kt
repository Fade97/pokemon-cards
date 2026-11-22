// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceBatchesTest {
    private fun set(name: String, cards: Int) = (1..cards).map { "$name-%03d".format(it) }

    @Test
    fun `cards of one set share a request`() {
        val ids = set("sv01", 3) + set("base1", 2) + set("sv01", 4).drop(3)
        val batches = PriceBatches.of(ids.shuffled())
        assertEquals(1, batches.size)
        assertEquals(ids.toSet(), batches.single().toSet())
    }

    @Test
    fun `a request touches at most MAX_SETS sets`() {
        val ids = (1..10).flatMap { set("set$it", 2) }
        val batches = PriceBatches.of(ids)
        assertEquals(3, batches.size)
        batches.forEach { batch -> assertTrue(batch.map(PriceShards::of).toSet().size <= PriceBatches.MAX_SETS) }
        assertEquals(ids.toSet(), batches.flatten().toSet())
    }

    @Test
    fun `a large set is split at the id limit`() {
        val ids = set("sv03.5", 250) + set("sv04", 10)
        val batches = PriceBatches.of(ids)
        assertEquals(listOf(100, 100, 60), batches.map { it.size })
        assertEquals(ids.toSet(), batches.flatten().toSet())
    }

    @Test
    fun `no ids, no requests`() {
        assertEquals(emptyList<List<String>>(), PriceBatches.of(emptyList()))
    }
}
