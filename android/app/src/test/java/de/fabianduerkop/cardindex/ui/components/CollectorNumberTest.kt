// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectorNumberTest {
    @Test
    fun padsNumericIdsToTheWidthOfTheTotal() {
        assertEquals("006", padCollectorNumber("6", 165))
        assertEquals("006", padCollectorNumber("006", 165))
        assertEquals("025", padCollectorNumber("25", 99))
        assertEquals("0042", padCollectorNumber("42", 1006))
        assertEquals("007", padCollectorNumber("7", null))
        assertEquals("000", padCollectorNumber("0", 165))
    }

    @Test
    fun leavesNonNumericIdsAlone() {
        assertEquals("TG05", padCollectorNumber("TG05", 30))
        assertEquals("SWSH050", padCollectorNumber("SWSH050", null))
        assertEquals("", padCollectorNumber("", 165))
    }
}
