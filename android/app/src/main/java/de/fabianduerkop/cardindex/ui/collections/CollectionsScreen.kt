// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.db.CollectionSummary
import de.fabianduerkop.cardindex.data.prices.HistoryRange
import de.fabianduerkop.cardindex.debug.DebugTools
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.TopLevelScaffold
import de.fabianduerkop.cardindex.ui.components.numberOverTotal
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.theme.TabularFigures
import kotlinx.coroutines.launch

/** Collections: the value header (from direction Slab, ADR 0010), the set collections and the user's own ones. */
@Composable
fun CollectionsScreen(
    onOpenSet: (String) -> Unit,
    onOpenCollection: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollectionsViewModel = viewModel(
        factory = containerViewModelFactory { container, _ ->
            CollectionsViewModel(
                container.collections,
                container.prices,
                container.primaryPriceSource,
                container.priceHistory,
                container.entitlements.isPro,
            )
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val portfolio by viewModel.portfolio.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }

    TopLevelScaffold(
        title = stringResource(R.string.nav_collections),
        modifier = modifier,
        actions = { TextButton(onClick = { creating = true }) { Text(stringResource(R.string.collections_new)) } },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            if (!state.loading && state.totalCopies == 0) {
                item { EmptyCollections() }
            } else if (!state.loading) {
                item {
                    ValueHeader(
                        state = state,
                        portfolio = portfolio,
                        onPortfolioRange = viewModel::setPortfolioRange,
                        onOpenAll = { onOpenCollection(CollectionsRoutes.ALL_CARDS) },
                    )
                }
                item {
                    LanguageChips(
                        languages = state.languages,
                        selected = state.selectedLanguages,
                        onToggle = viewModel::toggleLanguage,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (state.sets.isEmpty()) {
                    item {
                        PlaceholderText(
                            stringResource(R.string.collections_no_sets_in_language),
                            Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
                        )
                    }
                }
                items(state.sets, key = { "set-" + it.completion.setId }) { row ->
                    SetRow(row, onClick = { onOpenSet(row.completion.setId) })
                }
                item { SectionLabel(stringResource(R.string.collections_yours), Modifier.padding(top = 16.dp)) }
                item {
                    CollectionRow(
                        name = stringResource(R.string.collections_all_cards),
                        cards = state.totalCopies,
                        onClick = { onOpenCollection(CollectionsRoutes.ALL_CARDS) },
                    )
                }
                items(state.collections, key = { "col-" + it.id }) { collection ->
                    CollectionRow(collection, onClick = { onOpenCollection(collection.id) })
                }
            }
        }
    }

    if (creating) {
        CollectionSheet(
            initial = null,
            onDismiss = { creating = false },
            onSave = { name, description ->
                viewModel.createCollection(name, description)
                creating = false
            },
        )
    }
}

/**
 * The value header from direction Slab (ADR 0010): what the collection is worth in the main market, with Pro the
 * portfolio chart under it (#17).
 */
@Composable
private fun ValueHeader(
    state: CollectionsUiState,
    portfolio: HistoryUiState?,
    onPortfolioRange: (HistoryRange) -> Unit,
    onOpenAll: () -> Unit,
) {
    Column(Modifier.padding(horizontal = ScreenPadding)) {
        Text(
            text = stringResource(
                if (state.source == PriceSource.CARDMARKET) {
                    R.string.collections_value_label_cardmarket
                } else {
                    R.string.collections_value_label_tcgplayer
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ValueBlock(
            value = state.value,
            caption = pluralStringResource(R.plurals.collections_summary, state.totalCopies, state.totalCopies),
            onCaptionClick = onOpenAll,
            priceDate = state.priceDate,
        )
        if (portfolio != null) {
            PriceHistoryBlock(portfolio, onRange = onPortfolioRange, modifier = Modifier.padding(top = 12.dp))
            Text(
                stringResource(R.string.history_portfolio_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 10.dp))
    }
}

@Composable
private fun SetRow(row: SetRowUi, onClick: () -> Unit) {
    val set = row.completion
    val description = pluralStringResource(
        R.plurals.collections_set_row, set.printedTotal, set.name, set.ownedNumbers, set.printedTotal,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = ScreenPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f)) {
                Text(
                    text = set.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                )
                row.value?.takeIf { it.amountMinor > 0 }?.let { value ->
                    Text(
                        text = Money.format(value.amountMinor, value.currency),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp).alignByBaseline(),
                    )
                }
                if (set.region == Region.JP) {
                    Text(
                        text = "JP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).alignByBaseline(),
                    )
                }
            }
            Text(
                text = numberOverTotal(
                    number = set.ownedNumbers.toString(),
                    total = set.printedTotal.toString(),
                    totalColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                style = MaterialTheme.typography.titleMedium.merge(TextStyle(fontFeatureSettings = TabularFigures)),
            )
        }
        SlotStrip(set.printedTotal, row.ownedSlots, Modifier.padding(bottom = 14.dp))
        HorizontalDivider()
    }
}

@Composable
private fun CollectionRow(collection: CollectionSummary, onClick: () -> Unit) =
    CollectionRow(name = collection.name, cards = collection.cards, onClick = onClick)

@Composable
private fun CollectionRow(name: String, cards: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = ScreenPadding)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                pluralStringResource(R.plurals.collections_cards_count, cards, cards),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun EmptyCollections() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = ScreenPadding, vertical = 24.dp)) {
        Text(stringResource(R.string.collections_empty_title), style = MaterialTheme.typography.headlineSmall)
        PlaceholderText(stringResource(R.string.collections_empty_body), Modifier.padding(top = 8.dp))
        if (DebugTools.AVAILABLE) {
            OutlinedButton(
                onClick = { scope.launch { DebugTools.addSampleData(context.appContainer) } },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text(stringResource(R.string.collections_debug_sample)) }
        }
    }
}
