// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalLayoutDirection
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
import de.fabianduerkop.cardindex.data.db.WantedWithCard
import de.fabianduerkop.cardindex.data.prices.HistoryRange
import de.fabianduerkop.cardindex.data.prices.WeeklyTrend
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
 * Collections (Pearl, ADR 0014): the value summary, then a switch between three views (#164) that stays at the top
 * while the list scrolls, so binders and the wishlist never sink below a long list of sets:
 * - Sets: one featured set on the foil cover and compact rows for every other set. The featured set is the one the
 *   user pinned in the set detail, else the most valuable set (#156); see [featuredSet].
 * - Binders: All cards and the user's own collections.
 * - Wishlist: the wanted cards (#134), as on the wishlist screen.
 * The last view is remembered across starts (AppSettings.collectionsView). With no cards yet the screen shows the
 * empty state instead, with any collections and the wishlist below it.
 */
@Composable
fun CollectionsScreen(
    onOpenSet: (String) -> Unit,
    onOpenCollection: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onOpenScan: () -> Unit = {},
    onOpenWishlist: () -> Unit = {},
    onOpenCard: (cardId: String, language: String) -> Unit = { _, _ -> },
    viewModel: CollectionsViewModel = viewModel(
        factory = containerViewModelFactory { container, _ ->
            val settings = container.settings
            CollectionsViewModel(
                container.collections,
                container.prices,
                container.primaryPriceSource,
                container.priceHistory,
                container.entitlements.isPro,
                settings.featuredSetFlow,
                storedView = settings.collectionsView,
                saveView = { settings.collectionsView = it },
            )
        },
    ),
    wishlist: WishlistViewModel = viewModel(
        factory = containerViewModelFactory { container, _ -> WishlistViewModel(container.collections) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val portfolio by viewModel.portfolio.collectAsStateWithLifecycle()
    val weekly by viewModel.weeklyChanges.collectAsStateWithLifecycle()
    val wanted by viewModel.wishlistCount.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val wantedEntries by wishlist.entries.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Scrolled into a view (or partly past the switch), the new view starts right under the switch, without animation.
    val show: (CollectionsView) -> Unit = { chosen ->
        viewModel.showView(chosen)
        val pastSwitch = listState.firstVisibleItemIndex > SWITCH_INDEX ||
            (listState.firstVisibleItemIndex == SWITCH_INDEX && listState.firstVisibleItemScrollOffset > 0)
        if (pastSwitch) scope.launch { listState.scrollToItem(SWITCH_INDEX) }
    }

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
        // The top padding sits outside the list, so the switch sticks below the app bar rather than behind it.
        val direction = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            state = listState,
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction),
                end = padding.calculateEndPadding(direction),
                bottom = padding.calculateBottomPadding(),
            ),
        ) {
            if (!state.loading && state.totalCopies == 0) {
                item { EmptyCollections(onOpenScan) }
                // Collections made before the first card stay reachable, so they can be renamed or deleted; so does
                // a wishlist (#134), which needs no owned card.
                if (state.collections.isNotEmpty() || wanted > 0) {
                    item { YoursHeader(state.collections.size + if (wanted > 0) 1 else 0) }
                    if (wanted > 0) item { WishlistRow(wanted, onOpenWishlist) }
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
                stickyHeader(key = "view-switch") {
                    CollectionsSwitch(view, onSelect = show)
                }
                when (view) {
                    CollectionsView.SETS -> setItems(
                        state,
                        weekly = weekly,
                        onToggleLanguage = viewModel::toggleLanguage,
                        onOpenSet = onOpenSet,
                    )
                    CollectionsView.BINDERS -> binderItems(state, onOpenCollection = onOpenCollection)
                    CollectionsView.WISHLIST -> wishlistItems(
                        wantedEntries,
                        onOpenCard = onOpenCard,
                        onRemove = wishlist::remove,
                    )
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
                // A new collection lives under Binders: show it there, not behind another view (#164).
                show(CollectionsView.BINDERS)
            },
        )
    }
}

/** List index of the switch: right after the value header. */
private const val SWITCH_INDEX = 1

/**
 * The Sets / Binders / Wishlist switch (#164) on the page colour, so rows scrolling under it stay hidden, with the
 * divider that closes the value header below it.
 */
@Composable
private fun CollectionsSwitch(view: CollectionsView, onSelect: (CollectionsView) -> Unit) {
    val labels = CollectionsView.entries.map { entry ->
        stringResource(
            when (entry) {
                CollectionsView.SETS -> R.string.collections_view_sets
                CollectionsView.BINDERS -> R.string.collections_view_binders
                CollectionsView.WISHLIST -> R.string.collections_view_wishlist
            },
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = ScreenPadding),
    ) {
        ViewSwitch(
            labels = labels,
            selectedIndex = view.ordinal,
            onSelect = { onSelect(CollectionsView.entries[it]) },
            modifier = Modifier.padding(vertical = Spacing.sm),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Sets view: the count, language chips, the featured set on the foil cover and a row for every other set. */
private fun LazyListScope.setItems(
    state: CollectionsUiState,
    weekly: Map<String, WeeklyTrend>,
    onToggleLanguage: (String?) -> Unit,
    onOpenSet: (String) -> Unit,
) {
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
            onToggle = onToggleLanguage,
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
    state.featured?.let { featured ->
        item(key = "featured-" + featured.completion.setId) {
            FeaturedSet(
                featured,
                pinned = state.featuredPinned,
                change = weekly[featured.completion.setId],
                onClick = { onOpenSet(featured.completion.setId) },
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = Spacing.xs),
            )
        }
    }
    items(state.otherSets, key = { "set-" + it.completion.setId }) { row ->
        SetRow(row, weekly[row.completion.setId], onClick = { onOpenSet(row.completion.setId) })
    }
}

/** Binders view: All cards, then the user's own collections, or a hint to start one with New. */
private fun LazyListScope.binderItems(state: CollectionsUiState, onOpenCollection: (Long) -> Unit) {
    item {
        CollectionRow(
            name = stringResource(R.string.collections_all_cards),
            cards = state.totalCopies,
            onClick = { onOpenCollection(CollectionsRoutes.ALL_CARDS) },
        )
    }
    if (state.collections.isEmpty()) {
        item {
            PlaceholderText(
                stringResource(R.string.collections_binders_empty),
                Modifier.padding(horizontal = ScreenPadding, vertical = Spacing.md),
            )
        }
    }
    items(state.collections, key = { "col-" + it.id }) { collection ->
        CollectionRow(collection, onClick = { onOpenCollection(collection.id) })
    }
}

/** Wishlist view: the wanted cards with Remove, as on the wishlist screen; nothing while they load. */
private fun LazyListScope.wishlistItems(
    entries: List<WantedWithCard>?,
    onOpenCard: (cardId: String, language: String) -> Unit,
    onRemove: (WantedWithCard) -> Unit,
) {
    if (entries == null) return
    item { WishlistIntro(empty = entries.isEmpty()) }
    items(entries, key = { "wanted-" + it.wanted.id }) { entry ->
        WantedRow(
            entry,
            onClick = { onOpenCard(entry.card.id, entry.card.language) },
            onRemove = { onRemove(entry) },
        )
    }
}

/**
 * The value summary (ADR 0010, Pearl layout): every copy in the main market, labelled with its source, the newest
 * market date, and a link to All cards. With Pro the portfolio chart follows (#17); it also shows the change over the
 * chosen range. Without Pro the header shows no change: the free weekly direction (#186) is on the set rows.
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
    }
}

/** Spoken summary of a set: "151, 87 of 165 cards, plus 2 secret cards, 684,20 €". */
@Composable
private fun setDescription(row: SetRowUi, pinned: Boolean = false, change: WeeklyTrend? = null): String {
    val set = row.completion
    val progress = row.progress
    val parts = listOfNotNull(
        pluralStringResource(R.plurals.collections_set_row, set.printedTotal, set.name, progress.base, set.printedTotal),
        secretDescription(progress),
        if (set.region == Region.JP) stringResource(R.string.collections_region_jp_description) else null,
        row.value?.takeIf { it.amountMinor > 0 }?.let { Money.format(it.amountMinor, it.currency) },
        change?.let { weeklyChangeDescription(it) },
        if (pinned) stringResource(R.string.collections_featured_pinned_description) else null,
    )
    return parts.joinToString(", ")
}

/**
 * The featured set on the foil cover: its series and name, up to three owned cards fanned (CDN art, cached by Coil,
 * or the numbered placeholder when there is none or the phone is offline), the count, value and completion bar.
 */
@Composable
private fun FeaturedSet(
    row: SetRowUi,
    pinned: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    change: WeeklyTrend? = null,
) {
    val set = row.completion
    val description = setDescription(row, pinned, change)
    FoilCover(onClick = onClick, modifier = modifier, onClickLabel = stringResource(R.string.collections_open_set)) {
        Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val overline = listOfNotNull(
                        row.series,
                        if (set.region == Region.JP) stringResource(R.string.collections_region_jp) else null,
                        if (pinned) stringResource(R.string.collections_featured_pinned) else null,
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
                        row.progress.base,
                        set.printedTotal,
                        countColor = MaterialTheme.colorScheme.onSurface,
                        suffix = listOfNotNull(stringResource(R.string.collections_collected), secretText(row.progress))
                            .joinToString(" · "),
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
            change?.let { WeeklyChangeLine(it, Modifier.padding(top = Spacing.xs)) }
            CompletionBar(row.progress.base, set.printedTotal, Modifier.padding(top = Spacing.md))
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

/**
 * A compact set row: name, cards and value, the weekly price change (#173; direction only without Pro, #186), and the
 * count with a thin completion bar under it.
 */
@Composable
private fun SetRow(row: SetRowUi, change: WeeklyTrend?, onClick: () -> Unit) {
    val set = row.completion
    val description = setDescription(row, change = change)
    val subtitle = listOfNotNull(
        pluralStringResource(R.plurals.collections_cards_count, set.copies, set.copies),
        row.value?.takeIf { it.amountMinor > 0 }?.let { Money.format(it.amountMinor, it.currency) },
        secretText(row.progress),
        if (set.region == Region.JP) stringResource(R.string.collections_region_jp) else null,
    ).joinToString(" · ")
    ListRow(
        title = set.name,
        subtitle = subtitle,
        onClick = onClick,
        onClickLabel = stringResource(R.string.collections_open_set),
        contentDescription = description,
        below = change?.let { { WeeklyChangeLine(it, Modifier.padding(top = Spacing.xxs)) } },
        trailing = {
            Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.End) {
                Text(
                    progressText(row.progress.base, set.printedTotal, countColor = MaterialTheme.colorScheme.onSurface),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                CompletionBar(row.progress.base, set.printedTotal, Modifier.padding(top = Spacing.xs))
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

/** The wishlist (#134): its count is the cards still wanted, which are not part of any card count above. */
@Composable
private fun WishlistRow(wanted: Int, onClick: () -> Unit) {
    ListRow(
        title = stringResource(R.string.wishlist_title),
        subtitle = pluralStringResource(R.plurals.wishlist_wanted_count, wanted, wanted),
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
