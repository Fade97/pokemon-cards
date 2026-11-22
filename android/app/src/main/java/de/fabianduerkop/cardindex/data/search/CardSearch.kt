// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.search

import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardSearchHit
import de.fabianduerkop.cardindex.data.db.CatalogDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * What the user typed, split into a name part and a collector number (#99). `pikachu`, `151` (a set name and a
 * number), `25`, `025/165`, `TG05`, and `charizard 199` (name and number together) all work.
 */
data class SearchTerms(
    /** Card or set name to look for, as typed; null for a number on its own after a name. */
    val text: String?,
    /** Collector number without the printed total and leading zeros, upper case; null if there is none. */
    val number: String?,
    /** A card must match [text] and [number] together, not either one. */
    val both: Boolean,
    /** The printed total typed after the number (`025/165` → 165): only sets of that size. */
    val total: Int? = null,
) {
    companion object {
        private const val MAX_NUMBER = 10

        /** Null when there is nothing to search for. */
        fun parse(input: String): SearchTerms? {
            val tokens = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val last = numberOf(tokens.last())
            val total = if (last != null) totalOf(tokens.last()) else null
            return when {
                tokens.size >= 2 && last != null -> SearchTerms(tokens.dropLast(1).joinToString(" "), last, both = true, total)
                tokens.size == 1 && last != null -> SearchTerms(tokens.single(), last, both = false, total)
                else -> SearchTerms(tokens.joinToString(" "), null, both = false)
            }
        }

        /** `#025/165` → `25`, `tg05` → `TG05`; null when the token is no collector number (no digit, other signs). */
        internal fun numberOf(token: String): String? {
            val id = token.removePrefix("#").substringBefore('/')
            if (id.isEmpty() || id.length > MAX_NUMBER || id.none(Char::isDigit)) return null
            if (!id.all { it.isLetterOrDigit() || it == '-' }) return null
            return CardEntity.numberKey(id)
        }

        /** `025/165` → 165; null without a total or with one that is not a plain number. */
        internal fun totalOf(token: String): Int? =
            token.substringAfter('/', "").takeIf { it.isNotEmpty() && it.length <= 4 && it.all(Char::isDigit) }?.toInt()

        /** Escapes `%`, `_` and `\` for a LIKE pattern with `ESCAPE '\'`. */
        internal fun escapeLike(text: String): String =
            text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

/** One page of search results; [more] when there were more matches than the page holds. */
data class SearchPage(val hits: List<CardSearchHit>, val more: Boolean)

/** Searches the cards of the installed language packs in the local catalog. Nothing leaves the device. */
class CardSearch(private val dao: CatalogDao) {
    /**
     * One query per shape of [terms] (#124), so a name with a number can look the number up in its index; all three
     * share the filter and the order of [CatalogDao.searchByName]. A [query] that is exactly a set's name, such as
     * "POP Series 5", searches that set instead of splitting off "5" as the collector number (#220).
     */
    fun observe(terms: SearchTerms, languages: Collection<String>, limit: Int = PAGE, query: String? = null): Flow<SearchPage> {
        val whole = query?.trim()?.replace(Regex("\\s+"), " ")
        if (terms.both && whole != null) {
            return flow {
                val asSet = dao.hasSetNamed(whole, languages.toList())
                emitAll(observeTerms(if (asSet) SearchTerms(whole, null, both = false) else terms, languages, limit))
            }
        }
        return observeTerms(terms, languages, limit)
    }

    private fun observeTerms(terms: SearchTerms, languages: Collection<String>, limit: Int): Flow<SearchPage> {
        val escaped = terms.text?.let(SearchTerms::escapeLike)
        val text = escaped?.let { "%$it%" }
        val prefix = escaped?.let { "$it%" }
        val langs = languages.toList()
        val number = terms.number
        val rows = when {
            number != null && terms.both -> dao.searchByNameAndNumber(text, prefix, number, terms.total, langs, limit + 1)
            number != null -> dao.searchByNameOrNumber(text, prefix, number, terms.total, langs, limit + 1)
            else -> dao.searchByName(text, prefix, terms.total, langs, limit + 1)
        }
        return rows.map { SearchPage(it.take(limit), more = it.size > limit) }
    }

    companion object {
        const val PAGE = 50
    }
}
