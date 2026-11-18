// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.components.ChoiceChip
import de.fabianduerkop.cardindex.ui.components.DetailScaffold
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.containerViewModelFactory

/** A custom collection or All cards: the copies in it, sortable. Tapping a copy opens its card. */
@Composable
fun CollectionDetailScreen(
    onBack: () -> Unit,
    onOpenCard: (cardId: String, language: String) -> Unit,
    viewModel: CollectionDetailViewModel = viewModel(
        factory = containerViewModelFactory { container, saved ->
            CollectionDetailViewModel(container.collections, container.prices, container.primaryPriceSource, saved)
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    val collection = state.collection

    DetailScaffold(
        title = collection?.name ?: stringResource(R.string.collections_all_cards),
        onBack = onBack,
        actions = {
            if (collection != null) {
                TextButton(onClick = { editing = true }) { Text(stringResource(R.string.collections_edit)) }
            }
        },
    ) { padding ->
        when {
            state.loading -> Unit
            state.missing -> PlaceholderText(
                stringResource(R.string.collections_set_unknown),
                Modifier.padding(padding).padding(ScreenPadding),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item {
                    Column(Modifier.padding(horizontal = ScreenPadding)) {
                        collection?.description?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ValueBlock(
                            value = state.value,
                            caption = pluralStringResource(R.plurals.collections_cards_count, state.cardCount, state.cardCount),
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        HorizontalDivider()
                    }
                }
                item {
                    Row(
                        Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.collections_sort_by),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CopySort.entries.forEach { sort ->
                            ChoiceChip(state.sort == sort, { viewModel.setSort(sort) }, stringResource(sort.label()))
                        }
                    }
                }
                if (state.copies.isEmpty()) {
                    item {
                        PlaceholderText(
                            stringResource(R.string.collections_collection_empty),
                            Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
                        )
                    }
                }
                items(state.copies, key = { it.copy.id }) { item ->
                    CopyRow(
                        item = item,
                        memberships = emptyList(),
                        showCard = true,
                        onClick = { onOpenCard(item.card.id, item.card.language) },
                        trailing = { QuoteColumn(state.quotes[item.copy.id], languageName(item.card.language)) },
                    )
                }
            }
        }
    }

    if (editing && collection != null) {
        CollectionSheet(
            initial = collection,
            onDismiss = { editing = false },
            onSave = { name, description ->
                viewModel.update(name, description)
                editing = false
            },
            onDelete = {
                editing = false
                viewModel.delete()
                onBack()
            },
        )
    }
}

private fun CopySort.label(): Int = when (this) {
    CopySort.VALUE -> R.string.collections_sort_value
    CopySort.NUMBER -> R.string.collections_sort_number
    CopySort.NEWEST -> R.string.collections_sort_newest
}
