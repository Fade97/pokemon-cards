// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class JoinedTextTest {
    /** A line fits when it has at most [width] characters. */
    private fun fitsIn(width: Int) = { line: String -> line.length <= width }

    @Test
    fun `everything on one line when it fits`() {
        assertEquals("TCGplayer market · Holo", joinParts(listOf(listOf("TCGplayer market", "Holo")), false, fitsIn(40)))
    }

    @Test
    fun `breaks between facts, never after a dot`() {
        assertEquals(
            "Not in a binder · German\nadded Nov 18 · copy 1",
            joinParts(listOf(listOf("Not in a binder", "German", "added Nov 18", "copy 1")), false, fitsIn(26)),
        )
    }

    @Test
    fun `groups start their own line when the whole does not fit`() {
        assertEquals(
            "Not in a binder\nadded Nov 18 · copy 1",
            joinParts(listOf(listOf("Not in a binder"), listOf("added Nov 18", "copy 1")), false, fitsIn(30)),
        )
        assertEquals(
            "Not in a binder · added Nov 18 · copy 1",
            joinParts(listOf(listOf("Not in a binder"), listOf("added Nov 18", "copy 1")), false, fitsIn(60)),
        )
    }

    @Test
    fun `group lines always break between groups`() {
        assertEquals("3 cards · $4.10\nTCGplayer", joinParts(listOf(listOf("3 cards", "$4.10"), listOf("TCGplayer")), true, fitsIn(60)))
    }

    @Test
    fun `a fact wider than the line stays whole`() {
        assertEquals("Trade binder\nGerman", joinParts(listOf(listOf("Trade binder", "German")), false, fitsIn(5)))
    }
}
