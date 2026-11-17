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
}
