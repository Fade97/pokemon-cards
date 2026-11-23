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

        // Padded like the set grid (#221).
        assertEquals(listOf("001", "002", "003", "004", "005", "006"), data.slots.map { it.label })
        assertEquals(listOf(true, false, true, false, false, false), data.slots.map { it.owned })
        assertEquals(listOf("006"), data.slots.filter { it.secret }.map { it.label })
        assertEquals(listOf("002"), data.slots.filter { it.wanted }.map { it.label })
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

    @Test
    fun cellsShrinkToTheRowHeight_whenTheGridIsTallerThanWide() {
        // Delta-Herrschaft: 103 + 38 = 141 slots. 10 columns need 15 rows; on a 312 × 400 area a row is 26.7 high,
        // less than the 31.2 column width, so every cell must use the row height or the last row is cut off.
        val columns = SetShare.gridColumns(141, 312f, 400f)
        val cell = SetShare.cellSize(141, columns, 312f, 400f)
        val rows = kotlin.math.ceil(141 / columns.toFloat())
        assertTrue(cell * rows <= 400f + 0.01f)
        assertTrue(cell * columns <= 312f + 0.01f)
        assertEquals(312f / 11, SetShare.cellSize(165, 11, 312f, 460f), 0.001f)
    }

    @Test
    fun aCompleteSetIsDatedByItsLastFirstFilledBaseNumber() {
        fun at(day: Int, localId: String) = CopyWithCard(
            OwnedCopyEntity(cardId = "s-$localId", cardLanguage = "en", createdAt = Instant.parse("2026-11-%02dT10:00:00Z".format(day))),
            card("s-$localId", "s", localId, "en"),
            set,
        )
        // 002 was owned on the 3rd and again on the 9th: it counts from the 3rd. 005 came last, on the 7th.
        // The secret rare 006 on the 12th doesn't move the date.
        val owned = listOf(at(1, "001"), at(3, "002"), at(9, "002"), at(2, "003"), at(4, "004"), at(7, "005"), at(12, "006"))
        val complete = SetDetail(set, listOf("en"), buildSetSlots(5, catalog, owned, null), owned)

        assertEquals(java.time.LocalDate.of(2026, 11, 7), SetShare.build(complete, "EN", emptySet(), java.time.ZoneOffset.UTC).completedOn)
        assertEquals(null, SetShare.build(detail, "EN", emptySet()).completedOn)
    }

    @Test
    fun aSetCompleteOnlyInAnotherLanguageIsNoCertificate() {
        fun copy(localId: String, language: String) = CopyWithCard(
            OwnedCopyEntity(cardId = "s-$localId", cardLanguage = language, createdAt = Instant.EPOCH),
            card("s-$localId", "s", localId, language),
            set,
        )
        // Complete in English, only 001 in German: the German map has gaps, so it is no certificate (#221).
        val owned = listOf("001", "002", "003", "004", "005").map { copy(it, "en") } + copy("001", "de")
        val german = SetDetail(set, listOf("de", "en"), buildSetSlots(5, catalog, owned, setOf("de")), owned)
        val english = SetDetail(set, listOf("de", "en"), buildSetSlots(5, catalog, owned, setOf("en")), owned)

        assertEquals(null, SetShare.build(german, "DE", emptySet()).completedOn)
        assertTrue(SetShare.build(english, "EN", emptySet()).completedOn != null)
    }
}
