// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.model

import de.fabianduerkop.cardindex.data.db.Converters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintingsTest {
    @Test
    fun onlyFinishNeedsExactlyOneFinish() {
        assertEquals(Finish.HOLO, Printings.ofKeys(listOf("holo"))?.onlyFinish)
        assertEquals(Finish.REVERSE_HOLO, Printings.ofKeys(listOf("reverse"))?.onlyFinish)
        assertEquals(Finish.NORMAL, Printings.ofKeys(listOf("normal", "firstEdition"))?.onlyFinish)
        assertEquals(Finish.HOLO, Printings.ofKeys(listOf("holo", "firstEdition", "wPromo"))?.onlyFinish)
        assertNull(Printings.ofKeys(listOf("normal", "reverse"))?.onlyFinish)
        assertNull(Printings.ofKeys(listOf("firstEdition"))?.onlyFinish)
    }

    @Test
    fun noKnownKeyIsUnknown() {
        assertNull(Printings.ofKeys(emptyList()))
        assertNull(Printings.ofKeys(listOf("galaxy")))
        assertNull(Printings.parse(null))
        assertNull(Printings.parse(""))
    }

    @Test
    fun storedFormRoundTripsInPackOrder() {
        val p = Printings.ofKeys(listOf("firstEdition", "holo"))
        assertEquals("holo,firstEdition", p.toString())
        assertEquals(p, Printings.parse("holo,firstEdition"))
        val converters = Converters()
        assertEquals(p, converters.stringToPrintings(converters.printingsToString(p)))
        assertNull(converters.printingsToString(null))
    }
}
