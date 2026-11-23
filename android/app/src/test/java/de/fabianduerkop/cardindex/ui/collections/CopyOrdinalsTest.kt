// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.testing.card
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class CopyOrdinalsTest {
    private val set = CardSetEntity("s", "151", "SV", "en", 165, Region.INTERNATIONAL)

    private fun copy(id: Long, condition: Condition = Condition.NEAR_MINT, language: String = "en") = CopyWithCard(
        OwnedCopyEntity(id = id, cardId = "s-003", cardLanguage = language, condition = condition, createdAt = Instant.EPOCH),
        card("s-003", "s", "003", language),
        set,
    )

    @Test
    fun onlyCopiesThatWouldReadTheSameAreNumbered() {
        // 1 and 2 match; 3 differs by condition, 4 by language, 5 by its binder (#221).
        val copies = listOf(copy(1), copy(2), copy(3, Condition.LIGHTLY_PLAYED), copy(4, language = "de"), copy(5))
        val ordinals = copyOrdinals(copies, mapOf(5L to listOf("Trade binder")))

        assertEquals(mapOf(1L to 1, 2L to 2), ordinals)
    }
}
