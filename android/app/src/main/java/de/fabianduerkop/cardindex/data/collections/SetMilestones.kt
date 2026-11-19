// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.collections

import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A set milestone just reached (#169): [level] percent of [set]'s base numbers in [language]. */
data class Milestone(val set: CardSetEntity, val language: String, val level: Int, val progress: SetProgress) {
    val complete: Boolean get() = level == 100
}

/**
 * Set milestones (#169): 25, 50, 75 and 100 % of a set's base numbers (1 to the printed total) in one language. Secret
 * rares count apart and never push a set over a milestone. Filling a set is the collector's ritual, so each milestone
 * is celebrated once per set and language: Undo takes the progress back, redoing the card does not celebrate again.
 */
object Milestones {
    val LEVELS = listOf(25, 50, 75, 100)

    /** The milestones [progress] has reached, lowest first. */
    fun reached(progress: SetProgress): List<Int> =
        if (progress.printedTotal <= 0) emptyList() else LEVELS.filter { progress.base * 100 >= it * progress.printedTotal }

    fun key(setId: String, language: String, level: Int): String = "$setId|$language|$level"

    /** The progress of [copies] (one set, one language) as the set header counts it. */
    fun progressOf(copies: List<CopyWithCard>): SetProgress? {
        val set = copies.firstOrNull()?.set ?: return null
        return setProgress(copies.map { it.card.localId }, set.printedTotal)
    }

    /** Every milestone [copies] have reached, per set and language, as [key]s: what an update marks as celebrated. */
    fun reachedKeys(copies: List<CopyWithCard>): Set<String> =
        copies.groupBy { it.card.setId to it.copy.cardLanguage }.flatMapTo(HashSet()) { (pair, group) ->
            val progress = progressOf(group) ?: return@flatMapTo emptyList()
            reached(progress).map { key(pair.first, pair.second, it) }
        }
}

/**
 * Decides and remembers celebrations (#169). [celebrated] and [seeded] are the persisted state; [allCopies] and
 * [copiesInSet] read the collection. The first use marks every milestone already reached as celebrated, so people who
 * owned cards before this shipped don't get a burst of old celebrations.
 */
class SetMilestones(
    private val celebrated: Store<Set<String>>,
    private val seeded: Store<Boolean>,
    private val allCopies: suspend () -> List<CopyWithCard>,
    private val copiesInSet: suspend (setId: String) -> List<CopyWithCard>,
) {
    /** A persisted value; AppSettings in the app, plain variables in tests. */
    class Store<T>(val get: () -> T, val set: (T) -> Unit)

    private val mutex = Mutex()

    /** Marks old milestones as celebrated, once. Call early (screen start), before the first new card. */
    suspend fun seed() = mutex.withLock { seedLocked() }

    /**
     * The highest new milestone of [setId] in [language], now marked as celebrated together with any lower ones it
     * skipped; null when nothing new was reached.
     */
    suspend fun celebrate(setId: String, language: String): Milestone? = mutex.withLock {
        seedLocked()
        val copies = copiesInSet(setId).filter { it.copy.cardLanguage == language }
        val progress = Milestones.progressOf(copies) ?: return@withLock null
        val done = celebrated.get()
        val fresh = Milestones.reached(progress).filter { Milestones.key(setId, language, it) !in done }
        if (fresh.isEmpty()) return@withLock null
        celebrated.set(done + fresh.map { Milestones.key(setId, language, it) })
        Milestone(copies.first().set, language, fresh.max(), progress)
    }

    private suspend fun seedLocked() {
        if (seeded.get()) return
        celebrated.set(celebrated.get() + Milestones.reachedKeys(allCopies()))
        seeded.set(true)
    }
}
