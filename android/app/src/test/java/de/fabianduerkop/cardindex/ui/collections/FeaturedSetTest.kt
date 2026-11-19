// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.data.db.SetCompletion
import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeaturedSetTest {
    private fun row(id: String, valueMinor: Long?) = SetRowUi(
        completion = SetCompletion(id, "Set $id", Region.INTERNATIONAL, 100, 1, 1),
        ownedSlots = emptySet(),
        value = valueMinor?.let { CollectionValue(it, PriceSource.CARDMARKET, 0) },
    )

    private val sets = listOf(row("a", 1_200), row("b", 68_420), row("c", null), row("d", 9_900))

    private fun featuredId(sets: List<SetRowUi>, pinned: String?) = featuredSet(sets, pinned)?.completion?.setId

    @Test
    fun withoutPinTheMostValuableSetIsFeatured() {
        assertEquals("b", featuredId(sets, null))
    }

    @Test
    fun aPinnedSetWinsOverTheMostValuable() {
        assertEquals("c", featuredId(sets, "c"))
    }

    @Test
    fun aPinnedSetOutsideTheListFallsBackToTheMostValuable() {
        // Pinned set hidden by the language filter, or no copies left.
        assertEquals("b", featuredId(sets, "gone"))
    }

    @Test
    fun withoutPricesOrOnATieTheEarlierSetWins() {
        assertEquals("a", featuredId(listOf(row("a", null), row("b", 0), row("c", null)), null))
        assertEquals("b", featuredId(listOf(row("a", 10), row("b", 500), row("c", 500)), null))
    }

    @Test
    fun noSetsMeansNoCover() {
        assertNull(featuredSet(emptyList(), "a"))
    }

    @Test
    fun otherSetsKeepTheirOrderWithoutTheFeaturedOne() {
        val state = CollectionsUiState(sets = sets, featured = featuredSet(sets, null))
        assertEquals(listOf("a", "c", "d"), state.otherSets.map { it.completion.setId })
    }
}
