// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.model

import de.fabianduerkop.cardindex.model.PriceVariant.Companion.candidates
import org.junit.Assert.assertEquals
import org.junit.Test

class PriceVariantTest {
    @Test
    fun tcgplayerSubTypes() {
        assertEquals(PriceVariant.NORMAL, PriceVariant.fromTcgplayerSubType("Normal"))
        assertEquals(PriceVariant.NORMAL, PriceVariant.fromTcgplayerSubType("Unlimited"))
        assertEquals(PriceVariant.HOLO, PriceVariant.fromTcgplayerSubType("Unlimited Holofoil"))
        assertEquals(PriceVariant.REVERSE_HOLO, PriceVariant.fromTcgplayerSubType("Reverse Holofoil"))
        assertEquals(PriceVariant.FIRST_EDITION, PriceVariant.fromTcgplayerSubType("1st Edition Normal"))
        assertEquals(PriceVariant.FIRST_EDITION_HOLO, PriceVariant.fromTcgplayerSubType("1st Edition Holofoil"))
        assertEquals(null, PriceVariant.fromTcgplayerSubType("Something New"))
    }

    @Test
    fun cardmarket_pricesFoilAsReverse_andSkipsSpecialEditions() {
        val cm = PriceSource.CARDMARKET
        assertEquals(listOf(PriceVariant.NORMAL), candidates(cm, Finish.NORMAL, Edition.UNLIMITED))
        assertEquals(listOf(PriceVariant.NORMAL), candidates(cm, Finish.HOLO, Edition.UNLIMITED))
        assertEquals(listOf(PriceVariant.REVERSE_HOLO), candidates(cm, Finish.REVERSE_HOLO, Edition.UNLIMITED))
        assertEquals(emptyList<PriceVariant>(), candidates(cm, Finish.HOLO, Edition.FIRST_EDITION))
        assertEquals(emptyList<PriceVariant>(), candidates(cm, Finish.HOLO, Edition.SHADOWLESS))
    }

    @Test
    fun tcgplayer_firstEditionHolo_isItsOwnPrice() {
        val tp = PriceSource.TCGPLAYER
        assertEquals(listOf(PriceVariant.FIRST_EDITION_HOLO), candidates(tp, Finish.HOLO, Edition.FIRST_EDITION))
        assertEquals(listOf(PriceVariant.HOLO, PriceVariant.NORMAL), candidates(tp, Finish.HOLO, Edition.UNLIMITED))
        assertEquals(listOf(PriceVariant.NORMAL, PriceVariant.HOLO), candidates(tp, Finish.NORMAL, Edition.UNLIMITED))
        assertEquals(listOf(PriceVariant.REVERSE_HOLO), candidates(tp, Finish.REVERSE_HOLO, Edition.UNLIMITED))
        assertEquals(emptyList<PriceVariant>(), candidates(tp, Finish.NORMAL, Edition.SHADOWLESS))
    }
}
