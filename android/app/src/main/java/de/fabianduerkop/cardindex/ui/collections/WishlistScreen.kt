// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.collections.CollectionRepository
import de.fabianduerkop.cardindex.data.db.WantedWithCard
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.DetailScaffold
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The wishlist (#134); null while it loads. */
class WishlistViewModel(private val repository: CollectionRepository) : ViewModel() {
    val entries: StateFlow<List<WantedWithCard>?> = repository.observeWishlist()
        .map<List<WantedWithCard>, List<WantedWithCard>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun remove(entry: WantedWithCard) {
        viewModelScope.launch { repository.removeFromWishlist(entry.card.id, entry.card.language) }
    }
}

/**
 * The wishlist (#134): wanted prints, which are not copies, so they count nowhere else. Still wanted ones come first;
 * a print the user owns a copy of now says so and stays until removed. Tapping a row opens the card.
 */
@Composable
fun WishlistScreen(
    onBack: () -> Unit,
    onOpenCard: (cardId: String, language: String) -> Unit,
    viewModel: WishlistViewModel = viewModel(
        factory = containerViewModelFactory { container, _ -> WishlistViewModel(container.collections) },
    ),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    DetailScaffold(title = stringResource(R.string.wishlist_title), onBack = onBack) { padding ->
        val list = entries ?: return@DetailScaffold
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item { WishlistIntro(empty = list.isEmpty()) }
            items(list, key = { it.wanted.id }) { entry ->
                WantedRow(
                    entry,
                    onClick = { onOpenCard(entry.card.id, entry.card.language) },
                    onRemove = { viewModel.remove(entry) },
                )
            }
        }
    }
}

/** What wanted cards are (or how to add one, while there are none), above the wanted rows. */
@Composable
internal fun WishlistIntro(empty: Boolean) {
    Column(Modifier.padding(horizontal = ScreenPadding)) {
        PlaceholderText(
            stringResource(if (empty) R.string.wishlist_empty else R.string.wishlist_hint),
            Modifier.padding(vertical = Spacing.md),
        )
        if (!empty) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * One wanted print: the collector number, the name, set and language, and Remove. On a narrow row (#121) the number
 * goes above the name, as in [CopyRow].
 */
@Composable
internal fun WantedRow(entry: WantedWithCard, onClick: () -> Unit, onRemove: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .focusRing(MaterialTheme.shapes.small, gap = (-2).dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.collections_open_card), onClick = onClick),
    ) {
        val stacked = isNarrowRow(maxWidth, fontScale)
        Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!stacked) {
                    CollectorNumber(
                        localId = entry.card.localId,
                        printedTotal = entry.set.printedTotal,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = Spacing.md),
                    )
                }
                Column(Modifier.weight(1f)) {
                    if (stacked) {
                        CollectorNumber(
                            localId = entry.card.localId,
                            printedTotal = entry.set.printedTotal,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        entry.card.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOfNotNull(
                        entry.set.name,
                        languageName(entry.card.language),
                        if (entry.found) stringResource(R.string.wishlist_found) else null,
                    )
                    Text(
                        details.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextAction(
                    stringResource(R.string.wishlist_remove_short),
                    onClick = onRemove,
                    contentDescription = stringResource(R.string.wishlist_remove_description, entry.card.name),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
