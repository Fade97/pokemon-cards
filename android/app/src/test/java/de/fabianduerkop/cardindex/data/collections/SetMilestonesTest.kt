// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.collections

import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.testing.card
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetMilestonesTest {
    private val set = CardSetEntity("s", "Set", "S", "en", 4, Region.INTERNATIONAL)

    private fun copy(localId: String, language: String = "en") = CopyWithCard(
        OwnedCopyEntity(cardId = "s-$localId", cardLanguage = language, createdAt = Instant.EPOCH),
        card("s-$localId", "s", localId, language),
        set,
    )

    @Test
    fun levelsCountTheBaseSetOnly() {
        assertEquals(emptyList<Int>(), Milestones.reached(SetProgress(base = 0, printedTotal = 4, secret = 3)))
        assertEquals(listOf(25, 50), Milestones.reached(SetProgress(base = 2, printedTotal = 4, secret = 0)))
        assertEquals(Milestones.LEVELS, Milestones.reached(SetProgress(base = 165, printedTotal = 165, secret = 0)))
        // 82 of 165 is just under half.
        assertEquals(listOf(25), Milestones.reached(SetProgress(base = 82, printedTotal = 165, secret = 40)))
        assertEquals(emptyList<Int>(), Milestones.reached(SetProgress(base = 3, printedTotal = 0, secret = 0)))
    }

    private class Fixture(initial: List<CopyWithCard>) {
        var copies = initial
        var celebrated = emptySet<String>()
        var seeded = false
        val milestones = SetMilestones(
            SetMilestones.Store({ celebrated }, { celebrated = it }),
            SetMilestones.Store({ seeded }, { seeded = it }),
            allCopies = { copies },
            copiesInSet = { id -> copies.filter { it.card.setId == id } },
        )
    }

    @Test
    fun aMilestoneIsCelebratedOnce_evenAfterUndoAndRedo() = runBlocking {
        val f = Fixture(emptyList())
        f.milestones.seed()

        f.copies = listOf(copy("001"))
        assertEquals(25, f.milestones.celebrate("s", "en")!!.level)
        assertNull(f.milestones.celebrate("s", "en"))

        f.copies = listOf(copy("001"), copy("002"))
        assertEquals(50, f.milestones.celebrate("s", "en")!!.level)
        // Undo the second card, then scan it again: the state is right, no second celebration.
        f.copies = listOf(copy("001"))
        assertNull(f.milestones.celebrate("s", "en"))
        f.copies = listOf(copy("001"), copy("002"))
        assertNull(f.milestones.celebrate("s", "en"))
    }

    @Test
    fun aJumpCelebratesTheHighestAndMarksTheOnesItSkipped_perLanguage() = runBlocking {
        val f = Fixture(emptyList())
        f.milestones.seed()
        f.copies = listOf("001", "002", "003", "004").map { copy(it) } + copy("001", "de")

        val complete = f.milestones.celebrate("s", "en")!!
        assertEquals(100, complete.level)
        assertTrue(complete.complete)
        assertTrue(Milestones.key("s", "en", 25) in f.celebrated)
        // German is its own collection of the set.
        assertEquals(25, f.milestones.celebrate("s", "de")!!.level)
    }

    @Test
    fun progressFromBeforeTheFeatureIsNotCelebrated() = runBlocking {
        val f = Fixture(listOf(copy("001"), copy("002")))
        f.milestones.seed()
        assertNull(f.milestones.celebrate("s", "en"))

        f.copies = f.copies + copy("003")
        assertEquals(75, f.milestones.celebrate("s", "en")!!.level)
    }
}
