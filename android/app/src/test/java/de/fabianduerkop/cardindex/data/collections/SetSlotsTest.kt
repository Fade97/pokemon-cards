// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.collections

import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.EnergyType
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.testing.card
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetSlotsTest {
    private val set = CardSetEntity("s", "Set", "S", "en", 5, Region.INTERNATIONAL)

    private fun owned(localId: String, language: String, quantity: Int = 1, type: EnergyType = EnergyType.FIRE) =
        CopyWithCard(
            OwnedCopyEntity(cardId = "s-$localId", cardLanguage = language, quantity = quantity, createdAt = Instant.EPOCH),
            card("s-$localId", "s", localId, language, type),
            set,
        )

    @Test
    fun everyNumberUpToTheTotalGetsASlot_evenWithoutCatalogData() {
        val slots = buildSetSlots(5, emptyList(), emptyList(), null)

        assertEquals(listOf("1", "2", "3", "4", "5"), slots.map { it.localId })
        assertTrue(slots.none { it.owned })
        assertTrue(slots.all { it.card == null })
    }

    @Test
    fun printsOfOneNumberShareASlot_andSecretRaresComeAfterTheTotal() {
        val catalog = listOf(card("s-002", "s", "002", "en"), card("s-TG01", "s", "TG01", "en"), card("s-007", "s", "007", "en"))
        val copies = listOf(owned("002", "en", quantity = 2), owned("002", "de"), owned("007", "en"))

        val slots = buildSetSlots(5, catalog, copies, null)

        assertEquals(listOf("1", "002", "3", "4", "5", "007", "TG01"), slots.map { it.localId })
        val two = slots[1]
        assertTrue(two.owned)
        assertEquals(3, two.copies)
        assertTrue(slots[5].owned)
        assertFalse(slots[6].owned)
    }

    @Test
    fun languageFilter_decidesWhichCopiesFillASlotAndWhichPrintShows() {
        val catalog = listOf(card("s-001", "s", "001", "en"), card("s-001", "s", "001", "de"))
        val copies = listOf(owned("001", "de"))

        val english = buildSetSlots(5, catalog, copies, setOf("en"))
        assertFalse(english[0].owned)
        assertEquals("en", english[0].card?.language)

        val german = buildSetSlots(5, catalog, copies, setOf("de"))
        assertTrue(german[0].owned)
        assertEquals("de", german[0].card?.language)

        val all = buildSetSlots(5, catalog, copies, null)
        assertEquals("de", all[0].card?.language) // the owned print wins
    }

    @Test
    fun slotForAnUnknownNumber_hasNoCard() {
        assertNull(buildSetSlots(5, emptyList(), listOf(owned("001", "en")), null)[1].card)
        assertEquals(EnergyType.FIRE, buildSetSlots(5, emptyList(), listOf(owned("001", "en")), null)[0].energyType)
    }

    @Test
    fun progress_countsBaseNumbersOfThePrintedTotal_andSecretRaresApart() {
        // 160 base cards and 10 secret rares of a 165-card set read 160/165 +10, not 170/165 (#132).
        val owned = (1..160).map { it.toString().padStart(3, '0') } + (166..175).map { it.toString() }

        val progress = setProgress(owned, 165)

        assertEquals(SetProgress(base = 160, printedTotal = 165, secret = 10), progress)
        assertEquals(160f / 165, progress.fraction, 0.0001f)
    }

    @Test
    fun progress_neverPassesTheWholeSet() {
        val progress = setProgress((1..170).map { it.toString() }, 165)

        assertEquals(165, progress.base)
        assertEquals(5, progress.secret)
        assertEquals(1f, progress.fraction, 0f)
    }

    @Test
    fun progress_countsPaddedNumbersOnce_andLettersAsSecret() {
        val progress = setProgress(listOf("006", "6", "TG05", "0"), 10)

        assertEquals(SetProgress(base = 1, printedTotal = 10, secret = 2), progress)
    }

    @Test
    fun setDetailProgress_matchesItsSlots_whileTheOwnedFilterListsEverySlot() {
        val copies = listOf(owned("001", "en"), owned("005", "en"), owned("007", "en"), owned("TG01", "en"))
        val detail = SetDetail(set, listOf("en"), buildSetSlots(5, emptyList(), copies, null), copies)

        assertEquals(4, detail.ownedNumbers)
        assertEquals(SetProgress(base = 2, printedTotal = 5, secret = 2), detail.progress)
    }

    @Test
    fun progress_withoutAPrintedTotal_isEmpty() {
        assertEquals(0f, setProgress(listOf("1"), 0).fraction, 0f)
        assertEquals(1, setProgress(listOf("1"), 0).secret)
    }
}
