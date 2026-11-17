// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
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
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.numberOverTotal
import de.fabianduerkop.cardindex.ui.components.padCollectorNumber
import de.fabianduerkop.cardindex.ui.containerViewModelFactory
import de.fabianduerkop.cardindex.ui.theme.EnergyColors
import de.fabianduerkop.cardindex.ui.theme.TabularFigures

/**
 * Set detail (ADR 0010): the set as a grid with one slot per number. Compact shows numbered slots filled with the
 * energy colour; Cards shows small card images, missing ones dimmed in place. A missing slot opens a bar at the
 * bottom instead of a new screen, so the user can scan the grid quickly.
 */
@Composable
fun SetDetailScreen(
    onBack: () -> Unit,
    onOpenCard: (cardId: String, language: String) -> Unit,
    viewModel: SetDetailViewModel = viewModel(
        factory = containerViewModelFactory { container, saved ->
            SetDetailViewModel(container.collections, container.ownPhotos, container.prices, container.primaryPriceSource, saved)
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = detail?.slots?.firstOrNull { it.localId == selectedKey }

    DetailScaffold(
        title = detail?.set?.name.orEmpty(),
        onBack = onBack,
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
    onSlot: (SetSlot) -> Unit,
) {
    val owned = detail.ownedNumbers
    val missing = detail.slots.size - owned
    val shown = when (state.filter) {
        SlotFilter.ALL -> detail.slots
        SlotFilter.OWNED -> detail.slots.filter { it.owned }
        SlotFilter.MISSING -> detail.slots.filterNot { it.owned }
    }
    val compact = state.mode == SetViewMode.COMPACT
    LazyVerticalGrid(
        columns = if (compact) GridCells.Adaptive(50.dp) else GridCells.Adaptive(76.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        header {
            SetHeader(detail, state.selectedLanguages, state.value)
        }
        header {
            LanguageChips(
                languages = detail.languages,
                selected = state.selectedLanguages,
                onToggle = onToggleLanguage,
                horizontalPadding = 0.dp,
            )
        }
        header {
            ModeSwitch(state.mode, onMode)
        }
        header {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                IndexChip(state.filter == SlotFilter.ALL, { onFilter(SlotFilter.ALL) }, stringResource(R.string.collections_filter_all))
                IndexChip(
                    state.filter == SlotFilter.OWNED,
                    { onFilter(SlotFilter.OWNED) },
                    stringResource(R.string.collections_filter_owned, owned),
                )
                IndexChip(
                    state.filter == SlotFilter.MISSING,
                    { onFilter(SlotFilter.MISSING) },
                    stringResource(R.string.collections_filter_missing, missing),
                )
            }
        }
        items(shown, key = { it.localId }) { slot ->
            if (compact) {
                CompactSlot(slot, detail.set.printedTotal, selected = slot.localId == selectedKey, onClick = { onSlot(slot) })
            } else {
                CardSlot(slot, detail.set.printedTotal, state.photos, onClick = { onSlot(slot) })
            }
        }
    }
}

private fun LazyGridScope.header(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") { content() }
}

@Composable
private fun SetHeader(detail: SetDetail, selectedLanguages: Set<String>, value: CollectionValue?) {
    val languages = selectedLanguages.ifEmpty { detail.languages.toSet() }.sorted()
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            text = if (selectedLanguages.isEmpty() && languages.size != 1) {
                stringResource(R.string.collections_all_languages)
            } else {
                stringResource(
                    R.string.collections_languages_label,
                    languageList(languages, stringResource(R.string.collections_and)),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = numberOverTotal(
                    detail.ownedNumbers.toString(),
                    detail.set.printedTotal.toString(),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                style = MaterialTheme.typography.displayMedium.merge(TextStyle(fontFeatureSettings = TabularFigures)),
                modifier = Modifier.weight(1f),
            )
            value?.takeIf { it.amountMinor > 0 }?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text(Money.format(it.amountMinor, it.currency), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(it.source.label()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: SetViewMode, onMode: (SetViewMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SetViewMode.entries.forEachIndexed { index, value ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onMode(value) },
                shape = SegmentedButtonDefaults.itemShape(index, SetViewMode.entries.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.inverseSurface,
                    activeContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ),
            ) {
                Text(
                    stringResource(
                        if (value == SetViewMode.COMPACT) R.string.collections_mode_compact else R.string.collections_mode_cards,
                    ),
                )
            }
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

@Composable
private fun CompactSlot(slot: SetSlot, printedTotal: Int, selected: Boolean, onClick: () -> Unit) {
    val type = slot.energyType
    val fill = when {
        !slot.owned -> MaterialTheme.colorScheme.surface
        type != null -> EnergyColors.fill(type)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val textColor = when {
        !slot.owned -> MaterialTheme.colorScheme.onSurfaceVariant
        type != null -> EnergyColors.onFill(type)
        else -> MaterialTheme.colorScheme.surface
    }
    val border = when {
        selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
        slot.owned -> null
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }
    val description = slotDescription(slot, printedTotal)
    Box(
        Modifier
            .aspectRatio(1.15f)
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .then(if (border != null) Modifier.border(border, MaterialTheme.shapes.small) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = padCollectorNumber(slot.localId, printedTotal),
            style = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = TabularFigures)),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(start = 5.dp, bottom = 3.dp),
        )
    }
}

@Composable
private fun CardSlot(slot: SetSlot, printedTotal: Int, photos: OwnPhotos, onClick: () -> Unit) {
    val description = slotDescription(slot, printedTotal)
    Column(Modifier.clickable(onClick = onClick).semantics { contentDescription = description }) {
        CardImage(
            card = slot.card,
            localId = slot.localId,
            printedTotal = printedTotal,
            modifier = Modifier.fillMaxWidth(),
            ownPhoto = photos.firstOf(slot.copyUuids),
            preferOwnPhoto = photos.preferOwn,
            missing = !slot.owned,
        )
        Text(
            text = if (slot.owned) {
                padCollectorNumber(slot.localId, printedTotal)
            } else {
                padCollectorNumber(slot.localId, printedTotal) + " " + stringResource(R.string.collections_slot_missing)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (slot.owned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MissingSlotBar(slot: SetSlot, printedTotal: Int, onOpen: (() -> Unit)?, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceBright, shadowElevation = 6.dp) {
        Column {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = ScreenPadding, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = listOfNotNull(padCollectorNumber(slot.localId, printedTotal) + "/" + padCollectorNumber(printedTotal.toString(), printedTotal), slot.card?.name)
                            .joinToString(" "),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
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
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.collections_open_card)) }
                }
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.collections_slot_close))
                }
            }
        }
    }
}
