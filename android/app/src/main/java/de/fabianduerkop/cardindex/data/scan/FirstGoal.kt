// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import de.fabianduerkop.cardindex.data.prices.CollectionValue

/**
 * The first-run goal (#174): "Scan your first 10 cards, then see what they're worth". An empty app explains less
 * than it motivates; a small, reachable number gives the first session a point, and its end shows the reward.
 *
 * The goal counts every copy in the collection (quantity included), not only this session's, so a first session cut
 * short continues where it stopped. Someone who already owns [TARGET] cards when the Scan screen opens never sees it.
 */
object FirstGoal {
    const val TARGET = 10

    sealed interface State {
        /** [cards] of [TARGET] so far. */
        data class Progress(val cards: Int) : State

        /** Reached on this screen: what the collection is worth now, in the user's main market. */
        data class Reached(val value: CollectionValue) : State
    }

    /**
     * What the Scan screen shows, given the collection's size when the screen opened ([cardsAtStart]) and now
     * ([cardsNow]); null when the goal was already behind the user. Once reached it stays reached for the screen,
     * even if Undo takes a card back, so the reward doesn't flicker away.
     */
    fun state(cardsAtStart: Int, cardsNow: Int, reachedHere: Boolean, value: CollectionValue): State? = when {
        cardsAtStart >= TARGET -> null
        reachedHere || cardsNow >= TARGET -> State.Reached(value)
        else -> State.Progress(cardsNow)
    }
}
