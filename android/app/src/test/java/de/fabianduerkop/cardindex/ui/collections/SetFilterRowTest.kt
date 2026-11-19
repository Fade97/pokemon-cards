// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.data.collections.SetSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The non-UI parts of the set detail filter row (#165). */
class SetFilterRowTest {
    private fun slot(id: String, owned: Boolean) = SetSlot(localId = id, card = null, owned = owned, copies = if (owned) 1 else 0)

    // 3/3 base set plus one owned and one missing secret rare.
    private val slots = listOf(slot("1", true), slot("2", false), slot("3", true), slot("4", true), slot("5", false))

    @Test
    fun filtersKeepPrintOrderAndIncludeSecretRares() {
        assertEquals(slots, slots.filteredBy(SlotFilter.ALL))
        assertEquals(listOf("1", "3", "4"), slots.filteredBy(SlotFilter.OWNED).map { it.localId })
        assertEquals(listOf("2", "5"), slots.filteredBy(SlotFilter.MISSING).map { it.localId })
    }

    @Test
    fun languageChipNamesTheChosenLanguagesInTheSetsOrder() {
        val languages = listOf("de", "en", "fr")
        assertNull(languageChipLabel(languages, emptySet()))
        assertEquals("EN", languageChipLabel(languages, setOf("en")))
        assertEquals("DE, FR", languageChipLabel(languages, setOf("fr", "de")))
    }

    @Test
    fun legendShowsOnTheFirstVisitsOnly() {
        assertTrue(SetLegend.showsOnVisit(0))
        assertTrue(SetLegend.showsOnVisit(SetLegend.FIRST_VISITS - 1))
        assertFalse(SetLegend.showsOnVisit(SetLegend.FIRST_VISITS))
        assertFalse(SetLegend.showsOnVisit(42))
    }
}
