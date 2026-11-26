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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
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
import de.fabianduerkop.cardindex.data.images.OwnPhotos
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
import java.time.ZoneId
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onOpenSet: (setId: String, languages: Set<String>) -> Unit,
    onOpenCollection: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onOpenScan: () -> Unit = {},
    onOpenWishlist: () -> Unit = {},
    onOpenCard: (cardId: String, language: String) -> Unit = { _, _ -> },
    onOpenSearch: () -> Unit = {},
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
                openedRecaps = settings.openedRecaps,
                markRecapOpened = { settings.openedRecaps = settings.openedRecaps + it },
                photos = container.ownPhotos,
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
    val recaps by viewModel.recaps.collectAsStateWithLifecycle()
    val openRecap by viewModel.recap.collectAsStateWithLifecycle()
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

    // While the view switch is pinned under the bar, the two read as one raised block; the switch draws the edge.
    val switchPinned by remember { derivedStateOf { listState.firstVisibleItemIndex >= SWITCH_INDEX } }
    TopLevelScaffold(
        title = stringResource(R.string.nav_collections),
        modifier = modifier,
        wordmark = true,
        pinnedBelow = switchPinned,
        actions = {
            ScanShortcut(onClick = onOpenScan)
            TextAction(
                stringResource(R.string.collections_new),
                onClick = { creating = true },
                // The bar pads its actions by 4 dp; this puts the word's end on the 20 dp gutter (#218).
                modifier = Modifier.padding(end = Spacing.xs),
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
                        CollectionRow(collection, cover = null, photos = state.photos, onClick = { onOpenCollection(collection.id) })
                    }
                }
            } else if (!state.loading) {
                item {
                    Column {
                        ValueHeader(
                            state = state,
                            portfolio = portfolio,
                            onPortfolioRange = viewModel::setPortfolioRange,
                            onOpenAll = { onOpenCollection(CollectionsRoutes.ALL_CARDS) },
                        )
                        // Ready recaps (#175) wait here, in the same item, so the switch keeps its index.
                        recaps.forEach { entry -> RecapRow(entry, onClick = { viewModel.showRecap(entry.period) }) }
                    }
                }
                stickyHeader(key = "view-switch") {
                    CollectionsSwitch(view, onSelect = show, pinned = switchPinned)
                }
                when (view) {
                    CollectionsView.SETS -> setItems(
                        state,
                        weekly = weekly,
                        onToggleLanguage = viewModel::toggleLanguage,
                        // The set opens in the languages chosen here, so its count and value match the row (#218).
                        onOpenSet = { onOpenSet(it, state.selectedLanguages) },
                    )
                    CollectionsView.BINDERS -> binderItems(state, onOpenCollection = onOpenCollection)
                    CollectionsView.WISHLIST -> wishlistItems(
                        wantedEntries,
                        onOpenCard = onOpenCard,
                        onRemove = wishlist::remove,
                        onFindCard = onOpenSearch,
                    )
                }
                item { Box(Modifier.height(Spacing.xl)) }
            }
        }
    }

    openRecap?.let { recap ->
        ModalBottomSheet(
            onDismissRequest = viewModel::closeRecap,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            RecapSheet(recap, onDone = viewModel::closeRecap)
        }
    }

    if (creating) {
        CollectionSheet(
            initial = null,
            takenNames = state.collections.mapTo(HashSet()) { binderNameKey(it.name) },
            onDismiss = { creating = false },
            onSave = { name, description, _ ->
                viewModel.createCollection(name, description)
                creating = false
                // A new collection lives under Binders: show it there, not behind another view (#164).
                show(CollectionsView.BINDERS)
            },
        )
    }
}

/** Width of a set row's completion track, the widest common count ("100/165") at the default font. */
private val SetRowBarMin = 72.dp

/** List index of the switch: right after the value header. */
private const val SWITCH_INDEX = 1

/**
 * The Sets / Binders / Wishlist switch (#164) on the page colour, so rows scrolling under it stay hidden, with the
 * divider that closes the value header below it.
 */
@Composable
private fun CollectionsSwitch(view: CollectionsView, onSelect: (CollectionsView) -> Unit, pinned: Boolean) {
    val labels = CollectionsView.entries.map { entry ->
        stringResource(
            when (entry) {
                CollectionsView.SETS -> R.string.collections_view_sets
                CollectionsView.BINDERS -> R.string.collections_view_binders
                CollectionsView.WISHLIST -> R.string.collections_view_wishlist
            },
        )
    }
    // Pinned under the app bar, it takes the bar's scrolled surface and a full-width hairline, so the two read as one
    // raised block over the list instead of a strip floating on it (#221).
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(if (pinned) colors.surfaceContainer else colors.background)) {
        ViewSwitch(
            labels = labels,
            selectedIndex = view.ordinal,
            onSelect = { onSelect(CollectionsView.entries[it]) },
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = Spacing.sm),
        )
        HorizontalDivider(
            Modifier.padding(horizontal = if (pinned) 0.dp else ScreenPadding),
            color = colors.outlineVariant,
        )
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

