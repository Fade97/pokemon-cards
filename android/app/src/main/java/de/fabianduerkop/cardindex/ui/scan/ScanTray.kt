// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.data.prices.PriceQuote
import de.fabianduerkop.cardindex.data.prices.PriceSelection
import de.fabianduerkop.cardindex.data.prices.forLanguage
import de.fabianduerkop.cardindex.data.prices.prices
import de.fabianduerkop.cardindex.data.scan.CopyDefaults
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.RefCard

/**
 * Finish, edition and condition of a scanned copy. They start at [CopyDefaults]; the `…Chosen` flags turn true as the
 * user picks each one, so the screen says "your choice" instead of "default". The camera never sets them.
 */
data class CopyVariant(
    val finish: Finish = CopyDefaults.finish,
    val edition: Edition = CopyDefaults.edition,
    val condition: Condition = CopyDefaults.condition,
    val finishChosen: Boolean = false,
    val editionChosen: Boolean = false,
    val conditionChosen: Boolean = false,
) {
    val chosen: Boolean get() = finishChosen || editionChosen || conditionChosen

    fun withFinish(value: Finish) = copy(finish = value, finishChosen = true)
    fun withEdition(value: Edition) = copy(edition = value, editionChosen = true)
    fun withCondition(value: Condition) = copy(condition = value, conditionChosen = true)
}

/** One decision of this scanning session. */
sealed interface LastScan {
    val card: RefCard

    /**
     * Added to All Cards as copy [copyId]; [contributionId]: the crop queued for the open dataset, if any.
     * [prices] are the card's cached prices (empty until known), [wishlistFound] says the copy is the first of a
     * wanted print (#162).
     */
    data class Added(
        override val card: RefCard,
        val copyId: Long,
        val contributionId: Long? = null,
        val variant: CopyVariant = CopyVariant(),
        val prices: List<CachedPriceEntity> = emptyList(),
        val wishlistFound: Boolean = false,
        /** The viewfinder decision that added it: the hit is shown only while the camera still means this copy. */
        val decision: Long = 0,
    ) : LastScan {
        /** Its price in [source]'s own market for its finish and edition; null when that market doesn't price it. */
        fun marketQuote(source: PriceSource): PriceQuote? =
            if (source.prices(card.lang)) PriceSelection.quote(prices, source, variant.finish, variant.edition) else null

        /** Its own price, as card screens show it: [primary], or Cardmarket for a language TCGplayer doesn't price. */
        fun ownQuote(primary: PriceSource): PriceQuote? = marketQuote(primary.forLanguage(card.lang))

        /** This copy as a one-copy value in [source], priced or not: what the session total adds up. */
        fun value(source: PriceSource): CollectionValue {
            val quote = marketQuote(source) ?: return CollectionValue(0, source, unpricedCopies = 1, pricedCopies = 0)
            return CollectionValue(quote.amountMinor, source, unpricedCopies = 0, pricedCopies = 1)
        }
    }

    /** Saved to the review queue as item [itemId]. */
    data class Queued(override val card: RefCard, val reason: ReviewReason, val itemId: Long) : LastScan
}

/**
 * The tray of the Scan screen: this session's decisions, newest last, so Undo can take them back one at a time
 * (#98). Each entry names the exact copy or review item it created, so Undo never touches anything else: not cards
 * added by hand, not copies from an earlier session. Once [history] is empty, Undo is off.
 */
data class ScanTray(
    val history: List<LastScan> = emptyList(),
    /** Cards added to the collection in this session, minus undone ones. */
    val added: Int = 0,
    /** Cards saved for review in this session ("need checking", #161), minus undone ones. */
    val queued: Int = 0,
    /** The card the last Undo took back; cleared by the next decision. */
    val removed: RefCard? = null,
    /** A card whose copy or review item could not be saved (#126); cleared by the next decision or Undo. */
    val failed: RefCard? = null,
    /** Counts new decisions only (Undo leaves it), so the screen can confirm each one with a haptic. */
    val decisions: Long = 0,
    /**
     * Added cards that left [history] (past [MAX_UNDO]) and can't be undone or changed any more, valued once per
     * market, so the session total still counts them (#162).
     */
    val settled: Map<PriceSource, CollectionValue> = emptyMap(),
) {
    val latest: LastScan? get() = history.lastOrNull()

    /** Cards added or queued in this session, minus undone ones. */
    val scanned: Int get() = added + queued
    val canUndo: Boolean get() = history.isNotEmpty()

    /**
     * The session's added cards in [source]'s market (#162): the sum of the priced ones, and how many have no price
     * there. Queued cards aren't in it, and Undo takes a card out again.
     */
    fun value(source: PriceSource): CollectionValue =
        history.filterIsInstance<LastScan.Added>().fold(settled[source] ?: CollectionValue.empty(source)) { sum, scan ->
            sum + scan.value(source)
        }

    fun record(scan: LastScan): ScanTray = copy(
        history = (history + scan).takeLast(MAX_UNDO),
        settled = (history + scan).dropLast(MAX_UNDO).filterIsInstance<LastScan.Added>().fold(settled) { values, gone ->
            PriceSource.entries.associateWith { source -> (values[source] ?: CollectionValue.empty(source)) + gone.value(source) }
        },
        added = added + if (scan is LastScan.Added) 1 else 0,
        queued = queued + if (scan is LastScan.Queued) 1 else 0,
        removed = null,
        failed = null,
        decisions = decisions + 1,
    )

    /** Saving [card] failed: nothing was added or queued, so the history stays as it is. */
    fun failed(card: RefCard): ScanTray = copy(failed = card, removed = null)

    /** The newest decision and the tray without it; null when there is nothing left to undo. */
    fun undo(): Pair<LastScan, ScanTray>? {
        val last = latest ?: return null
        return last to copy(
            history = history.dropLast(1),
            added = if (last is LastScan.Added) maxOf(0, added - 1) else added,
            queued = if (last is LastScan.Queued) maxOf(0, queued - 1) else queued,
            removed = last.card,
            failed = null,
        )
    }

    /** The user's [variant] for copy [copyId], if that copy is still in the history. */
    fun withVariant(copyId: Long, variant: CopyVariant): ScanTray = copy(
        history = history.map { if (it is LastScan.Added && it.copyId == copyId) it.copy(variant = variant) else it },
    )

    /** The card's [prices] for copy [copyId], once they arrived after the scan (fetched for a card not cached yet). */
    fun withPrices(copyId: Long, prices: List<CachedPriceEntity>): ScanTray = copy(
        history = history.map { if (it is LastScan.Added && it.copyId == copyId) it.copy(prices = prices) else it },
    )

    companion object {
        /** How many decisions Undo can go back; older ones stay in the collection and the review queue. */
        const val MAX_UNDO = 50
    }
}

/**
 * The tray entry the camera's current [decision] produced, once its copy is saved; null while that copy is still being
 * saved, so a second copy of a print never shows the first copy's price or wishlist find (#162).
 */
internal fun hitFor(latest: LastScan?, decision: Long): LastScan.Added? =
    (latest as? LastScan.Added)?.takeIf { decision != 0L && it.decision == decision }
