// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import de.fabianduerkop.cardindex.data.collections.numberKey
import de.fabianduerkop.cardindex.data.db.CachedPriceEntity
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.data.prices.PriceQuote
import de.fabianduerkop.cardindex.data.prices.PriceSelection
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.PriceSource
import java.time.Instant

/** One print on a session receipt: [copies] of it, each worth [quote] in the receipt's market (null: no price there). */
data class ReceiptLine(
    val card: CardEntity,
    val set: CardSetEntity,
    val finish: Finish,
    val edition: Edition,
    val copies: Int,
    val quote: PriceQuote?,
)

/**
 * What a scanning session added (#163), from the session ledger as the collection is now: Undo and later deletions
 * are already gone, changed finishes are priced as changed. [value] uses the same rule as the collection value (one
 * market, unpriced copies counted apart), so the receipt and Collections agree.
 */
data class SessionReceipt(
    /** Copies added and still owned. */
    val cards: Int,
    val value: CollectionValue,
    /** The newest price date among the priced copies; null without any. */
    val priceDate: Instant?,
    /** Set slots (set + collector number) that had no copy before this session. */
    val newSlots: Int,
    val sets: Int,
    /** The most valuable print of the session; null when nothing is priced. */
    val bestFind: ReceiptLine?,
    /** Every print, most valuable first, unpriced ones last. */
    val lines: List<ReceiptLine>,
) {
    val isEmpty: Boolean get() = cards == 0

    companion object {
        /**
         * The receipt of [session]'s copies. [before] are the other copies in the same sets (not from this session),
         * to tell which slots are new; [pricesByCard] the cached prices, [source] the user's main market.
         */
        fun build(
            session: List<CopyWithCard>,
            before: List<CopyWithCard>,
            pricesByCard: Map<String, List<CachedPriceEntity>>,
            source: PriceSource,
        ): SessionReceipt {
            val value = PriceSelection.total(session.map { it.copy }, pricesByCard, source)
            val lines = session
                .groupBy { listOf(it.card.id, it.card.language, it.copy.finish, it.copy.edition) }
                .values
                .map { group ->
                    val first = group.first()
                    ReceiptLine(
                        card = first.card,
                        set = first.set,
                        finish = first.copy.finish,
                        edition = first.copy.edition,
                        copies = group.sumOf { it.copy.quantity },
                        quote = PriceSelection.quote(pricesByCard[first.card.id].orEmpty(), source, first.copy),
                    )
                }
                .sortedWith(compareByDescending<ReceiptLine> { it.quote?.amountMinor ?: -1L }.thenBy { it.card.name })
            val filledBefore = before.mapTo(HashSet()) { slot(it) }
            return SessionReceipt(
                cards = session.sumOf { it.copy.quantity },
                value = value,
                priceDate = lines.mapNotNull { it.quote?.priceDate }.maxOrNull(),
                newSlots = session.mapTo(HashSet()) { slot(it) }.count { it !in filledBefore },
                sets = session.mapTo(HashSet()) { it.card.setId }.size,
                bestFind = lines.firstOrNull { it.quote != null },
                lines = lines,
            )
        }

        /** EN, DE and FR prints of one number share a slot, as in the set grid (ADR 0010). */
        private fun slot(copy: CopyWithCard): Pair<String, String> = copy.card.setId to numberKey(copy.card.localId)
    }
}
