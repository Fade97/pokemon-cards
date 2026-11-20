// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.collections.SetDetail
import de.fabianduerkop.cardindex.data.collections.SetSlot
import de.fabianduerkop.cardindex.data.images.OwnPhotos
import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.ui.components.CardImage
import de.fabianduerkop.cardindex.ui.components.ChoiceChip
import de.fabianduerkop.cardindex.ui.components.MenuChip
import de.fabianduerkop.cardindex.ui.components.CompletionBar
import de.fabianduerkop.cardindex.ui.components.DetailScaffold
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.components.numberOverTotal
import de.fabianduerkop.cardindex.ui.components.padCollectorNumber
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import de.fabianduerkop.cardindex.ui.theme.Spacing

/**
 * Set detail (ADR 0010, Pearl in ADR 0014): the set as a grid with one slot per number. Compact shows numbered
 * slots, owned solid and missing hollow; Cards shows small card images, missing ones grey and outlined in place.
 * A missing slot opens a bar at the bottom instead of a new screen, so the user can scan the grid quickly.
 */
@Composable
fun SetDetailScreen(
    onBack: () -> Unit,
    onOpenCard: (cardId: String, language: String) -> Unit,
    viewModel: SetDetailViewModel = viewModel(
        factory = containerViewModelFactory { container, saved ->
            SetDetailViewModel(
                container.collections,
                container.ownPhotos,
                container.prices,
                container.primaryPriceSource,
                saved,
                pinnedSet = container.settings.featuredSetFlow,
                pinSet = { container.settings.featuredSet = it },
                pastLegendVisits = container.settings.setLegendVisits,
                recordLegendVisit = { container.settings.setLegendVisits += 1 },
            )
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = detail?.slots?.firstOrNull { it.localId == selectedKey }
    // Back closes the missing-card bar first, then leaves the set (#101).
    BackHandler(enabled = selected != null) { selectedKey = null }

    DetailScaffold(
        title = detail?.set?.name.orEmpty(),
        onBack = onBack,
        actions = {
            if (detail != null) {
                TextAction(
                    text = stringResource(if (state.pinned) R.string.collections_unpin_set else R.string.collections_pin_set),
                    onClick = viewModel::togglePinned,
                    contentDescription = stringResource(
                        if (state.pinned) R.string.collections_unpin_set_description else R.string.collections_pin_set_description,
                    ),
                )
            }
        },
        bottomBar = {
            if (selected != null) {
                MissingSlotBar(
                    slot = selected,
                    printedTotal = detail.set.printedTotal,
                    onOpen = selected.card?.let { card -> { onOpenCard(card.id, card.language) } },
                    onClose = { selectedKey = null },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> Unit
            detail == null -> PlaceholderText(
                stringResource(R.string.collections_set_unknown),
                Modifier.padding(padding).padding(ScreenPadding),
            )
            else -> SetGrid(
                state = state,
                detail = detail,
                padding = padding,
                selectedKey = selectedKey,
                onToggleLanguage = viewModel::toggleLanguage,
                onMode = viewModel::setMode,
                onFilter = viewModel::setFilter,
                onToggleLegend = viewModel::toggleLegend,
                onSlot = { slot ->
                    val card = slot.card
                    if (slot.owned && card != null) {
                        selectedKey = null
                        onOpenCard(card.id, card.language)
                    } else {
                        selectedKey = slot.localId
                    }
                },
            )
        }
    }
}

@Composable
private fun SetGrid(
    state: SetDetailUiState,
    detail: SetDetail,
    padding: PaddingValues,
    selectedKey: String?,
    onToggleLanguage: (String?) -> Unit,
    onMode: (SetViewMode) -> Unit,
    onFilter: (SlotFilter) -> Unit,
    onToggleLegend: () -> Unit,
    onSlot: (SetSlot) -> Unit,
) {
    // Filters and grid list every slot (buildSetSlots), secret rares included; the header counts the base set (#132).
    val shown = detail.slots.filteredBy(state.filter)
    val compact = state.mode == SetViewMode.COMPACT
    var languageSheet by rememberSaveable { mutableStateOf(false) }
    if (languageSheet) {
        LanguageSheet(detail.languages, state.selectedLanguages, onToggleLanguage, onDismiss = { languageSheet = false })
    }
    LazyVerticalGrid(
        columns = if (compact) GridCells.Adaptive(CompactSlotMin) else GridCells.Adaptive(CardSlotMin),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + Spacing.lg,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) Spacing.xs else Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(if (compact) Spacing.xs else Spacing.md),
    ) {
        header {
            SetHeader(detail, state.selectedLanguages, state.value)
        }
        header {
            FilterRow(
                state = state,
                detail = detail,
                onFilter = onFilter,
                onLanguages = { languageSheet = true },
                onMode = onMode,
                onToggleLegend = onToggleLegend,
            )
        }
        if (state.legendShown) {
            header {
                ChecklistLegend(
                    stringResource(if (compact) R.string.collections_legend_compact else R.string.collections_legend_cards),
                    Modifier.padding(bottom = Spacing.sm),
                )
            }
        }
        items(shown, key = { it.localId }) { slot ->
            if (compact) {
                CompactSlot(slot, detail.set.printedTotal, selected = slot.localId == selectedKey, onClick = { onSlot(slot) })
            } else {
                CardSlot(slot, detail.set.printedTotal, state.photos, selected = slot.localId == selectedKey, onClick = { onSlot(slot) })
            }
        }
    }
}

/** Smallest compact slot: five across on a 360 dp phone, and never under the 48 dp touch height. */
private val CompactSlotMin = 56.dp
private val CardSlotMin = 76.dp

private fun LazyGridScope.header(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") { content() }
}

/**
 * The set's count as the strongest type (ADR 0010), its series and languages above, the value with its source at the
 * end, and the completion bar. All from the same slots as the checklist: the count and bar cover the printed total,
 * owned secret rares beyond it show apart as "+N secret" (#132).
 */
@Composable
private fun SetHeader(detail: SetDetail, selectedLanguages: Set<String>, value: CollectionValue?) {
    val languages = selectedLanguages.ifEmpty { detail.languages.toSet() }.sorted()
    val progress = detail.progress
    val languageLabel = if (selectedLanguages.isEmpty() && languages.size != 1) {
        stringResource(R.string.collections_all_languages)
    } else {
        stringResource(R.string.collections_languages_label, languageList(languages, stringResource(R.string.collections_and)))
    }
    Column(Modifier.padding(top = Spacing.sm, bottom = Spacing.md)) {
        Text(
            text = listOfNotNull(detail.set.series.takeIf { it.isNotBlank() }, languageLabel).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val description = progressDescription(progress)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = Spacing.xs)) {
            Text(
                text = progressText(progress.base, progress.printedTotal),
                style = MaterialTheme.typography.displayMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f).semantics { contentDescription = description },
            )
            value?.takeIf { it.amountMinor > 0 }?.let {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = Spacing.md)) {
                    Text(Money.format(it.amountMinor, it.currency), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(it.source.label()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        secretText(progress)?.let {
            // Part of the count's spoken description above.
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        CompletionBar(progress.base, progress.printedTotal, Modifier.padding(top = Spacing.md))
    }
}

/**
 * The one control row above the grid (#165): All, Owned and Missing, the language chip for sets in two or more
 * languages, and at the end the view toggle and the legend's info button. The chips scroll sideways instead of
 * wrapping, so the row stays one line at 2x font size and the grid starts in the first screen height. The Missing
 * chip splits like the header, so "Missing 158 · +42 secret" adds up with a 7/165 header (#161).
 */
@Composable
private fun FilterRow(
    state: SetDetailUiState,
    detail: SetDetail,
    onFilter: (SlotFilter) -> Unit,
    onLanguages: () -> Unit,
    onMode: (SetViewMode) -> Unit,
    onToggleLegend: () -> Unit,
) {
    val missing = detail.missing
    val compact = state.mode == SetViewMode.COMPACT
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val chipScroll = rememberScrollState()
        Row(
            Modifier.weight(1f).fadeHiddenEnd(chipScroll).horizontalScroll(chipScroll),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChoiceChip(state.filter == SlotFilter.ALL, { onFilter(SlotFilter.ALL) }, stringResource(R.string.collections_filter_all))
            ChoiceChip(
                state.filter == SlotFilter.OWNED,
                { onFilter(SlotFilter.OWNED) },
                stringResource(R.string.collections_filter_owned, detail.ownedNumbers),
            )
            ChoiceChip(
                state.filter == SlotFilter.MISSING,
                { onFilter(SlotFilter.MISSING) },
                if (missing.secret > 0 && detail.set.printedTotal > 0) {
                    stringResource(R.string.collections_filter_missing_secret, missing.base, missing.secret)
                } else {
                    stringResource(R.string.collections_filter_missing, missing.total)
                },
            )
            if (detail.languages.size >= 2) {
                val label = languageChipLabel(detail.languages, state.selectedLanguages)
                    ?: stringResource(R.string.collections_all_languages)
                val description = stringResource(R.string.collections_languages_chip_description, label)
                // Tinted while a language is chosen, so a narrowed grid never passes for the whole set.
                MenuChip(
                    active = state.selectedLanguages.isNotEmpty(),
                    onClick = onLanguages,
                    label = label,
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
        }
        IconButton(
            onClick = { onMode(if (compact) SetViewMode.CARDS else SetViewMode.COMPACT) },
            modifier = Modifier.padding(start = Spacing.xs).focusRing(CircleShape),
        ) {
            Icon(
                painterResource(if (compact) R.drawable.ic_view_cards else R.drawable.ic_view_compact),
                contentDescription = stringResource(
                    if (compact) R.string.collections_view_show_cards else R.string.collections_view_show_compact,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconToggleButton(
            checked = state.legendShown,
            onCheckedChange = { onToggleLegend() },
            modifier = Modifier.focusRing(CircleShape),
            // Pearl's selection tint behind the icon while the legend is open, as on a selected chip (ADR 0014).
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Icon(
                painterResource(R.drawable.ic_info),
                contentDescription = stringResource(R.string.collections_legend_toggle),
                tint = if (state.legendShown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The set's languages behind the language chip (#165); a choice applies at once, as the chips did in the row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(languages: List<String>, selected: Set<String>, onToggle: (String?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Spacing.xl)) {
            Text(
                stringResource(R.string.collections_languages_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = ScreenPadding).semantics { heading() },
            )
            LanguageChips(languages, selected, onToggle, Modifier.padding(top = Spacing.md))
        }
    }
}

@Composable
private fun slotDescription(slot: SetSlot, printedTotal: Int): String {
    val number = padCollectorNumber(slot.localId, printedTotal)
    val label = listOfNotNull(number, slot.card?.name).joinToString(" ")
    return stringResource(
        if (slot.owned) R.string.collections_slot_owned_description else R.string.collections_slot_missing_description,
        label,
    )
}

/**
 * A numbered checklist slot (ADR 0014): owned is solid sea-glass with a white number, missing is hollow with an
 * outline and a muted number, so shape carries the meaning and not a tint. The selected missing slot (the one in
 * the bar below) gets a thicker ink outline. TalkBack reads "006 Bulbasaur, owned".
 */
@Composable
private fun CompactSlot(slot: SetSlot, printedTotal: Int, selected: Boolean, onClick: () -> Unit) {
    val extra = LumifoilTheme.colors
    val shape = MaterialTheme.shapes.extraSmall
    val border = when {
        selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
        slot.owned -> null
        else -> BorderStroke(1.5.dp, extra.missingSlotOutline)
    }
    val description = slotDescription(slot, printedTotal)
    Box(
        Modifier
            .aspectRatio(1.15f)
            .heightIn(min = Spacing.touchTarget)
            .focusRing(shape)
            .clip(shape)
            .then(if (slot.owned) Modifier.background(extra.ownedSlot) else Modifier)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = padCollectorNumber(slot.localId, printedTotal),
            style = numberStyle(MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)),
            color = if (slot.owned) extra.onOwnedSlot else extra.onMissingSlot,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(horizontal = Spacing.xxs),
        )
    }
}

/**
 * A slot in the Cards view: owned cards in full colour, missing ones grey and faded with the hollow slot outline and
 * the word "Missing" on a line under the number. Without art (none on the CDN, or offline before the first load)
 * the numbered placeholder shows.
 */
@Composable
private fun CardSlot(slot: SetSlot, printedTotal: Int, photos: OwnPhotos, selected: Boolean, onClick: () -> Unit) {
    val description = slotDescription(slot, printedTotal)
    val shape = MaterialTheme.shapes.small
    val outline = when {
        selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
        slot.owned -> null
        else -> BorderStroke(1.5.dp, LumifoilTheme.colors.missingSlotOutline)
    }
    Column(
        Modifier
            .focusRing(shape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
    ) {
        CardImage(
            card = slot.card,
            localId = slot.localId,
            printedTotal = printedTotal,
            modifier = Modifier.fillMaxWidth().then(if (outline != null) Modifier.border(outline, shape) else Modifier),
            ownPhoto = photos.firstOf(slot.copyUuids),
            preferOwnPhoto = photos.preferOwn,
            missing = !slot.owned,
        )
        Text(
            text = padCollectorNumber(slot.localId, printedTotal),
            style = numberStyle(MaterialTheme.typography.labelSmall),
            color = if (slot.owned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        // "Missing" on its own line: next to the number it was cut to "Miss…" at 1.3x font size (#101).
        if (!slot.owned) {
            Text(
                text = stringResource(R.string.collections_slot_missing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The bar for a missing slot: its number and name, why it is missing, and Open card when the card is installed. */
@Composable
private fun MissingSlotBar(slot: SetSlot, printedTotal: Int, onOpen: (() -> Unit)?, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 6.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = ScreenPadding, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = buildAnnotatedString {
                            append(
                                numberOverTotal(
                                    padCollectorNumber(slot.localId, printedTotal),
                                    padCollectorNumber(printedTotal.toString(), printedTotal),
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                            slot.card?.name?.let { append("  "); append(it) }
                        },
                        style = numberStyle(MaterialTheme.typography.titleSmall),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (slot.card == null) {
                            stringResource(R.string.collections_slot_unknown)
                        } else {
                            stringResource(R.string.collections_slot_missing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onOpen != null) {
                    TextAction(stringResource(R.string.collections_open_card), onClick = onOpen)
                }
                IconButton(onClick = onClose, modifier = Modifier.focusRing(CircleShape)) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.collections_slot_close))
                }
            }
        }
    }
}

/** Width of the fade at the end of the filter row. */
private val ChipRowFade = 24.dp

/**
 * Fades the end of a sideways-scrolling row while more of it is hidden there (#165), so the language chip off the
 * edge of a narrow screen or at a large font is visibly still to come. Mirrored for right-to-left layouts.
 */
private fun Modifier.fadeHiddenEnd(scroll: ScrollState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (scroll.canScrollForward) {
                val fade = ChipRowFade.toPx().coerceAtMost(size.width)
                val rtl = layoutDirection == LayoutDirection.Rtl
                val start = if (rtl) 0f else size.width - fade
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = if (rtl) listOf(Color.Transparent, Color.Black) else listOf(Color.Black, Color.Transparent),
                        startX = start,
                        endX = start + fade,
                    ),
                    topLeft = Offset(start, 0f),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