/**
 * Binders view: All cards, then the user's own collections, or a hint to start one with New. Each row shows its
 * cover (#172): the card the user picked in the edit sheet, else the most valuable one; All cards shows the most
 * valuable card overall.
 */
private fun LazyListScope.binderItems(state: CollectionsUiState, onOpenCollection: (Long) -> Unit) {
    item {
        CollectionRow(
            name = stringResource(R.string.collections_all_cards),
            description = null,
            cards = state.totalCopies,
            cover = state.allCardsCover,
            photos = state.photos,
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
        CollectionRow(collection, state.covers[collection.id], state.photos, onClick = { onOpenCollection(collection.id) })
    }
}

/** Wishlist view: the wanted cards with Remove, as on the wishlist screen; nothing while they load. */
private fun LazyListScope.wishlistItems(
    entries: List<WantedWithCard>?,
    onOpenCard: (cardId: String, language: String) -> Unit,
    onRemove: (WantedWithCard) -> Unit,
    onFindCard: () -> Unit,
) {
    if (entries == null) return
    item { WishlistIntro(empty = entries.isEmpty(), onFindCard = onFindCard) }
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
    // The same 8 dp under the bar as the set screen and Search (#221).
    Column(Modifier.padding(horizontal = ScreenPadding).padding(top = Spacing.sm)) {
        // "Value of priced copies" while some copies have no price, so the big number never reads as the whole
        // collection's worth before the coverage line under it is read (#221).
        val partial = (state.value?.unpricedCopies ?: 0) > 0
        ValueSummary(
            value = state.value,
            caption = pluralStringResource(R.plurals.collections_summary, state.totalCopies, state.totalCopies),
            label = stringResource(
                when {
                    state.source == PriceSource.CARDMARKET && partial -> R.string.collections_value_label_cardmarket_partial
                    state.source == PriceSource.CARDMARKET -> R.string.collections_value_label_cardmarket
                    partial -> R.string.collections_value_label_tcgplayer_partial
                    else -> R.string.collections_value_label_tcgplayer
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

/**
 * How much of a set's value is known, when not all of it: "5 of 10 copies priced", or "No price" when none of its
 * copies has one. Null when every copy is priced. Shown and spoken next to the value, so a partial sum never passes
 * for the whole set (#218).
 */
@Composable
private fun valueCoverage(row: SetRowUi): String? {
    val value = row.value
    return when {
        row.completion.copies > 0 && (value == null || value.amountMinor <= 0) ->
            stringResource(R.string.collections_not_priced, stringResource(row.source.marketName()))
        value != null && value.unpricedCopies > 0 ->
            pluralStringResource(R.plurals.collections_set_value_coverage, value.totalCopies, value.pricedCopies, value.totalCopies)
        else -> null
    }
}

/**
 * Where a set's value comes from: "TCGplayer, Nov 20, 2026". Next to the value on the cover and in each row, so the
 * basis stays in view once the collection header has scrolled away (#218). Null without a price.
 */
@Composable
private fun valueSource(row: SetRowUi, keepTogether: Boolean = true): String? {
    val value = row.value?.takeIf { it.amountMinor > 0 } ?: return null
    // The set's oldest price date (Prices.totalDate), said so when it sums more than one copy (#250).
    val date = row.priceDate?.let {
        val day = formatDate(it.atZone(ZoneId.systemDefault()).toLocalDate())
        if (value.pricedCopies > 1) stringResource(R.string.price_oldest_short, day) else day
    }?.replace(' ', '\u00A0')
    // In a row's subtitle one unbreakable piece, so source and date move to the next line together (#218); in the
    // cover's narrow value column it may break after the comma, or the year would split.
    return listOfNotNull(stringResource(value.source.marketName()), date).joinToString(if (keepTogether) ",\u00A0" else ", ")
}

/** Spoken summary of a set: "151, 87 of 165 cards, plus 2 secret cards, 684,20 €". */
@Composable
private fun setDescription(row: SetRowUi, pinned: Boolean = false, change: WeeklyTrend? = null): String {
    val set = row.completion
    val progress = row.progress
    val parts = listOfNotNull(
        pluralStringResource(R.plurals.collections_set_row, set.printedTotal, set.name, progress.base, set.printedTotal),
        // The copies too, as the row shows them ("30 copies"): the slot count alone loses them (#250).
        set.copies.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.collections_cards_count, it, it) },
        secretDescription(progress),
        if (set.region == Region.JP) stringResource(R.string.collections_region_jp_description) else null,
        row.value?.takeIf { it.amountMinor > 0 }?.let { Money.format(it.amountMinor, it.currency) },
        valueSource(row),
        valueCoverage(row),
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
            // Top-aligned: the count lines up with the value, not between it and its coverage line (#218).
            Row(verticalAlignment = Alignment.Top) {
                // "collected" and "+N secret" under the count, not after it: the value column keeps the width to show
                // its source on one line at large font (#221). Natural width (unweighted): the count never breaks.
                Column {
                    Text(
                        progressText(row.progress.base, set.printedTotal, countColor = MaterialTheme.colorScheme.onSurface),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    // The copies count under it, so "10 of 20 copies priced" on the right refers to something (#250).
                    Text(
                        listOfNotNull(
                            stringResource(R.string.collections_collected),
                            pluralStringResource(R.plurals.collections_cards_count, set.copies, set.copies).takeIf { set.copies > 0 },
                            secretText(row.progress),
                        ).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val priced = row.value?.takeIf { it.amountMinor > 0 }
                if (priced != null) {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f).padding(start = Spacing.sm)) {
                        Text(Money.format(priced.amountMinor, priced.currency), style = MaterialTheme.typography.labelLarge)
                        // Its source and date, and how much it covers when not all: alone, $18.33 read as all copies'
                        // worth, from no market in particular (#218).
                        Text(
                            listOfNotNull(valueSource(row, keepTogether = false), valueCoverage(row)).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                        // With the value it describes, right-aligned, not over the completion bar (#250).
                        change?.let { WeeklyChangeLine(it, Modifier.padding(top = Spacing.xs), textAlign = TextAlign.End) }
                    }
                } else if (set.copies > 0) {
                    // Owned but unpriced: said here too, as in the rows and on the set screen, where a price would be.
                    Text(
                        stringResource(R.string.collections_not_priced, stringResource(row.source.marketName())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f).padding(start = Spacing.sm),
                    )
                }
            }
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
    val facts = listOfNotNull(
        pluralStringResource(R.plurals.collections_cards_count, set.copies, set.copies),
        row.value?.takeIf { it.amountMinor > 0 }?.let { Money.format(it.amountMinor, it.currency) },
        secretText(row.progress),
        if (set.region == Region.JP) stringResource(R.string.collections_region_jp) else null,
    )
    // Where the value comes from and how much it covers on a line of its own: run on after "·", it broke mid-way at
    // large font and left a "·" dangling at a line end (#221). Each line breaks only between its facts (#250).
    val basis = listOfNotNull(valueSource(row), valueCoverage(row))
    ListRow(
        title = set.name,
        subtitle = null,
        subtitleLines = listOf(facts, basis),
        onClick = onClick,
        onClickLabel = stringResource(R.string.collections_open_set),
        contentDescription = description,
        below = change?.let { { WeeklyChangeLine(it, Modifier.padding(top = Spacing.xxs)) } },
        trailing = {
            // One track width down the list, so 1/16 and 10/103 compare by their bars, not by their digits (#221);
            // a wider count at large font still widens it.
            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = SetRowBarMin), horizontalAlignment = Alignment.End) {
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
private fun CollectionRow(collection: CollectionSummary, cover: BinderCover?, photos: OwnPhotos, onClick: () -> Unit) =
    CollectionRow(collection.name, collection.description, collection.cards, cover, photos, onClick)

/**
 * A binder row (#172): its cover, the name, the description as an optional one-line subtitle, and the card count.
 * The cover is decorative; TalkBack reads the texts.
 */
@Composable
private fun CollectionRow(
    name: String,
    description: String?,
    cards: Int,
    cover: BinderCover?,
    photos: OwnPhotos,
    onClick: () -> Unit,
) {
    val count = pluralStringResource(R.plurals.collections_cards_count, cards, cards)
    val subtitle = description?.trim()?.takeIf { it.isNotEmpty() }
    ListRow(
        title = name,
        subtitle = subtitle ?: count,
        subtitleMaxLines = 1,
        onClick = onClick,
        leading = { CoverThumb(cover?.copy, photos) },
        onClickLabel = stringResource(R.string.collections_open_collection),
        below = subtitle?.let {
            {
                Text(
                    count,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
        },
    )
}

/** The wishlist (#134): its count is the cards still wanted, which are not part of any card count above. */
@Composable
private fun WishlistRow(wanted: Int, onClick: () -> Unit) {
    ListRow(
        title = stringResource(R.string.wishlist_title),
        subtitle = pluralStringResource(R.plurals.wishlist_wanted_count, wanted, wanted),
        onClick = onClick,
        // Hollow like a missing slot (wanted cards are not owned yet), and in line with the binder covers below.
        leading = { CoverThumb(null, OwnPhotos()) },
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
