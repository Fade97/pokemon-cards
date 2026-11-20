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

    // A 3-card base set (1–3, slot 2 missing) plus secret rares 4 (owned) and 5 (missing); filters treat them alike.
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

    @Test
    fun aVisitCountsOnlyWhileTheLegendStillShowsOnItsOwn() {
        var recorded = 0
        assertTrue(SetLegend.visit(0) { recorded++ })
        assertTrue(SetLegend.visit(SetLegend.FIRST_VISITS - 1) { recorded++ })
        assertEquals(2, recorded)
        // From then on the info button shows it; visits are no longer written.
        assertFalse(SetLegend.visit(SetLegend.FIRST_VISITS) { recorded++ })
        assertFalse(SetLegend.visit(99) { recorded++ })
        assertEquals(2, recorded)
    }
}
