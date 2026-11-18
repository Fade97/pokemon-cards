// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a copy row stacks its price under the details (#121), for the widths and font sizes of the #101 pass. */
class NarrowRowTest {
    @Test
    fun `default and 1_3x on a 411 dp phone keep the side column`() {
        assertFalse(isNarrowRow(411.dp, 1.0f))
        assertFalse(isNarrowRow(411.dp, 1.3f))
        assertFalse(isNarrowRow(360.dp, 1.0f))
    }

    @Test
    fun `2x font size and 1_3x on a 360 dp phone stack`() {
        assertTrue(isNarrowRow(411.dp, 2.0f))
        assertTrue(isNarrowRow(360.dp, 2.0f))
        assertTrue(isNarrowRow(360.dp, 1.3f))
    }
}
