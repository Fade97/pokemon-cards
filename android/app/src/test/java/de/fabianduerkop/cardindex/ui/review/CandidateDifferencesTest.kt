// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.review

import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.scan.ReviewCandidate
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.testing.card
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateDifferencesTest {
    private val sv151 = CardSetEntity("sv03.5", "151", "Scarlet & Violet", "en", 165, Region.INTERNATIONAL)
    private val obsidian = CardSetEntity("sv03", "Obsidian Flames", "Scarlet & Violet", "en", 197, Region.INTERNATIONAL)

    private fun candidate(rank: Int, set: CardSetEntity, localId: String, language: String) =
        ReviewCandidate(rank, similarity = 0.9f, card = card("${set.id}-$localId", set.id, localId, language), set = set)

    @Test
    fun oneCardInTwoLanguages_differsOnlyInTheLanguage() {
        val differences = candidateDifferences(listOf(candidate(0, sv151, "006", "en"), candidate(1, sv151, "6", "de")))

        // "006" and "6" are the same number, so only the language tells them apart.
        assertEquals(CandidateDifferences(number = false, set = false, language = true), differences)
    }

    @Test
    fun cardsFromTwoSets_differInSetAndNumber() {
        val differences = candidateDifferences(listOf(candidate(0, sv151, "6", "en"), candidate(1, obsidian, "125", "en")))

        assertEquals(CandidateDifferences(number = true, set = true, language = false), differences)
    }

    @Test
    fun aSingleCandidate_differsInNothing() {
        assertEquals(CandidateDifferences.None, candidateDifferences(listOf(candidate(0, sv151, "6", "en"))))
        assertEquals(CandidateDifferences.None, candidateDifferences(emptyList()))
    }
}
