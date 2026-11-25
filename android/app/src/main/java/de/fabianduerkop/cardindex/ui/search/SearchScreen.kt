// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.db.CardSearchHit
import de.fabianduerkop.cardindex.data.search.CardSearch
import de.fabianduerkop.cardindex.ui.collections.languageList
import de.fabianduerkop.cardindex.ui.collections.languageName
import de.fabianduerkop.cardindex.ui.components.CardImage
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.DetailScaffold
import de.fabianduerkop.cardindex.ui.components.JoinedText
import de.fabianduerkop.cardindex.ui.components.NoticeTone
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.StatusNotice
import de.fabianduerkop.cardindex.ui.components.TopLevelScaffold
import de.fabianduerkop.cardindex.ui.components.collectorNumberDescription
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlinx.coroutines.flow.map

/**
 * Search (#99, Pearl study "Search cards"): find any card of the installed language packs by name, set or collector
 * number, then open it to see its price or add a copy. Manual add lives here and on the card detail (ADR 0014).
 *
 * With [onBack] the screen is a picker opened from another screen (Review's "Find the card", #129): it gets a back
 * button and [title], and [onOpenCard] hands the chosen card back instead of opening it.
 */
@Composable
fun SearchScreen(
    onOpenCard: (cardId: String, language: String) -> Unit,
    onOpenLanguages: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    title: String = stringResource(R.string.search_title),
    /** The way out of "No cards found" (#174); null where scanning makes no sense, such as picking a card in Review. */
    onOpenScan: (() -> Unit)? = null,
    viewModel: SearchViewModel = viewModel(
        factory = containerViewModelFactory { container, saved ->
            SearchViewModel(
                CardSearch(container.database.catalogDao()),
                container.packs.installed.map { it.packs.keys },
                saved,
            )
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(viewModel.query.value) }
    val keyboard = LocalSoftwareKeyboardController.current

    val scaffold: @Composable (@Composable (PaddingValues) -> Unit) -> Unit = { content ->
        if (onBack != null) {
            DetailScaffold(title = title, onBack = onBack, modifier = modifier, pinnedBelow = true, content = content)
        } else {
            TopLevelScaffold(title = stringResource(R.string.nav_search), modifier = modifier, pinnedBelow = true, content = content)
        }
    }
    val current by viewModel.query.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
    // A new query's results start at their best match; "Show more" and coming back from a card keep the place (#221).
    val foundQuery = (state.results as? SearchResults.Found)?.query
    var shownQuery by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(foundQuery) {
        if (foundQuery != null && foundQuery != shownQuery) {
            if (shownQuery != null) listState.scrollToItem(0)
            shownQuery = foundQuery
        }
    }
    scaffold { padding ->
        // The field stays pinned above the results, so a new query is one tap away after scrolling (#221).
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            // No headline under the bar: it named the screen a second time ("Search", then "Find a card", #218).
            // With results under it, the field takes the bar's raised tint and the edge moves below it, where the
            // rows actually pass (#221).
            val colors = MaterialTheme.colorScheme
            Column(Modifier.fillMaxWidth().background(if (scrolled) colors.surfaceContainer else colors.background)) {
                Column(Modifier.padding(horizontal = Spacing.gutter).padding(bottom = Spacing.sm)) {
                    SearchField(
                        value = text,
                        onValueChange = {
                            text = it.take(MAX_QUERY_LENGTH)
                            viewModel.setQuery(text)
                        },
                        onSearch = { keyboard?.hide() },
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                    val languages = state.languages
                    if (!languages.isNullOrEmpty()) {
                        Text(
                            // Up to two languages by name; more as a count, so the pinned block stays one line and
                            // results fit above the keyboard at a large font. The names are in the hint (#250).
                            if (languages.size <= 2) {
                                stringResource(R.string.search_scope, languageList(languages, stringResource(R.string.collections_and)))
                            } else {
                                pluralStringResource(R.plurals.search_scope_count, languages.size, languages.size)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.sm),
                        )
                    }
                }
                HorizontalDivider(color = if (scrolled) colors.outlineVariant else Color.Transparent)
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
            ) {
                val languages = state.languages
                when {
                    languages == null -> Unit
                    languages.isEmpty() -> item {
                        StatusNotice(
                            stringResource(R.string.search_no_languages),
                            tone = NoticeTone.WARNING,
                            actionLabel = stringResource(R.string.search_choose_languages),
                            onAction = onOpenLanguages,
                            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.lg),
                        )
                    }
                    else -> results(
                        state.results,
                        languages = state.languages.orEmpty(),
                        currentQuery = current,
                        onOpenCard = onOpenCard,
                        onRetry = viewModel::retry,
                        onOpenScan = onOpenScan,
                        onShowMore = viewModel::showMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.search_field)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_nav_search), contentDescription = null) },
        trailingIcon = if (value.isEmpty()) null else {
            {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.focusRing(CircleShape)) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.search_clear))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun LazyListScope.results(
    results: SearchResults,
    languages: List<String>,
    currentQuery: String,
    onOpenCard: (String, String) -> Unit,
    onRetry: () -> Unit,
    onOpenScan: (() -> Unit)?,
    onShowMore: () -> Unit,
) {
    when (results) {
        SearchResults.Idle -> item { SearchHint(languages) }
        SearchResults.Loading -> item { Note(stringResource(R.string.search_loading)) }
        SearchResults.Failed -> item {
            StatusNotice(
                stringResource(R.string.search_failed),
                tone = NoticeTone.ERROR,
                actionLabel = stringResource(R.string.packs_retry),
                onAction = onRetry,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.lg),
            )
        }
        // An older query's "No cards found" never stands under new text: the search is still running (#250).
        is SearchResults.Found -> if (results.hits.isEmpty() && results.query != currentQuery) {
            item { Note(stringResource(R.string.search_loading)) }
        } else if (results.hits.isEmpty()) {
            item { NoMatch(onOpenScan) }
        } else {
            // Hits of older text while the next search runs: shown faded, not pickable, so a tap never opens a card
            // the field no longer asks for (#221). Their count is not said for the new text either (#250).
            val stale = results.query != currentQuery
            item {
                Text(
                    if (stale) {
                        stringResource(R.string.search_loading)
                    } else if (results.more) {
                        pluralStringResource(R.plurals.search_more, results.hits.size, results.hits.size)
                    } else {
                        pluralStringResource(R.plurals.search_count, results.hits.size, results.hits.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Announced as it changes, so TalkBack users typing in the field hear what the query found (#218).
                    modifier = Modifier
                        // A smaller gap than before (#221): with the keyboard open at large font, a third result fits.
                        .padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.md, bottom = Spacing.sm)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                HorizontalDivider(Modifier.padding(horizontal = Spacing.gutter), color = MaterialTheme.colorScheme.outlineVariant)
            }
            items(results.hits, key = { it.card.id + "/" + it.card.language }) { hit ->
                SearchResultRow(hit, enabled = !stale, onClick = { onOpenCard(hit.card.id, hit.card.language) })
            }
            // The list is cut at a page: the next page of the same query, so a broad query never hides a match (#218).
            if (results.more) {
                item {
                    SecondaryButton(
                        stringResource(R.string.search_show_more),
                        onClick = onShowMore,
                        enabled = !stale,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.lg),
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
    )
}

/** What the field takes, with examples that break only between them (#250). */
@Composable
private fun SearchHint(languages: List<String>) {
    val style = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xl)) {
        Text(stringResource(R.string.search_hint), style = style, color = color)
        JoinedText(stringArrayResource(R.array.search_hint_examples).toList(), style = style, color = color)
        Text(stringResource(R.string.search_hint_narrow), style = style, color = color, modifier = Modifier.padding(top = Spacing.lg))
        JoinedText(stringArrayResource(R.array.search_hint_narrow_examples).toList(), style = style, color = color)
        // With more than two languages the line above the results gives a count; the names are here (#250).
        if (languages.size > 2) {
            Text(
                stringResource(R.string.search_scope, languageList(languages, stringResource(R.string.collections_and))),
                style = style,
                color = color,
                modifier = Modifier.padding(top = Spacing.lg),
            )
        }
    }
}

@Composable
private fun NoMatch(onOpenScan: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_nav_search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Text(
            stringResource(R.string.search_none_title),
            style = MaterialTheme.typography.titleMedium,
            // Heard while typing, like the result count.
            modifier = Modifier.padding(top = Spacing.md).semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        )
        Text(
            stringResource(R.string.search_none_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        if (onOpenScan != null) {
            SecondaryButton(stringResource(R.string.search_none_scan), onClick = onOpenScan, modifier = Modifier.padding(top = Spacing.md))
        }
    }
}

/**
 * One found card: its art (or the numbered placeholder), the collector number as the strongest text, the name, then
 * set and language, and how many copies of this print the user owns. One button for TalkBack.
 */
@Composable
private fun SearchResultRow(hit: CardSearchHit, enabled: Boolean, onClick: () -> Unit) {
    val card = hit.card
    // One label for the whole row: merged, the number's own description would replace the name, set and language.
    val spoken = listOfNotNull(
        collectorNumberDescription(card.localId, hit.printedTotal),
        card.name,
        hit.setName,
        languageName(card.language),
        hit.owned.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.card_owned_copies, it, it) },
    ).joinToString(", ")
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else STALE_ALPHA)
            .focusRing(MaterialTheme.shapes.small, gap = (-2).dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardImage(card = card, localId = card.localId, printedTotal = hit.printedTotal, modifier = Modifier.width(44.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                CollectorNumber(
                    localId = card.localId,
                    printedTotal = hit.printedTotal,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(card.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // "151, English" as on Review's candidates and the card's own screen (#250).
                Text(
                    hit.setName?.let { stringResource(R.string.review_set_language, it, languageName(card.language)) } ?: languageName(card.language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hit.owned > 0) {
                    // Last, with the check, as on the card's own screen: number and name stay together at any font
                    // size, and the count reads as a fact about the print, not as part of "/165" (#250).
                    Row(Modifier.padding(top = Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            pluralStringResource(R.plurals.card_owned_copies, hit.owned, hit.owned),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs).size(20.dp),
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = Spacing.gutter), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private const val STALE_ALPHA = 0.5f
