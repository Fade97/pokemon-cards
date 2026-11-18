// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.testing.card
import de.fabianduerkop.cardindex.testing.inMemoryDatabase
import de.fabianduerkop.cardindex.testing.seedCatalog
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

class SearchTermsTest {
    @Test
    fun parse_nameOnly() {
        assertEquals(SearchTerms("Pikachu", null, both = false), SearchTerms.parse("  Pikachu "))
        assertEquals(SearchTerms("Mr. Mime", null, both = false), SearchTerms.parse("Mr.  Mime"))
    }

    @Test
    fun parse_aNumberAloneMatchesTheNumberOrANameLike151() {
        assertEquals(SearchTerms("151", "151", both = false), SearchTerms.parse("151"))
        assertEquals(SearchTerms("025/165", "25", both = false, total = 165), SearchTerms.parse("025/165"))
        assertEquals(SearchTerms("#6", "6", both = false), SearchTerms.parse("#6"))
        assertEquals(SearchTerms("tg05", "TG05", both = false), SearchTerms.parse("tg05"))
    }

    @Test
    fun parse_aNumberAfterANameNeedsBoth() {
        assertEquals(SearchTerms("Charizard ex", "199", both = true, total = 165), SearchTerms.parse("Charizard ex 199/165"))
        assertNull(SearchTerms.totalOf("199/"))
    }

    @Test
    fun parse_nothingOrNoNumber() {
        assertNull(SearchTerms.parse("   "))
        assertNull(SearchTerms.numberOf("ex"))
        assertNull(SearchTerms.numberOf("2.5"))
        assertEquals("", SearchTerms.numberOf("000"))
    }

    @Test
    fun escapeLike_keepsWildcardsLiteral() {
        assertEquals("100\\%", SearchTerms.escapeLike("100%"))
        assertEquals("a\\_b\\\\", SearchTerms.escapeLike("a_b\\"))
    }
}

@RunWith(AndroidJUnit4::class)
class CardSearchTest {
    private lateinit var db: CardIndexDatabase
    private lateinit var search: CardSearch

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        search = CardSearch(db.catalogDao())
    }

    @After
    fun tearDown() = db.close()

    private fun named(base: CardEntity, name: String) = base.copy(name = name)

    private suspend fun seed() {
        db.seedCatalog()
        db.catalogDao().upsertCards(
            listOf(
                named(card("sv03.5-025", "sv03.5", "025", "en"), "Pikachu"),
                named(card("sv03.5-025", "sv03.5", "025", "de"), "Pikachu"),
                named(card("sv03.5-006", "sv03.5", "006", "en"), "Charmeleon"),
                named(card("sv03.5-180", "sv03.5", "180", "en"), "Pikachu 100%"),
            ),
        )
    }

    private suspend fun find(input: String, languages: Set<String> = setOf("en", "de"), limit: Int = CardSearch.PAGE) =
        search.observe(SearchTerms.parse(input)!!, languages, limit).first()

    @Test
    fun byName_caseInsensitive_onlyInTheGivenLanguages() = runTest {
        seed()
        val hits = find("pika", setOf("en")).hits
        assertEquals(listOf("sv03.5-025", "sv03.5-180"), hits.map { it.card.id })
        assertTrue(hits.all { it.card.language == "en" })
        assertEquals("151", hits.first().setName)
        assertEquals(165, hits.first().printedTotal)
    }

    @Test
    fun byNumber_ignoresLeadingZerosAndTheTotal_numberMatchesFirst() = runTest {
        seed()
        val hits = find("6/165").hits
        assertEquals(listOf("sv03.5-006" to "de", "sv03.5-006" to "en"), hits.map { it.card.id to it.card.language })
    }

    @Test
    fun aTypedTotalKeepsOnlySetsOfThatSize() = runTest {
        seed()
        assertEquals(2, find("6/165").hits.size)
        assertTrue(find("6/102").hits.isEmpty())
    }

    @Test
    fun aSetNameAndANumberAloneBothMatch() = runTest {
        seed()
        // "151" is the set name of every seeded card; no card has number 151.
        assertEquals(6, find("151").hits.size)
    }

    @Test
    fun nameAndNumberTogether_narrowToOneCard() = runTest {
        seed()
        val hits = find("pikachu 25", setOf("en")).hits
        assertEquals(listOf("sv03.5-025"), hits.map { it.card.id })
    }

    @Test
    fun wildcardsInTheTextAreLiteral() = runTest {
        seed()
        assertEquals(listOf("sv03.5-180"), find("100%").hits.map { it.card.id })
        assertTrue(find("_").hits.isEmpty())
    }

    @Test
    fun retiredCardsAreLeftOut_noMatchIsEmpty() = runTest {
        seed()
        db.catalogDao().retireCardsInLanguage("de")
        assertTrue(find("pikachu", setOf("de")).hits.isEmpty())
        assertTrue(find("mewtwo").hits.isEmpty())
    }

    @Test
    fun countsOwnedCopiesOfThatPrint_andSaysWhenThePageIsFull() = runTest {
        seed()
        db.copyDao().insert(OwnedCopyEntity(cardId = "sv03.5-025", cardLanguage = "de", quantity = 2, createdAt = Instant.EPOCH))
        val hits = find("pikachu 25").hits.associateBy { it.card.language }
        assertEquals(2, hits.getValue("de").owned)
        assertEquals(0, hits.getValue("en").owned)

        val page = find("151", limit = 2)
        assertEquals(2, page.hits.size)
        assertTrue(page.more)
        assertFalse(find("pikachu 25", limit = 2).more)
    }
}
