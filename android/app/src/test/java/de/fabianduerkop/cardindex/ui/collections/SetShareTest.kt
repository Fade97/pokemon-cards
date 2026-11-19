// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.data.collections.SetDetail
import de.fabianduerkop.cardindex.data.collections.SetMissing
import de.fabianduerkop.cardindex.data.collections.buildSetSlots
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.testing.card
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetShareTest {
    private val set = CardSetEntity("s", "151", "SV", "en", 5, Region.INTERNATIONAL)

    private fun owned(localId: String) = CopyWithCard(
        OwnedCopyEntity(cardId = "s-$localId", cardLanguage = "en", createdAt = Instant.EPOCH),
        card("s-$localId", "s", localId, "en"),
        set,
    )

    // 5-card base set: 001, 003 owned; 002 (wanted), 004, 005 missing; secret rare 006 missing.
    private val catalog = listOf("001", "002", "003", "004", "005", "006").map { card("s-$it", "s", it, "en") }
    private val copies = listOf(owned("001"), owned("003"))
    private val detail = SetDetail(set, listOf("en"), buildSetSlots(5, catalog, copies, null), copies)

    @Test
    fun slotMapListsEverySlotInPrintOrder_withSecretRaresAndWishlistMarks() {
        val data = SetShare.build(detail, "EN", wantedKeys = setOf("2"))

        assertEquals(listOf("1", "2", "3", "4", "5", "6"), data.slots.map { it.label })
        assertEquals(listOf(true, false, true, false, false, false), data.slots.map { it.owned })
        assertEquals(listOf("6"), data.slots.filter { it.secret }.map { it.label })
        assertEquals(listOf("2"), data.slots.filter { it.wanted }.map { it.label })
        assertEquals(SetMissing(base = 3, secret = 1), data.missing)
        assertTrue(data.anyWanted)
    }

    @Test
    fun checklistNamesEachMissingCard_andStarsTheWantedOnes() {
        val data = SetShare.build(detail, "EN", wantedKeys = setOf("2"))

        assertEquals(
            listOf("002/005 Card 002 ★", "004/005 Card 004", "005/005 Card 005", "006/005 Card 006"),
            SetShare.checklistLines(data.missingLines),
        )
    }

    @Test
    fun anOwnedWishlistPrintIsNotMarked() {
        val data = SetShare.build(detail, null, wantedKeys = setOf("1"))
        assertFalse(data.anyWanted)
        assertTrue(data.slots.none { it.wanted })
    }

    @Test
    fun gridColumnsGiveTheLargestSquaresThatFit() {
        // 165 slots on a 312 × 460 area: 11 columns of 28 px, 15 rows.
        assertEquals(11, SetShare.gridColumns(165, 312f, 460f))
        // A few slots get few, big cells.
        assertEquals(2, SetShare.gridColumns(4, 312f, 460f))
        assertEquals(1, SetShare.gridColumns(0, 312f, 460f))
    }
}
