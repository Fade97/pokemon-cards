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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.scan.ReviewCandidate
import de.fabianduerkop.cardindex.data.scan.ReviewItem
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.scan.languageName
import kotlin.math.roundToInt

/**
 * Review queue (#9, mockup c-index "Review queue"): one unsure scan at a time with the crop, the top-3 matches,
 * variant chips (finish and edition, always editable), condition and quantity. Confirm adds the copy; Skip moves
 * the card to the back of the queue, where it waits for a later pass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ReviewViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.review_back))
                    }
                },
                actions = {
                    if (state.current != null) {
                        Text(
                            stringResource(R.string.review_position, state.position, state.total),
                            Modifier.padding(end = 18.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            val item = state.current
            if (item != null) {
                Footer(
                    state.draft,
                    onQuantity = viewModel::changeQuantity,
                    onSkip = viewModel::skip,
                    onConfirm = viewModel::confirm,
                )
            }
        },
    ) { padding ->
        val item = state.current
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Unit
                item == null -> Done(state, onBack, viewModel::reviewSkippedAgain)
                else -> ItemForm(item, state.draft, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemForm(item: ReviewItem, draft: ReviewDraft, viewModel: ReviewViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AsyncImage(
                model = item.cropFile,
                contentDescription = stringResource(R.string.review_crop),
                modifier = Modifier.width(72.dp).aspectRatio(63f / 88f).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f)) {
                item.entity.numberRead?.let { read ->
                    Text(read, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold))
                }
                Text(
                    stringResource(reasonExplanation(item.entity.reason)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionHeading(stringResource(if (item.candidates.size > 1) R.string.review_best_matches else R.string.review_card))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item.candidates.forEach { c ->
                CandidateRow(c, selected = c.rank == draft.rank, onClick = { viewModel.selectCandidate(c.rank) })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeading(stringResource(R.string.review_variant))
            if (item.entity.checkVariant && draft.variantMissing) {
                Text(
                    stringResource(R.string.review_check_this),
                    Modifier.padding(start = 8.dp, top = 6.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        ChipRow(Finish.entries, draft.finish, ::finishLabel, viewModel::setFinish)
        ChipRow(Edition.entries, draft.edition, ::editionLabel, viewModel::setEdition)

        SectionHeading(stringResource(R.string.review_condition))
        ChipRow(Condition.entries, draft.condition, ::conditionLabel, viewModel::setCondition)

        TextButton(onClick = viewModel::discard, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.review_discard))
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun CandidateRow(c: ReviewCandidate, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = c.card.imageUrl?.let { "$it/low.webp" },
                contentDescription = null,
                modifier = Modifier.width(36.dp).aspectRatio(63f / 88f).clip(RoundedCornerShape(3.dp)),
                contentScale = ContentScale.Fit,
            )
            CollectorNumber(
                localId = c.card.localId,
                printedTotal = c.set.printedTotal,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            )
            Column(Modifier.weight(1f)) {
                Text(c.card.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    stringResource(R.string.review_set_language, c.set.name, languageName(c.card.language)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                stringResource(R.string.review_similarity, (c.similarity * 100).roundToInt().coerceIn(0, 100)),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(values: List<T>, selected: T?, label: (T) -> Int, onSelect: (T) -> Unit) {
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v ->
            FilterChip(
                selected = v == selected,
                onClick = { onSelect(v) },
                label = { Text(stringResource(label(v))) },
            )
        }
    }
}

@Composable
private fun Footer(draft: ReviewDraft, onQuantity: (Int) -> Unit, onSkip: () -> Unit, onConfirm: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.review_quantity), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    val less = stringResource(R.string.review_quantity_less)
                    val more = stringResource(R.string.review_quantity_more)
                    OutlinedButton(
                        onClick = { onQuantity(-1) },
                        enabled = draft.quantity > 1,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = less },
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("−", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        draft.quantity.toString(),
                        Modifier.width(48.dp),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = { onQuantity(1) },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = more },
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.review_skip))
                    }
                    Button(onClick = onConfirm, enabled = draft.canConfirm, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.review_confirm))
                    }
                }
                if (draft.variantMissing) {
                    Text(
                        stringResource(R.string.review_pick_variant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Done(state: ReviewUiState, onBack: () -> Unit, onAgain: () -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.confirmed == 0 && state.skipped == 0) {
            PlaceholderText(stringResource(R.string.review_empty))
        } else {
            Text(stringResource(R.string.review_done), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold))
            Text(
                stringResource(
                    R.string.review_done_summary,
                    pluralStringResource(R.plurals.review_done_added, state.confirmed, state.confirmed),
                    pluralStringResource(R.plurals.review_done_skipped, state.skipped, state.skipped),
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.waiting > 0) {
                Text(stringResource(R.string.review_skipped_wait), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = onAgain) { Text(stringResource(R.string.review_again)) }
            }
        }
        Button(onClick = onBack) { Text(stringResource(R.string.review_back_to_scanning)) }
    }
}

private fun reasonExplanation(reason: ReviewReason): Int = when (reason) {
    ReviewReason.NUMBER_NOT_READ -> R.string.review_why_number_not_read
    ReviewReason.NUMBER_DIFFERS -> R.string.review_why_number_differs
    ReviewReason.SIMILAR_CARDS -> R.string.review_why_similar_cards
    ReviewReason.CHECK_VARIANT -> R.string.review_why_check_variant
}

private fun finishLabel(f: Finish): Int = when (f) {
    Finish.NORMAL -> R.string.review_finish_normal
    Finish.HOLO -> R.string.review_finish_holo
    Finish.REVERSE_HOLO -> R.string.review_finish_reverse_holo
    Finish.OTHER -> R.string.review_finish_other
}

private fun editionLabel(e: Edition): Int = when (e) {
    Edition.UNLIMITED -> R.string.review_edition_unlimited
    Edition.FIRST_EDITION -> R.string.review_edition_first
    Edition.SHADOWLESS -> R.string.review_edition_shadowless
}

private fun conditionLabel(c: Condition): Int = when (c) {
    Condition.NEAR_MINT -> R.string.review_condition_nm
    Condition.LIGHTLY_PLAYED -> R.string.review_condition_lp
    Condition.MODERATELY_PLAYED -> R.string.review_condition_mp
    Condition.HEAVILY_PLAYED -> R.string.review_condition_hp
    Condition.POOR -> R.string.review_condition_poor
}
