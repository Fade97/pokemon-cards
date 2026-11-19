// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PercentChangeTest {
    @Test
    fun aChangeThatRoundsToZeroPercentHasNoPercentage() {
        // 684,00 € to 684,20 €: +0.03 %, which would read "+0 %" next to an up arrow (#173).
        assertNull(percentChange(68_400, 68_420))
        assertNull(percentChange(100, 100))
        assertNull(percentChange(0, 50))
        assertNotNull(percentChange(1_000, 1_001))
        assertNotNull(percentChange(100, 112))
    }
}
