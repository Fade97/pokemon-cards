// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #145: the variant check and the finish chips follow the card's printings, the set list is the fallback. */
class VariantCheckTest {
    private fun printings(vararg keys: String) = Printings.ofKeys(keys.toList())

    @Test
    fun aBaseSetCardAsksForTheEditionOnlyWithA1stEditionPrinting() {
        assertTrue(needsVariantCheck("base1", printings("holo", "firstEdition")))
        assertFalse(needsVariantCheck("base1", printings("holo")))
    }

    @Test
    fun aCardOutsideTheWotcSetsWithA1stEditionPrintingAsksToo() {
        // JP Neo-era and later 1st-edition prints live in sets the old list never named.
        assertTrue(needsVariantCheck("neo1-jp", printings("normal", "firstEdition")))
        assertFalse(needsVariantCheck("sv03.5", printings("normal", "reverse")))
    }

    @Test
    fun unknownPrintingsFallBackToTheSetList() {
        assertTrue(needsVariantCheck("base1", null))
        assertTrue(needsVariantCheck("Neo4", null))
        assertFalse(needsVariantCheck("base4", null))
        assertFalse(needsVariantCheck("sv03.5", null))
    }

    @Test
    fun printedFinishesComeFirstAndTheOthersStaySelectable() {
        assertEquals(listOf(Finish.HOLO, Finish.NORMAL, Finish.REVERSE_HOLO, Finish.OTHER), finishOrder(printings("holo", "firstEdition")))
        assertEquals(listOf(Finish.HOLO, Finish.REVERSE_HOLO, Finish.NORMAL, Finish.OTHER), finishOrder(printings("reverse", "holo")))
        assertEquals(listOf(Finish.NORMAL, Finish.REVERSE_HOLO, Finish.HOLO, Finish.OTHER), finishOrder(printings("normal", "reverse")))
        // Unknown, or no finish among them: Finish order.
        assertEquals(Finish.entries, finishOrder(null))
        assertEquals(Finish.entries, finishOrder(printings("firstEdition")))
    }
}
