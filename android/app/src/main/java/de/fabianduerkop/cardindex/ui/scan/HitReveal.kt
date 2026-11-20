// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import de.fabianduerkop.cardindex.data.prices.currency
import de.fabianduerkop.cardindex.model.PriceSource

/**
 * The hit reveal (#168): a valuable scan gets a brief light halo around the card in the frame, its own haptic and its
 * own hint line. Nothing of it waits on anything: the camera keeps deciding the next card, and the next decision
 * simply replaces the reveal. With animations off the halo is left out; the haptic and the text stay.
 *
 * **Valuable** is the hit's own price in the user's main market, at or above [threshold] there, in that market's own
 * currency: €20 on Cardmarket, $20 on TCGplayer. No conversion and no fallback: a card the main market has no price
 * for (no price yet, a language TCGplayer doesn't price, a 1st edition Cardmarket doesn't split) is not valuable,
 * even when the other market prices it highly, so EUR and USD never mix.
 */
object HitReveal {
    /** €20 / $20: about where a single pulled from a pack stops being bulk and becomes a card worth sleeving. */
    const val THRESHOLD_MINOR = 2_000L

    /** The threshold in [source]'s own currency. */
    fun threshold(source: PriceSource): Threshold = Threshold(THRESHOLD_MINOR, source.currency)

    data class Threshold(val amountMinor: Long, val currency: String)
}

/** Whether this hit is valuable for a user whose main market is [primary] (see [HitReveal]). */
fun LastScan.Added.isValuable(primary: PriceSource): Boolean {
    val quote = marketQuote(primary) ?: return false
    val threshold = HitReveal.threshold(primary)
    return quote.source == primary && quote.currency == threshold.currency && quote.amountMinor >= threshold.amountMinor
}

/** How a decision feels in the hand; each one has its own haptic, so the hand can tell them apart without looking. */
enum class HitFeel {
    /** Added; the plain confirm. */
    ADDED,

    /** Saved for review: a lighter tick (#161). */
    QUEUED,

    /** A wanted card found (#162); it keeps its own haptic even when it is valuable too. */
    WISHLIST,

    /** A valuable hit (#168): two quick pulses. */
    VALUABLE,
}

fun feelOf(scan: LastScan, primary: PriceSource): HitFeel = when {
    scan is LastScan.Queued -> HitFeel.QUEUED
    scan is LastScan.Added && scan.wishlistFound -> HitFeel.WISHLIST
    scan is LastScan.Added && scan.isValuable(primary) -> HitFeel.VALUABLE
    else -> HitFeel.ADDED
}

/**
 * The haptic between two states of the tray, or null for none. A new decision always gets its feel. A card nobody owned
 * before is priced only after it was added; if that price makes it valuable while it is still the hit on screen
 * ([hitOnScreen]), it gets the valuable haptic then, once. Undo, a changed finish and coming back to the screen don't
 * buzz.
 */
internal fun hapticBetween(before: ScanTray, after: ScanTray, primary: PriceSource, hitOnScreen: Boolean): HitFeel? {
    val latest = after.latest
    if (after.decisions > before.decisions) return latest?.let { feelOf(it, primary) }
    val was = before.latest as? LastScan.Added ?: return null
    val now = latest as? LastScan.Added ?: return null
    val priceArrived = was.copyId == now.copyId && was.prices.isEmpty() && now.prices.isNotEmpty()
    return HitFeel.VALUABLE.takeIf { priceArrived && hitOnScreen && feelOf(now, primary) == HitFeel.VALUABLE }
}

/**
 * Whether the frame shows the valuable halo: the camera still means this [hit] (it says "added") and the hit is
 * valuable. [reducedMotion] leaves it out, since the halo is the one moving part of the reveal.
 */
internal fun showsValueHalo(hit: LastScan.Added?, hint: ScanHint, primary: PriceSource, reducedMotion: Boolean): Boolean =
    !reducedMotion && hit != null && hint == ScanHint.DONE && hit.isValuable(primary)
