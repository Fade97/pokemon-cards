// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.search

import de.fabianduerkop.cardindex.data.db.CardSearchHit
import de.fabianduerkop.cardindex.data.db.CatalogDao
import java.util.Locale
import kotlinx.coroutines.flow.Flow
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
            return id.trimStart('0').uppercase(Locale.ROOT)
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
    fun observe(terms: SearchTerms, languages: Collection<String>, limit: Int = PAGE): Flow<SearchPage> {
        val text = terms.text?.let(SearchTerms::escapeLike)
        return dao.searchCards(
            text = text?.let { "%$it%" },
            prefix = text?.let { "$it%" },
            number = terms.number,
            both = terms.both,
            total = terms.total,
            languages = languages.toList(),
            limit = limit + 1,
        ).map { rows -> SearchPage(rows.take(limit), more = rows.size > limit) }
    }

    companion object {
        const val PAGE = 50
    }
}
