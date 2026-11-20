// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.scan

import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.model.PriceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstGoalTest {
    private val value = CollectionValue(4820, PriceSource.CARDMARKET, unpricedCopies = 1, pricedCopies = 9)

    @Test
    fun countsUpFromWhereAnEarlierSessionStopped() {
        assertEquals(FirstGoal.State.Progress(0), FirstGoal.state(cardsAtStart = 0, cardsNow = 0, reachedHere = false, value))
        assertEquals(FirstGoal.State.Progress(7), FirstGoal.state(cardsAtStart = 3, cardsNow = 7, reachedHere = false, value))
    }

    @Test
    fun reachingTheTargetShowsTheValue_andUndoDoesNotTakeTheRewardBack() {
        assertEquals(FirstGoal.State.Reached(value), FirstGoal.state(cardsAtStart = 8, cardsNow = 10, reachedHere = false, value))
        // A quantity of 3 can jump past it.
        assertEquals(FirstGoal.State.Reached(value), FirstGoal.state(cardsAtStart = 8, cardsNow = 11, reachedHere = false, value))
        assertEquals(FirstGoal.State.Reached(value), FirstGoal.state(cardsAtStart = 8, cardsNow = 9, reachedHere = true, value))
    }

    @Test
    fun aCollectionAlreadyPastTheTargetNeverShowsIt() {
        assertNull(FirstGoal.state(cardsAtStart = FirstGoal.TARGET, cardsNow = 12, reachedHere = false, value))
        assertNull(FirstGoal.state(cardsAtStart = 250, cardsNow = 250, reachedHere = true, value))
    }
}
