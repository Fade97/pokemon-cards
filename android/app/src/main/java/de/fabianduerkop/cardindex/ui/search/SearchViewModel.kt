// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.fabianduerkop.cardindex.data.db.CardSearchHit
import de.fabianduerkop.cardindex.data.search.CardSearch
import de.fabianduerkop.cardindex.data.search.SearchTerms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the search list shows below the field. */
sealed interface SearchResults {
    /** Nothing typed yet. */
    data object Idle : SearchResults

    /** The first search for this text is still running. */
    data object Loading : SearchResults

    /**
     * [hits] may be empty (no match); [more] when the list was cut at one page. [query] is the text they were found
     * for: while the field already holds newer text, the list is stale and cannot be picked from (#221).
     */
    data class Found(val hits: List<CardSearchHit>, val more: Boolean, val query: String = "") : SearchResults

    /** The local catalog could not be read. */
    data object Failed : SearchResults
}

data class SearchUiState(
    /** Installed card languages, in a stable order; null until the packs are known. */
    val languages: List<String>? = null,
    val results: SearchResults = SearchResults.Idle,
)

/**
 * Card search (#99) over the installed language packs. The typed text lives in the saved state, so it survives
 * opening a card and coming back, and process death. Results follow the database, so a copy added from a result's
 * detail screen shows in the owned count when the user comes back.
 */
class SearchViewModel(
    private val search: CardSearch,
    installedLanguages: Flow<Set<String>>,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val query: StateFlow<String> = saved.getStateFlow(KEY_QUERY, "")

    private val retries = MutableStateFlow(0)

    /** Pages of [CardSearch.PAGE] shown for the current query; "Show more" adds one, a new query starts at one (#218). */
    private val pages = MutableStateFlow(1)
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(query, installedLanguages.distinctUntilChanged(), retries, pages) { q, languages, _, shown ->
                Triple(q, languages.sorted(), shown)
            }.collectLatest { (q, languages, shown) ->
                _state.update { it.copy(languages = languages) }
                val terms = SearchTerms.parse(q)
                if (terms == null || languages.isEmpty()) {
                    _state.update { it.copy(results = SearchResults.Idle) }
                    return@collectLatest
                }
                // Keep the previous list while typing; a first search, or one after Idle or Failed, shows Loading.
                _state.update { if (it.results is SearchResults.Found) it else it.copy(results = SearchResults.Loading) }
                delay(DEBOUNCE_MS)
                try {
                    search.observe(terms, languages, limit = CardSearch.PAGE * shown, query = q).collect { page ->
                        _state.update { it.copy(results = SearchResults.Found(page.hits, page.more, q)) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(results = SearchResults.Failed) }
                }
            }
        }
    }

    fun setQuery(text: String) {
        val next = text.take(MAX_QUERY_LENGTH)
        if (next != query.value) pages.value = 1
        saved[KEY_QUERY] = next
    }

    /** The next [CardSearch.PAGE] results of the same query, so no match is out of reach (#218). */
    fun showMore() {
        pages.value++
    }

    fun retry() {
        retries.value++
    }

    private companion object {
        const val KEY_QUERY = "query"
        const val DEBOUNCE_MS = 150L
    }
}

/** The longest query the search reads; the field takes no more, so it never shows text the search ignores (#250). */
internal const val MAX_QUERY_LENGTH = 60
