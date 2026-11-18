// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
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
import de.fabianduerkop.cardindex.ui.components.NoticeTone
import de.fabianduerkop.cardindex.ui.components.StatusNotice
import de.fabianduerkop.cardindex.ui.components.TopLevelScaffold
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlinx.coroutines.flow.map

/**
 * Search (#99, Pearl study "Search cards"): find any card of the installed language packs by name, set or collector
 * number, then open it to see its price or add a copy. Manual add lives here and on the card detail (ADR 0014).
 */
@Composable
fun SearchScreen(
    onOpenCard: (cardId: String, language: String) -> Unit,
    onOpenLanguages: () -> Unit,
    modifier: Modifier = Modifier,
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

    TopLevelScaffold(title = stringResource(R.string.nav_search), modifier = modifier) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = padding,
        ) {
            item {
                Column(Modifier.padding(horizontal = Spacing.gutter)) {
                    Text(
                        stringResource(R.string.search_title),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    SearchField(
                        value = text,
                        onValueChange = {
                            text = it
                            viewModel.setQuery(it)
                        },
                        onSearch = { keyboard?.hide() },
                        modifier = Modifier.padding(top = Spacing.lg),
                    )
                    val languages = state.languages
                    if (!languages.isNullOrEmpty()) {
                        Text(
                            stringResource(R.string.search_scope, languageList(languages, stringResource(R.string.collections_and))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.sm),
                        )
                    }
                }
            }
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
                else -> results(state.results, onOpenCard = onOpenCard, onRetry = viewModel::retry)
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
    onOpenCard: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    when (results) {
        SearchResults.Idle -> item { Note(stringResource(R.string.search_hint)) }
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
        is SearchResults.Found -> if (results.hits.isEmpty()) {
            item { NoMatch() }
        } else {
            item {
                Text(
                    if (results.more) {
                        pluralStringResource(R.plurals.search_more, results.hits.size, results.hits.size)
                    } else {
                        pluralStringResource(R.plurals.search_count, results.hits.size, results.hits.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xl, bottom = Spacing.xs),
                )
                HorizontalDivider(Modifier.padding(horizontal = Spacing.gutter), color = MaterialTheme.colorScheme.outlineVariant)
            }
            items(results.hits, key = { it.card.id + "/" + it.card.language }) { hit ->
                SearchResultRow(hit, onClick = { onOpenCard(hit.card.id, hit.card.language) })
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

@Composable
private fun NoMatch() {
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
            modifier = Modifier.padding(top = Spacing.md).semantics { heading() },
        )
        Text(
            stringResource(R.string.search_none_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/**
 * One found card: its art (or the numbered placeholder), the collector number as the strongest text, the name, then
 * set and language, and how many copies of this print the user owns. One button for TalkBack.
 */
@Composable
private fun SearchResultRow(hit: CardSearchHit, onClick: () -> Unit) {
    val card = hit.card
    Column(
        Modifier
            .fillMaxWidth()
            .focusRing(MaterialTheme.shapes.small, gap = (-2).dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardImage(card = card, localId = card.localId, printedTotal = hit.printedTotal, modifier = Modifier.width(44.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CollectorNumber(
                        localId = card.localId,
                        printedTotal = hit.printedTotal,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (hit.owned > 0) {
                        Text(
                            pluralStringResource(R.plurals.search_owned, hit.owned, hit.owned),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(card.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(hit.setName, languageName(card.language)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
