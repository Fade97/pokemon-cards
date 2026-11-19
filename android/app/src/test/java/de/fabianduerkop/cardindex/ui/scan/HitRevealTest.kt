// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.PriceVariant
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.RefCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class HitRevealTest {
    private fun ref(localId: String = "006", lang: String = "en") = RefCard(
        key = "$lang/sv03.5-$localId", cardId = "sv03.5-$localId", lang = lang, setId = "sv03.5", setName = "151",
        localId = localId, official = 165, name = "Card $localId", imageUrl = "", unseen = false,
    )

    private fun price(source: PriceSource, minor: Long, variant: PriceVariant = PriceVariant.NORMAL, currency: String? = null) =
        CachedPriceEntity(
            cardId = "c", source = source, variant = variant,
            currency = currency ?: if (source == PriceSource.CARDMARKET) "EUR" else "USD",
            trend = minor, low = null, avg = null, priceDate = null, fetchedAt = Instant.EPOCH,
        )

    private fun hit(vararg prices: CachedPriceEntity, lang: String = "en", wishlist: Boolean = false, copyId: Long = 1) =
        LastScan.Added(ref(lang = lang), copyId = copyId, prices = prices.toList(), wishlistFound = wishlist, decision = copyId)

    @Test
    fun threshold_isTwentyInEachMarketsOwnCurrency() {
        assertEquals(HitReveal.Threshold(2000, "EUR"), HitReveal.threshold(PriceSource.CARDMARKET))
        assertEquals(HitReveal.Threshold(2000, "USD"), HitReveal.threshold(PriceSource.TCGPLAYER))
        assertEquals("€20", Money.formatWhole(2000, "EUR", Locale.US))
        assertEquals("$20", Money.formatWhole(2000, "USD", Locale.US))
    }

    @Test
    fun valuable_fromTheThresholdUp_inTheMainMarket() {
        assertFalse(hit(price(PriceSource.CARDMARKET, 1999)).isValuable(PriceSource.CARDMARKET))
        assertTrue(hit(price(PriceSource.CARDMARKET, 2000)).isValuable(PriceSource.CARDMARKET))
        assertTrue(hit(price(PriceSource.TCGPLAYER, 4500)).isValuable(PriceSource.TCGPLAYER))
    }

    @Test
    fun valuable_neverUsesTheOtherMarketsPrice() {
        // €60 on Cardmarket, nothing on TCGplayer: for a TCGplayer user it is not valuable, not "€60 ≥ $20".
        val euroOnly = hit(price(PriceSource.CARDMARKET, 6000))
        assertFalse(euroOnly.isValuable(PriceSource.TCGPLAYER))
        assertTrue(euroOnly.isValuable(PriceSource.CARDMARKET))
        // And the other way round.
        assertFalse(hit(price(PriceSource.TCGPLAYER, 6000)).isValuable(PriceSource.CARDMARKET))
        // TCGplayer doesn't price a German print, so its English price never makes the German copy valuable.
        assertFalse(hit(price(PriceSource.TCGPLAYER, 6000), lang = "de").isValuable(PriceSource.TCGPLAYER))
        // A price stored in an unexpected currency is not compared with the threshold either.
        assertFalse(hit(price(PriceSource.TCGPLAYER, 6000, currency = "EUR")).isValuable(PriceSource.TCGPLAYER))
    }

    @Test
    fun valuable_followsTheCopysFinish_andNeedsAPrice() {
        val prices = arrayOf(price(PriceSource.CARDMARKET, 800), price(PriceSource.CARDMARKET, 2500, PriceVariant.REVERSE_HOLO))
        val normal = hit(*prices)
        assertFalse(normal.isValuable(PriceSource.CARDMARKET))
        assertTrue(normal.copy(variant = CopyVariant().withFinish(Finish.REVERSE_HOLO)).isValuable(PriceSource.CARDMARKET))
        assertFalse(hit().isValuable(PriceSource.CARDMARKET))
    }

    @Test
    fun feel_eachKindOfHitHasItsOwn_wishlistBeforeValue() {
        val cm = PriceSource.CARDMARKET
        assertEquals(HitFeel.QUEUED, feelOf(LastScan.Queued(ref(), ReviewReason.NUMBER_NOT_READ, itemId = 1), cm))
        assertEquals(HitFeel.ADDED, feelOf(hit(price(cm, 500)), cm))
        assertEquals(HitFeel.VALUABLE, feelOf(hit(price(cm, 5000)), cm))
        assertEquals(HitFeel.WISHLIST, feelOf(hit(price(cm, 5000), wishlist = true), cm))
    }

    @Test
    fun haptic_onANewDecisionOnly_notOnUndoOrReturning() {
        val cm = PriceSource.CARDMARKET
        val empty = ScanTray()
        val one = empty.record(hit(price(cm, 5000), copyId = 1))
        assertEquals(HitFeel.VALUABLE, hapticBetween(empty, one, cm, hitOnScreen = false))
        val two = one.record(hit(price(cm, 100), copyId = 2))
        assertEquals(HitFeel.ADDED, hapticBetween(one, two, cm, hitOnScreen = true))
        // Undo brings the valuable one back as the latest: no buzz.
        assertNull(hapticBetween(two, two.undo()!!.second, cm, hitOnScreen = true))
        // Coming back to the screen: the same tray on both sides.
        assertNull(hapticBetween(one, one, cm, hitOnScreen = true))
    }

    @Test
    fun haptic_aLatePriceThatMakesTheHitValuable_buzzesOnce_whileTheCardIsInView() {
        val cm = PriceSource.CARDMARKET
        val added = ScanTray().record(hit(copyId = 7))
        val priced = added.withPrices(7, listOf(price(cm, 3000)))
        assertEquals(HitFeel.VALUABLE, hapticBetween(added, priced, cm, hitOnScreen = true))
        // The camera already moved on to the next card: no late buzz that would seem to be about that one.
        assertNull(hapticBetween(added, priced, cm, hitOnScreen = false))
        // A late price under the threshold, or in the other market only, stays quiet.
        assertNull(hapticBetween(added, added.withPrices(7, listOf(price(cm, 300))), cm, hitOnScreen = true))
        assertNull(hapticBetween(added, added.withPrices(7, listOf(price(PriceSource.TCGPLAYER, 9000))), cm, hitOnScreen = true))
        // Prices that were already there changing (a different finish chosen) is not an arrival.
        assertNull(hapticBetween(priced, priced.withPrices(7, listOf(price(cm, 4000))), cm, hitOnScreen = true))
        // A wanted card already had its own haptic when it was added.
        val wanted = ScanTray().record(hit(copyId = 8, wishlist = true))
        assertNull(hapticBetween(wanted, wanted.withPrices(8, listOf(price(cm, 3000))), cm, hitOnScreen = true))
    }

    @Test
    fun halo_onlyForAValuableHitTheCameraStillMeans_andNeverWithAnimationsOff() {
        val cm = PriceSource.CARDMARKET
        val valuable = hit(price(cm, 5000))
        assertTrue(showsValueHalo(valuable, ScanHint.DONE, cm, reducedMotion = false))
        assertFalse(showsValueHalo(valuable, ScanHint.DONE, cm, reducedMotion = true))
        assertFalse(showsValueHalo(valuable, ScanHint.READING, cm, reducedMotion = false))
        assertFalse(showsValueHalo(null, ScanHint.DONE, cm, reducedMotion = false))
        assertFalse(showsValueHalo(hit(price(cm, 500)), ScanHint.DONE, cm, reducedMotion = false))
        assertFalse(showsValueHalo(valuable, ScanHint.DONE, PriceSource.TCGPLAYER, reducedMotion = false))
    }
}
