// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.db.ReviewCandidateEntity
import de.fabianduerkop.cardindex.data.scan.ReviewCandidate
import de.fabianduerkop.cardindex.data.scan.ReviewItem
import de.fabianduerkop.cardindex.debug.DebugTools
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.ui.components.ChoiceChip
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.DetailScaffold
import de.fabianduerkop.cardindex.ui.components.PrimaryButton
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.SectionHeader
import de.fabianduerkop.cardindex.ui.components.StatusNotice
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.components.padCollectorNumber
import de.fabianduerkop.cardindex.ui.scan.languageName
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Review queue (#9, Pearl review in `design/mockups/lumifoil`, #98): one unsure scan at a time with the crop, the
 * chosen card's collector number in large type, the top-3 matches, then finish, edition and condition as the user's
 * choice (the camera detects none of them), and quantity. Confirm adds the copy and offers Undo, which puts the item
 * back in the queue; Skip moves the card to the back of the queue, where it waits for a later pass.
 */
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onFindCard: (ReviewItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val target by viewModel.target.collectAsStateWithLifecycle()
    DetailScaffold(
        title = stringResource(R.string.review_title),
        onBack = onBack,
        modifier = modifier,
        backDescription = stringResource(R.string.review_back),
        actions = {
            if (state.current != null) {
                Text(
                    stringResource(R.string.review_position, state.position, state.total),
                    Modifier.padding(end = Spacing.gutter),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        bottomBar = {
            val item = state.current
            if (item != null || undo != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.navigationBarsPadding()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        undo?.let { UndoBar(it, viewModel::undoConfirm) }
                        if (item != null) {
                            Footer(
                                state.draft,
                                target = target?.name,
                                onQuantity = viewModel::changeQuantity,
                                onSkip = viewModel::skip,
                                onConfirm = viewModel::confirm,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        val item = state.current
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Unit
                item == null -> Done(state, onBack, viewModel::reviewSkippedAgain, viewModel::addSampleItems)
                // Each item starts at the top; its own scroll state, not the previous card's.
                else -> key(item.entity.id) { ItemForm(item, state.draft, viewModel, onFindCard = { onFindCard(item) }) }
            }
        }
    }
}

@Composable
private fun ItemForm(item: ReviewItem, draft: ReviewDraft, viewModel: ReviewViewModel, onFindCard: () -> Unit) {
    val chosen = item.candidates.firstOrNull { it.rank == draft.rank } ?: item.candidates.firstOrNull()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            AsyncImage(
                model = item.cropFile,
                contentDescription = stringResource(R.string.review_crop),
                modifier = Modifier.width(88.dp).aspectRatio(63f / 88f).clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                if (chosen != null) {
                    // The card that Confirm will add, number first: the easiest thing to check against the card.
                    CollectorNumber(
                        localId = chosen.card.localId,
                        printedTotal = chosen.set.printedTotal,
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Text(chosen.card.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.review_set_language, chosen.set.name, languageName(chosen.card.language)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.entity.numberRead?.let { read ->
                    Text(
                        stringResource(R.string.review_number_read, read),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        StatusNotice(stringResource(reasonExplanation(item.entity.reason)), Modifier.padding(top = Spacing.sm))

        SectionHeader(stringResource(if (item.candidates.size > 1) R.string.review_best_matches else R.string.review_card))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item.candidates.forEach { c ->
                CandidateRow(c, selected = c.rank == draft.rank, onClick = { viewModel.selectCandidate(c.rank) })
            }
        }
        // None of the matches is right (#129): search for the card, keeping this scan and the choices below.
        TextAction(stringResource(R.string.review_find_card), onClick = onFindCard)

        SectionHeader(stringResource(R.string.variant_your_choice))
        Text(
            stringResource(R.string.variant_not_detected),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VariantChoices(
            finish = draft.finish,
            edition = draft.edition,
            condition = draft.condition,
            finishChosen = draft.finishChosen,
            editionChosen = draft.editionChosen,
            conditionChosen = draft.conditionChosen,
            onFinish = viewModel::setFinish,
            onEdition = viewModel::setEdition,
            onCondition = viewModel::setCondition,
        )

        TextAction(
            stringResource(R.string.review_discard),
            onClick = viewModel::discard,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
        )
    }
}

/**
 * Finish, edition and condition as choice chips, each group headed with its state: "default" until the user picks
 * one, "your choice" after, or "check this" when the item needs a pick before Confirm. Shared with the Scan screen's
 * sheet for the latest card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VariantChoices(
    finish: Finish?,
    edition: Edition?,
    condition: Condition,
    finishChosen: Boolean,
    editionChosen: Boolean,
    conditionChosen: Boolean,
    onFinish: (Finish) -> Unit,
    onEdition: (Edition) -> Unit,
    onCondition: (Condition) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        ChoiceGroup(R.string.variant_finish, Finish.entries, finish, finishChosen, ::finishLabel, onFinish)
        ChoiceGroup(R.string.variant_edition, Edition.entries, edition, editionChosen, ::editionLabel, onEdition)
        ChoiceGroup(R.string.variant_condition, Condition.entries, condition, conditionChosen, ::conditionLabel, onCondition)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceGroup(title: Int, values: List<T>, selected: T?, chosen: Boolean, label: (T) -> Int, onSelect: (T) -> Unit) {
    val state = when {
        selected == null -> R.string.variant_state_check
        chosen -> R.string.variant_state_chosen
        else -> R.string.variant_state_default
    }
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(top = Spacing.sm).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
        Text(
            stringResource(R.string.variant_state_separator) + stringResource(state),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected == null) colors.tertiary else colors.onSurfaceVariant,
        )
    }
    FlowRow(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        values.forEach { v -> ChoiceChip(selected = v == selected, onClick = { onSelect(v) }, label = stringResource(label(v))) }
    }
}

@Composable
private fun CandidateRow(c: ReviewCandidate, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .focusRing(shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = shape,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AsyncImage(
                model = c.card.imageUrl?.let { "$it/low.webp" },
                contentDescription = null,
                modifier = Modifier.width(40.dp).aspectRatio(63f / 88f).clip(MaterialTheme.shapes.extraSmall),
                contentScale = ContentScale.Fit,
            )
            Column(Modifier.weight(1f)) {
                CollectorNumber(
                    localId = c.card.localId,
                    printedTotal = c.set.printedTotal,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (selected) colors.primary else colors.onSurface,
                )
                Text(c.card.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.review_set_language, c.set.name, languageName(c.card.language)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (c.similarity == ReviewCandidateEntity.PICKED_BY_HAND) {
                    stringResource(R.string.review_picked_by_hand)
                } else {
                    stringResource(R.string.review_similarity, (c.similarity * 100).roundToInt().coerceIn(0, 100))
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.primary else colors.onSurfaceVariant,
            )
        }
    }
}

/** After Confirm: which card was added, with Undo. Undo puts the item back in the queue with the same choices. */
@Composable
private fun UndoBar(undo: ReviewUndo, onUndo: () -> Unit) {
    val card = undo.candidate
    val title = "${padCollectorNumber(card.card.localId, card.set.printedTotal)}/${card.set.printedTotal ?: "?"} ${card.card.name}"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.gutter, end = Spacing.sm, top = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.review_added, title),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextAction(
            stringResource(R.string.scan_undo),
            onClick = onUndo,
            contentDescription = stringResource(R.string.review_undo_description, title),
        )
    }
}

@Composable
private fun Footer(
    draft: ReviewDraft,
    target: String?,
    onQuantity: (Int) -> Unit,
    onSkip: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.review_quantity), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            QuantityButton(R.drawable.ic_remove, stringResource(R.string.review_quantity_less), enabled = draft.quantity > 1) {
                onQuantity(-1)
            }
            Text(
                draft.quantity.toString(),
                Modifier.width(56.dp),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            QuantityButton(R.drawable.ic_add, stringResource(R.string.review_quantity_more)) { onQuantity(1) }
        }
        if (draft.variantMissing) {
            Text(
                stringResource(R.string.review_pick_variant),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        // The scan target chosen on the Scan screen (#139); only said when it is more than All Cards.
        if (target != null) {
            Text(
                stringResource(R.string.review_adds_to, target),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SecondaryButton(stringResource(R.string.review_skip), onSkip, Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.review_confirm), onConfirm, Modifier.weight(1f), enabled = draft.canConfirm)
        }
    }
}

@Composable
private fun QuantityButton(icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = CircleShape
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.focusRing(shape).size(Spacing.touchTarget).semantics { contentDescription = description },
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null)
    }
}

@Composable
private fun Done(state: ReviewUiState, onBack: () -> Unit, onAgain: () -> Unit, onSample: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        val empty = state.confirmed == 0 && state.skipped == 0
        Text(
            stringResource(if (empty) R.string.review_empty_title else R.string.review_done),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
        )
        if (empty) {
            Text(stringResource(R.string.review_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                stringResource(
                    R.string.review_done_summary,
                    pluralStringResource(R.plurals.review_done_added, state.confirmed, state.confirmed),
                    pluralStringResource(R.plurals.review_done_skipped, state.skipped, state.skipped),
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrimaryButton(stringResource(R.string.review_back_to_scanning), onBack, Modifier.fillMaxWidth())
        if (state.waiting > 0) {
            Text(stringResource(R.string.review_skipped_wait), style = MaterialTheme.typography.bodyMedium)
            SecondaryButton(stringResource(R.string.review_again), onAgain, Modifier.fillMaxWidth())
        }
        if (empty && DebugTools.AVAILABLE) {
            TextAction(stringResource(R.string.review_debug_sample), onSample, Modifier.padding(top = Spacing.lg))
        }
    }
}

private fun reasonExplanation(reason: ReviewReason): Int = when (reason) {
    ReviewReason.NUMBER_NOT_READ -> R.string.review_why_number_not_read
    ReviewReason.NUMBER_DIFFERS -> R.string.review_why_number_differs
    ReviewReason.SIMILAR_CARDS -> R.string.review_why_similar_cards
    ReviewReason.CHECK_VARIANT -> R.string.review_why_check_variant
}

internal fun finishLabel(f: Finish): Int = when (f) {
    Finish.NORMAL -> R.string.review_finish_normal
    Finish.HOLO -> R.string.review_finish_holo
    Finish.REVERSE_HOLO -> R.string.review_finish_reverse_holo
    Finish.OTHER -> R.string.review_finish_other
}

internal fun editionLabel(e: Edition): Int = when (e) {
    Edition.UNLIMITED -> R.string.review_edition_unlimited
    Edition.FIRST_EDITION -> R.string.review_edition_first
    Edition.SHADOWLESS -> R.string.review_edition_shadowless
}

internal fun conditionLabel(c: Condition): Int = when (c) {
    Condition.NEAR_MINT -> R.string.review_condition_nm
    Condition.LIGHTLY_PLAYED -> R.string.review_condition_lp
    Condition.MODERATELY_PLAYED -> R.string.review_condition_mp
    Condition.HEAVILY_PLAYED -> R.string.review_condition_hp
    Condition.POOR -> R.string.review_condition_poor
}
