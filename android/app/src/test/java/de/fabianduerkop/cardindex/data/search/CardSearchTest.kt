// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.db.SimpleSQLiteQuery
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.db.CardSearchHit
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Region
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

    /** #220: a set whose name ends in a number is found as the set, not as that card number. */
    @Test
    fun aSetNameEndingInANumberFindsTheWholeSet() = runTest {
        seed()
        db.catalogDao().insertSetsIfMissing(listOf(CardSetEntity("pop5", "POP Series 5", "POP", "en", 17, Region.INTERNATIONAL)))
        db.catalogDao().upsertCards(
            listOf(named(card("pop5-1", "pop5", "1", "en"), "Ho-Oh"), named(card("pop5-5", "pop5", "5", "en"), "Pikachu")),
        )
        val query = "pop  series 5"
        val set = search.observe(SearchTerms.parse(query)!!, setOf("en"), query = query).first()
        assertEquals(setOf("pop5-1", "pop5-5"), set.hits.map { it.card.id }.toSet())
        // A name with a number is still both: Pikachu number 25 only.
        val pika = search.observe(SearchTerms.parse("Pikachu 25")!!, setOf("en"), query = "Pikachu 25").first()
        assertEquals(listOf("sv03.5-025"), pika.hits.map { it.card.id })
    }

    /** #241: the set code printed on the card finds the set, alone or with a number, in any case. */
    @Test
    fun aSetCodeFindsItsSets_aloneOrWithANumber() = runTest {
        seed()
        db.catalogDao().insertSetsIfMissing(
            listOf(
                CardSetEntity("30th", "30th Celebration", "MEG", "en", 128, Region.INTERNATIONAL, code = "30C"),
                CardSetEntity("30th-c", "30th Classic Collection", "MEG", "en", 30, Region.INTERNATIONAL, code = "30C"),
            ),
        )
        db.catalogDao().upsertCards(
            listOf(
                named(card("30th-001", "30th", "001", "en"), "Bulbasaur"),
                named(card("30th-025", "30th", "025", "en"), "Pikachu"),
                named(card("30th-c-025", "30th-c", "025", "en"), "Charizard"),
            ),
        )
        fun ids(page: SearchPage) = page.hits.map { it.card.id }.toSet()

        assertEquals(setOf("30th-001", "30th-025", "30th-c-025"), ids(find("30C")))
        assertEquals(setOf("30th-025", "30th-c-025"), ids(find("30c 25")))
        assertEquals(setOf("30th-025"), ids(find("30C 025/128")))
        // A name is still a name: the 30C Pikachu is found with the others, not the whole set.
        assertEquals(setOf("sv03.5-025", "sv03.5-180", "30th-025"), ids(find("Pikachu", setOf("en"))))
    }

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

    @Test
    fun cardsCarryTheirNumberKey() {
        assertEquals("6", card("x-006", "x", "006", "en").numberKey)
        assertEquals("TG05", card("x-tg05", "x", "tg05", "en").numberKey)
        assertEquals("", card("x-000", "x", "000", "en").numberKey)
    }

    @Test
    fun numberMatchesFirst_thenNamesStartingWithTheText_thenByNameSetAndLanguage() = runTest {
        seedMixed()
        val hits = find("25", setOf("en", "de")).hits.map { "${it.card.language}/${it.card.id}" }
        // Number 25 first (by name, set name, language), then "25" in a card or set name (by card name).
        assertEquals(
            listOf("de/sv03.5-025", "en/sv03.5-025", "en/swsh-025", "en/xy-006", "en/xy-125", "en/xy-tg25b"),
            hits,
        )
    }

    /**
     * #124 rewrote the query for speed; results and their order must stay what the #99 query returned. The old query
     * runs here as a reference over a catalog with every kind of match, page sizes 50 and 2.
     */
    @Test
    fun sameHitsInTheSameOrderAsTheQueryBefore124() = runTest {
        seedMixed()
        db.copyDao().insert(OwnedCopyEntity(cardId = "sv03.5-025", cardLanguage = "de", quantity = 2, createdAt = Instant.EPOCH))
        db.copyDao().insert(OwnedCopyEntity(cardId = "sv03.5-025", cardLanguage = "de", quantity = 1, createdAt = Instant.EPOCH))
        db.copyDao().insert(OwnedCopyEntity(cardId = "swsh-025", cardLanguage = "en", quantity = 1, createdAt = Instant.EPOCH))
        val queries = listOf(
            "p", "pi", "pikachu", "PIKACHU", "chu", "1", "15", "151", "0", "02", "025", "025/", "025/165", "25/202",
            "pikachu 25", "pikachu 025/165", "151 25", "mewthree", "tg25b", "tg", "100%", "_", "world", "ex", "Ex 199",
            "#6", "000",
        )
        for (languages in listOf(setOf("en", "de"), setOf("en", "de", "ja"), setOf("ja"))) {
            for (query in queries) {
                for (limit in listOf(CardSearch.PAGE, 2)) {
                    val terms = SearchTerms.parse(query)!!
                    val expected = before124(terms, languages, limit + 1)
                    val actual = search.observe(terms, languages, limit).first()
                    assertEquals("$query $languages $limit", expected.take(limit), actual.hits.map(::row))
                    assertEquals("$query $languages $limit", expected.size > limit, actual.more)
                }
            }
        }
    }

    private suspend fun seedMixed() {
        db.seedCatalog()
        db.catalogDao().upsertSets(
            listOf(
                CardSetEntity("swsh", "Sword & Shield", "SWSH", "en", 202, Region.INTERNATIONAL),
                CardSetEntity("xy", "Pikachu World Collection 25", "XY", "en", 146, Region.INTERNATIONAL),
            ),
        )
        db.catalogDao().upsertCards(
            listOf(
                named(card("sv03.5-025", "sv03.5", "025", "en"), "Pikachu"),
                named(card("sv03.5-025", "sv03.5", "025", "de"), "Pikachu"),
                named(card("sv03.5-026", "sv03.5", "026", "en"), "Raichu"),
                named(card("sv03.5-199", "sv03.5", "199", "en"), "Charizard ex"),
                named(card("sv03.5-180", "sv03.5", "180", "en"), "Pikachu 100%"),
                named(card("swsh-025", "swsh", "025", "en"), "Pikachu"),
                named(card("swsh-151", "swsh", "151", "en"), "Flying Pikachu"),
                named(card("swsh-199", "swsh", "199", "en"), "Pikachu ex"),
                named(card("xy-125", "xy", "125", "en"), "Detective Pikachu 25"),
                named(card("xy-tg25b", "xy", "TG25b", "en"), "Surfing Pikachu"),
                named(card("xy-006", "xy", "006", "en"), "Charmander"),
                named(card("xy-old", "xy", "07", "en"), "Old Pikachu").copy(retired = true),
                named(card("SV2a-025", "SV2a", "025", "ja"), "ピカチュウ"),
            ),
        )
    }

    private fun row(hit: CardSearchHit) = listOf(hit.card.id, hit.card.language, hit.setName, hit.printedTotal, hit.owned)

    /** The search query of #99, as it was before #124, with the same parameters. */
    private fun before124(terms: SearchTerms, languages: Set<String>, limit: Int): List<List<Any?>> {
        val escaped = terms.text?.let(SearchTerms::escapeLike)
        val langs = languages.joinToString(",") { "'$it'" }
        val sql = """
            SELECT c.id, c.language, s.name AS setName, s.printedTotal AS printedTotal,
                (SELECT COALESCE(SUM(o.quantity), 0) FROM owned_copies o
                    WHERE o.cardId = c.id AND o.cardLanguage = c.language) AS owned
            FROM cards c LEFT JOIN card_sets s ON s.id = c.setId
            WHERE c.retired = 0 AND c.language IN ($langs) AND (?5 IS NULL OR s.printedTotal = ?5) AND (
                CASE WHEN ?4 THEN
                    (c.name LIKE ?1 ESCAPE '\' OR s.name LIKE ?1 ESCAPE '\')
                        AND UPPER(LTRIM(c.localId, '0')) = ?3
                ELSE
                    (?1 IS NOT NULL AND (c.name LIKE ?1 ESCAPE '\' OR s.name LIKE ?1 ESCAPE '\'))
                        OR (?3 IS NOT NULL AND UPPER(LTRIM(c.localId, '0')) = ?3)
                END
            )
            ORDER BY (?3 IS NOT NULL AND UPPER(LTRIM(c.localId, '0')) = ?3) DESC,
                (c.name LIKE ?2 ESCAPE '\') DESC, c.name, s.name, c.language, LENGTH(c.localId), c.localId
            LIMIT ?6
        """
        val args = arrayOf<Any?>(
            escaped?.let { "%$it%" }, escaped?.let { "$it%" }, terms.number, if (terms.both) 1 else 0, terms.total, limit,
        )
        return db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(listOf(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getInt(3), c.getInt(4)))
                }
            }
        }
    }
}
