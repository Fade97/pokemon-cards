// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CollectionSummary
import de.fabianduerkop.cardindex.data.prices.HistoryRange
import de.fabianduerkop.cardindex.debug.DebugTools
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.Region
import de.fabianduerkop.cardindex.ui.components.CardImage
import de.fabianduerkop.cardindex.ui.components.CompletionBar
import de.fabianduerkop.cardindex.ui.components.FoilCover
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.PrimaryButton
import de.fabianduerkop.cardindex.ui.components.ScanShortcut
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.SectionHeader
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.TopLevelScaffold
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Collections (Pearl, ADR 0014): the value summary, one featured set on the foil cover, compact rows for every other
 * set, then All cards and the user's own collections. The featured set is simply the first set in the existing order;
 * there is no pinning.
 */
@Composable
fun CollectionsScreen(
    onOpenSet: (String) -> Unit,
    onOpenCollection: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onOpenScan: () -> Unit = {},
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
        wordmark = true,
        actions = {
            ScanShortcut(onClick = onOpenScan)
            TextAction(
                stringResource(R.string.collections_new),
                onClick = { creating = true },
                contentDescription = stringResource(R.string.collections_new_description),
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            if (!state.loading && state.totalCopies == 0) {
                item { EmptyCollections(onOpenScan) }
                // Collections made before the first card stay reachable, so they can be renamed or deleted.
                if (state.collections.isNotEmpty()) {
                    item { YoursHeader(state.collections.size) }
                    items(state.collections, key = { "col-" + it.id }) { collection ->
                        CollectionRow(collection, onClick = { onOpenCollection(collection.id) })
                    }
                }
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
                    SectionHeader(
                        title = stringResource(R.string.collections_sets),
                        trailing = pluralStringResource(R.plurals.collections_sets_count, state.sets.size, state.sets.size),
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )
                }
                item {
                    LanguageChips(
                        languages = state.languages,
                        selected = state.selectedLanguages,
                        onToggle = viewModel::toggleLanguage,
                        modifier = Modifier.padding(bottom = Spacing.sm),
                    )
                }
                if (state.sets.isEmpty()) {
                    item {
                        PlaceholderText(
                            stringResource(R.string.collections_no_sets_in_language),
                            Modifier.padding(horizontal = ScreenPadding, vertical = Spacing.md),
                        )
                    }
                }
                state.sets.firstOrNull()?.let { featured ->
                    item(key = "featured-" + featured.completion.setId) {
                        FeaturedSet(
                            featured,
                            onClick = { onOpenSet(featured.completion.setId) },
                            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = Spacing.xs),
                        )
                    }
                }
                items(state.sets.drop(1), key = { "set-" + it.completion.setId }) { row ->
                    SetRow(row, onClick = { onOpenSet(row.completion.setId) })
                }
                item { YoursHeader(state.collections.size + 1) }
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
                item { Box(Modifier.height(Spacing.xl)) }
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
 * The value summary (ADR 0010, Pearl layout): every copy in the main market, labelled with its source, the newest
 * market date, and a link to All cards. With Pro the portfolio chart follows (#17); it also shows the change over the
 * chosen range. Without Pro the app has no price history, so no weekly change is shown rather than an invented one.
 */
@Composable
private fun ValueHeader(
    state: CollectionsUiState,
    portfolio: HistoryUiState?,
    onPortfolioRange: (HistoryRange) -> Unit,
    onOpenAll: () -> Unit,
) {
    Column(Modifier.padding(horizontal = ScreenPadding)) {
        ValueSummary(
            value = state.value,
            caption = pluralStringResource(R.plurals.collections_summary, state.totalCopies, state.totalCopies),
            label = stringResource(
                if (state.source == PriceSource.CARDMARKET) {
                    R.string.collections_value_label_cardmarket
                } else {
                    R.string.collections_value_label_tcgplayer
                },
            ),
            priceDate = state.priceDate,
            onCaptionClick = onOpenAll,
        )
        if (portfolio != null) {
            PriceHistoryBlock(portfolio, onRange = onPortfolioRange, modifier = Modifier.padding(top = Spacing.md))
            Text(
                stringResource(R.string.history_portfolio_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        HorizontalDivider(Modifier.padding(top = Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Spoken summary of a set: "151, 87 of 165 cards, 684,20 €". */
@Composable
private fun setDescription(row: SetRowUi): String {
    val set = row.completion
    val parts = listOfNotNull(
        pluralStringResource(R.plurals.collections_set_row, set.printedTotal, set.name, row.ownedNumbers, set.printedTotal),
        if (set.region == Region.JP) stringResource(R.string.collections_region_jp_description) else null,
        row.value?.takeIf { it.amountMinor > 0 }?.let { Money.format(it.amountMinor, it.currency) },
    )
    return parts.joinToString(", ")
}

/**
 * The featured set on the foil cover: its series and name, up to three owned cards fanned (CDN art, cached by Coil,
 * or the numbered placeholder when there is none or the phone is offline), the count, value and completion bar.
 */
@Composable
private fun FeaturedSet(row: SetRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val set = row.completion
    val description = setDescription(row)
    FoilCover(onClick = onClick, modifier = modifier, onClickLabel = stringResource(R.string.collections_open_set)) {
        Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val overline = listOfNotNull(
                        row.series,
                        if (set.region == Region.JP) stringResource(R.string.collections_region_jp) else null,
                    ).joinToString(" · ")
                    if (overline.isNotEmpty()) {
                        Text(
                            overline,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        set.name,
                        style = MaterialTheme.typography.headlineLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.sm).size(22.dp),
                )
            }
            CardFan(row.art, set.printedTotal, Modifier.padding(vertical = Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    progressText(
                        row.ownedNumbers,
                        set.printedTotal,
                        countColor = MaterialTheme.colorScheme.onSurface,
                        suffix = stringResource(R.string.collections_collected),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                row.value?.takeIf { it.amountMinor > 0 }?.let { value ->
                    Text(
                        Money.format(value.amountMinor, value.currency),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
            CompletionBar(row.ownedNumbers, set.printedTotal, Modifier.padding(top = Spacing.md))
        }
    }
}

/** Up to three cards, the newest in front, the others tilted behind it as in the Pearl study. */
@Composable
private fun CardFan(cards: List<CardEntity>, printedTotal: Int, modifier: Modifier = Modifier) {
    if (cards.isEmpty()) return
    // (index into cards, x offset, rotation), back to front.
    val layout = when (cards.size) {
        1 -> listOf(Triple(0, 0, 0f))
        2 -> listOf(Triple(1, 34, 8f), Triple(0, -34, -4f))
        else -> listOf(Triple(1, -62, -11f), Triple(2, 62, 11f), Triple(0, 0, 0f))
    }
    Box(modifier.fillMaxWidth().height(FanHeight), contentAlignment = Alignment.Center) {
        layout.forEachIndexed { z, (index, x, angle) ->
            val card = cards[index]
            CardImage(
                card = card,
                localId = card.localId,
                printedTotal = printedTotal,
                modifier = Modifier
                    .zIndex(z.toFloat())
                    .offset(x = x.dp, y = if (angle == 0f) 0.dp else Spacing.xs)
                    .rotate(angle)
                    .width(FanCardWidth)
                    .shadow(Spacing.xs, MaterialTheme.shapes.small),
            )
        }
    }
}

private val FanCardWidth = 84.dp
private val FanHeight = 132.dp

/** A compact set row: name, cards and value, and the count with a thin completion bar under it. */
@Composable
private fun SetRow(row: SetRowUi, onClick: () -> Unit) {
    val set = row.completion
    val description = setDescription(row)
    val subtitle = listOfNotNull(
        pluralStringResource(R.plurals.collections_cards_count, set.copies, set.copies),
        row.value?.takeIf { it.amountMinor > 0 }?.let { Money.format(it.amountMinor, it.currency) },
        if (set.region == Region.JP) stringResource(R.string.collections_region_jp) else null,
    ).joinToString(" · ")
    ListRow(
        title = set.name,
        subtitle = subtitle,
        onClick = onClick,
        onClickLabel = stringResource(R.string.collections_open_set),
        contentDescription = description,
        trailing = {
            Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.End) {
                Text(
                    progressText(row.ownedNumbers, set.printedTotal, countColor = MaterialTheme.colorScheme.onSurface),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                CompletionBar(row.ownedNumbers, set.printedTotal, Modifier.padding(top = Spacing.xs))
            }
        },
    )
}

@Composable
private fun YoursHeader(count: Int) {
    SectionHeader(
        title = stringResource(R.string.collections_yours),
        trailing = pluralStringResource(R.plurals.collections_count, count, count),
        modifier = Modifier.padding(horizontal = ScreenPadding),
    )
}

@Composable
private fun CollectionRow(collection: CollectionSummary, onClick: () -> Unit) =
    CollectionRow(name = collection.name, cards = collection.cards, onClick = onClick)

@Composable
private fun CollectionRow(name: String, cards: Int, onClick: () -> Unit) {
    ListRow(
        title = name,
        subtitle = pluralStringResource(R.plurals.collections_cards_count, cards, cards),
        onClick = onClick,
        icon = R.drawable.ic_nav_collections,
        onClickLabel = stringResource(R.string.collections_open_collection),
    )
}

@Composable
private fun EmptyCollections(onOpenScan: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = ScreenPadding, vertical = Spacing.xl)) {
        Text(stringResource(R.string.collections_empty_title), style = MaterialTheme.typography.headlineSmall)
        PlaceholderText(stringResource(R.string.collections_empty_body), Modifier.padding(top = Spacing.sm))
        PrimaryButton(
            stringResource(R.string.collections_empty_scan),
            onClick = onOpenScan,
            icon = R.drawable.ic_nav_scan,
            modifier = Modifier.padding(top = Spacing.lg),
        )
        if (DebugTools.AVAILABLE) {
            SecondaryButton(
                stringResource(R.string.collections_debug_sample),
                onClick = { scope.launch { DebugTools.addSampleData(context.appContainer) } },
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
    }
}
