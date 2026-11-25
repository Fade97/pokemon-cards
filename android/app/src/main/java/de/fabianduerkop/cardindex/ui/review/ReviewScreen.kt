// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.db.ReviewCandidateEntity
import de.fabianduerkop.cardindex.data.images.ImageQuality
import de.fabianduerkop.cardindex.data.images.cardImageUrl
import de.fabianduerkop.cardindex.data.scan.ReviewCandidate
import de.fabianduerkop.cardindex.data.scan.ReviewItem
import de.fabianduerkop.cardindex.debug.DebugTools
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.model.finishOrder
import de.fabianduerkop.cardindex.ui.components.ExpandMark
import de.fabianduerkop.cardindex.ui.components.ChoiceChip
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.DetailScaffold
import de.fabianduerkop.cardindex.ui.components.FooterFade
import de.fabianduerkop.cardindex.ui.components.FooterFadeHeight
import de.fabianduerkop.cardindex.ui.components.NoticeTone
import de.fabianduerkop.cardindex.ui.components.PrimaryButton
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.SectionHeader
import de.fabianduerkop.cardindex.ui.components.StatusNotice
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.TextActionInset
import de.fabianduerkop.cardindex.ui.components.collectorNumberDescription
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.components.numberWithTotal
import de.fabianduerkop.cardindex.ui.components.padCollectorNumber
import de.fabianduerkop.cardindex.ui.collections.label
import de.fabianduerkop.cardindex.ui.scan.languageName
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlinx.coroutines.launch

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
    // The footer's Change scrolls the form to the finish, edition, condition and quantity choices (#218).
    val choices = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    DetailScaffold(
        title = stringResource(R.string.review_title),
        onBack = onBack,
        modifier = modifier,
        backDescription = stringResource(R.string.review_back),
        actions = {
            if (state.current != null) {
                Text(
                    stringResource(R.string.review_position, state.position, state.total),
                    // The bar pads its actions by 4 dp: this ends the counter on the 20 dp gutter (#218).
                    Modifier.padding(end = Spacing.lg),
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
                                card = item.candidates.firstOrNull { it.rank == state.draft.rank }?.let { chosen ->
                                    // The set or language too when that is what tells the matches apart, so two
                                    // prints of "025/165 Pikachu" never read the same here (#250).
                                    val differences = candidateDifferences(item.candidates)
                                    listOfNotNull(
                                        "${numberWithTotal(chosen.card.localId, chosen.set.printedTotal)} ${chosen.card.name}",
                                        chosen.set.name.takeIf { differences.set },
                                        languageName(chosen.card.language).takeIf { differences.language },
                                    ).joinToString(", ")
                                },
                                target = target?.name,
                                onChange = { scope.launch { choices.bringIntoView() } },
                                onSkip = viewModel::skip,
                                onConfirm = viewModel::confirm,
                                saving = state.saving,
                                saveFailed = state.saveFailed,
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
                else -> key(item.entity.id) {
                    // Starts the item's short settle time, so a tap meant for the last card can't act on it (#248).
                    LaunchedEffect(item.entity.id) { viewModel.onShown(item.entity.id) }
                    ItemForm(
                        item,
                        state.draft,
                        viewModel,
                        choices,
                        onFindCard = { onFindCard(item) },
                        saving = state.saving,
                    )
                }
            }
            if (item != null) FooterFade(Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ItemForm(
    item: ReviewItem,
    draft: ReviewDraft,
    viewModel: ReviewViewModel,
    choices: BringIntoViewRequester,
    saving: Boolean = false,
    onFindCard: () -> Unit,
) {
    val chosen = item.candidates.firstOrNull { it.rank == draft.rank } ?: item.candidates.firstOrNull()
    val differences = remember(item.candidates) { candidateDifferences(item.candidates) }
    var comparing by remember { mutableStateOf(false) }
    if (comparing) CompareDialog(item, chosen, onDismiss = { comparing = false })
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            // Tap to see the scan large, next to the card Confirm would add (#161).
            val enlargeLabel = stringResource(R.string.review_crop_enlarge)
            // The expand mark says the scan opens the side-by-side comparison, not only to TalkBack (#221).
            Box {
                AsyncImage(
                    model = item.cropFile,
                    contentDescription = stringResource(R.string.review_crop),
                    modifier = Modifier
                        // 76 dp, not 88: so all three matches clear the footer at 1.3x font; the expand mark
                        // opens the scan large (#250).
                        .width(76.dp)
                        .aspectRatio(63f / 88f)
                        .focusRing(MaterialTheme.shapes.small)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = enlargeLabel, role = Role.Button) { comparing = true },
                    contentScale = ContentScale.Crop,
                )
                ExpandMark(Modifier.align(Alignment.BottomEnd).padding(Spacing.xs), size = 14.dp)
            }
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
                // Only when it says something the big number above doesn't: the read number differs from the card's.
                val shown = chosen?.let { c -> numberWithTotal(c.card.localId, c.set.printedTotal) }
                item.entity.numberRead?.takeIf { it != shown }?.let { read ->
                    Text(
                        stringResource(R.string.review_number_read, read),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Similar matches that differ only in their language get the one clue to check (#221).
        val languageOnly = item.entity.reason == ReviewReason.SIMILAR_CARDS &&
            differences.language && !differences.number && !differences.set
        StatusNotice(stringResource(if (languageOnly) R.string.review_why_language_only else reasonExplanation(item.entity.reason)))

        // None of the matches is right (#129): search for the card, keeping this scan and the choices below. Next to
        // the heading, so the way out shows on the first screen at any font size (#218).
        SectionHeader(
            stringResource(if (item.candidates.size > 1) R.string.review_best_matches else R.string.review_card),
            // The notice above already sets it apart; its own top kept the third match under the fade at 1.3x (#250).
            snug = true,
            action = {
                TextAction(
                    stringResource(R.string.review_find_card_short),
                    onClick = onFindCard,
                    // Off while this card is being saved, like every other choice on it (#250).
                    enabled = !saving,
                    modifier = Modifier.offset(x = TextActionInset),
                    contentDescription = stringResource(R.string.review_find_card_description),
                )
            },
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item.candidates.forEach { c ->
                CandidateRow(
                    c,
                    differences,
                    selected = c.rank == draft.rank,
                    onClick = { viewModel.selectCandidate(c.rank) },
                    enabled = !saving,
                )
            }
        }

        // The heading sits right on its explanation, so both clear the footer on the first screen (#218).
        Column(Modifier.bringIntoViewRequester(choices), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            SectionHeader(stringResource(R.string.variant_your_choice), dense = true)
            Text(
                stringResource(R.string.variant_not_detected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VariantChoices(
                finishes = finishOrder(item.candidates.firstOrNull { it.rank == draft.rank }?.card?.printings),
                finish = draft.finish,
                edition = draft.edition,
                condition = draft.condition,
                finishChosen = draft.finishChosen,
                editionChosen = draft.editionChosen,
                conditionChosen = draft.conditionChosen,
                onFinish = viewModel::setFinish,
                onEdition = viewModel::setEdition,
                onCondition = viewModel::setCondition,
                enabled = !saving,
            )
            QuantityRow(draft.quantity, viewModel::changeQuantity, Modifier.padding(top = Spacing.sm), enabled = !saving)
        }

        TextAction(
            stringResource(R.string.review_discard),
            onClick = viewModel::discard,
            enabled = !saving,
            // The last action ends clear of the footer fade at the end of the scroll (#221).
            modifier = Modifier.padding(top = Spacing.sm, bottom = if (viewModel.collectsNotCards) 0.dp else FooterFadeHeight),
            alignToText = true,
        )
        // Debug builds only (#244): the crop goes to training as a negative, apart from "Remove", which also covers
        // real cards not worth keeping (blurred, cut off).
        if (viewModel.collectsNotCards) {
            TextAction(
                stringResource(R.string.review_not_a_card),
                onClick = viewModel::notACard,
                enabled = !saving,
                modifier = Modifier.padding(bottom = FooterFadeHeight),
                alignToText = true,
            )
        }
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
    /** The finish chips in order: the card's printed finishes first ([finishOrder]). */
    finishes: List<Finish>,
    finish: Finish?,
    edition: Edition?,
    condition: Condition,
    finishChosen: Boolean,
    editionChosen: Boolean,
    conditionChosen: Boolean,
    onFinish: (Finish) -> Unit,
    onEdition: (Edition) -> Unit,
    onCondition: (Condition) -> Unit,
    /** False while the card is being saved: the chips look and read as off, as they are (#250). */
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        ChoiceGroup(R.string.variant_finish, finishes, finish, finishChosen, ::finishLabel, onFinish, enabled)
        ChoiceGroup(R.string.variant_edition, Edition.entries, edition, editionChosen, ::editionLabel, onEdition, enabled)
        ChoiceGroup(R.string.variant_condition, Condition.entries, condition, conditionChosen, ::conditionLabel, onCondition, enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceGroup(
    title: Int,
    values: List<T>,
    selected: T?,
    chosen: Boolean,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    enabled: Boolean,
) {
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
        values.forEach { v ->
            ChoiceChip(selected = v == selected, onClick = { onSelect(v) }, label = stringResource(label(v)), enabled = enabled)
        }
    }
}

/**
 * One candidate: number, name, then set and language, with the parts that tell the candidates apart
 * ([differences]) in strong type (#161). No similarity score: it reads as precision it doesn't have; Lab keeps it.
 */
@Composable
private fun CandidateRow(
    c: ReviewCandidate,
    differences: CandidateDifferences,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val spoken = listOf(
        collectorNumberDescription(c.card.localId, c.set.printedTotal),
        c.card.name,
        c.set.name,
        languageName(c.card.language),
    ).joinToString(", ")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            // Dimmed while the card is being saved, like the chips and buttons around it (#250).
            .alpha(if (enabled) 1f else DisabledAlpha)
            .focusRing(shape)
            .selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
            // One label: merged, the number's own description would replace the name, set and language (#218).
            .clearAndSetSemantics {
                contentDescription = spoken
                this.selected = selected
            },
        shape = shape,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline),
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
                    color = when {
                        selected -> colors.primary
                        differences.number || !(differences.set || differences.language) -> colors.onSurface
                        else -> colors.onSurfaceVariant
                    },
                )
                Text(c.card.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val setName = c.set.name
                val language = languageName(c.card.language)
                val line = stringResource(R.string.review_set_language, setName, language)
                val strong = SpanStyle(color = colors.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    buildAnnotatedString {
                        append(line)
                        if (differences.set) line.indexOf(setName).takeIf { it >= 0 }?.let { addStyle(strong, it, it + setName.length) }
                        if (differences.language) {
                            line.lastIndexOf(language).takeIf { it >= 0 }?.let { addStyle(strong, it, it + language.length) }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    // Two lines, so a long set name never pushes the language, often the deciding clue, out of view.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (c.similarity == ReviewCandidateEntity.PICKED_BY_HAND) {
                Text(
                    stringResource(R.string.review_picked_by_hand),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The scan large, next to the picture of the card Confirm would add (#161), so a worn corner or a language mark can
 * be checked without squinting at the 88 dp crop. Tap anywhere or Back to close.
 */
@Composable
private fun CompareDialog(item: ReviewItem, chosen: ReviewCandidate?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
                .clickable(onClickLabel = stringResource(R.string.review_compare_close), onClick = onDismiss),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                ComparePicture(item.cropFile, stringResource(R.string.review_crop), stringResource(R.string.review_compare_scan))
                if (chosen != null) {
                    val label = numberWithTotal(chosen.card.localId, chosen.set.printedTotal)
                    ComparePicture(
                        cardImageUrl(chosen.card.imageUrl, ImageQuality.HIGH),
                        stringResource(R.string.review_compare_card_description, chosen.card.name, label),
                        label,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ComparePicture(model: Any?, description: String, caption: String) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        AsyncImage(
            model = model,
            contentDescription = description,
            modifier = Modifier.fillMaxWidth().aspectRatio(63f / 88f).clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Fit,
        )
        Text(caption, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** After Confirm: which card was added, with Undo. Undo puts the item back in the queue with the same choices. */
@Composable
private fun UndoBar(undo: ReviewUndo, onUndo: () -> Unit) {
    val card = undo.candidate
    val title = "${numberWithTotal(card.card.localId, card.set.printedTotal)} ${card.card.name}"
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

/** How many of the card to add; in the form under the choices, so the footer keeps only what Confirm saves. */
@Composable
private fun QuantityRow(quantity: Int, onQuantity: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.review_quantity), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        QuantityButton(R.drawable.ic_remove, stringResource(R.string.review_quantity_less), enabled = enabled && quantity > 1) {
            onQuantity(-1)
        }
        Text(
            quantity.toString(),
            Modifier.width(56.dp),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        QuantityButton(R.drawable.ic_add, stringResource(R.string.review_quantity_more), enabled = enabled) { onQuantity(1) }
    }
}

/**
 * Skip and Confirm, with one line above them saying what Confirm saves ("Confirm adds 1× Holo, NM.") and a Change
 * that scrolls to the choices, so they are never accepted unseen (#218).
 */
@Composable
private fun Footer(
    draft: ReviewDraft,
    /** The chosen card, "025/165 Pikachu": named in the summary, so it shows while the form is scrolled (#250). */
    card: String?,
    target: String?,
    onChange: () -> Unit,
    onSkip: () -> Unit,
    onConfirm: () -> Unit,
    /** The item's confirmation is being written: the buttons wait and Confirm says so (#248, #250). */
    saving: Boolean = false,
    /** The last confirmation of this item failed: said right above Confirm, where the user tapped (#248, #250). */
    saveFailed: Boolean = false,
) {
    val enabled = !saving
    // Little top padding: the Change button's 48 dp touch height already spaces the summary line (#218).
    Column(
        Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs, bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val finish = draft.finish
        val edition = draft.edition
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (finish == null || edition == null) {
                Text(
                    stringResource(R.string.review_pick_variant),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                // Values nobody picked are the defaults, and say so: the camera did not read them (#218). With some
                // picked, each untouched one is marked on its own, so a default never passes for a checked value.
                val defaults = !draft.finishChosen && !draft.editionChosen && !draft.conditionChosen
                val mark: @Composable (String, Boolean) -> String = { text, chosen ->
                    if (defaults || chosen) text else stringResource(R.string.review_default_mark, text)
                }
                val finishText = mark(stringResource(finish.label()), draft.finishChosen)
                // Unlimited is the quiet default and left out, unless the user picked it: then it is part of the copy.
                val print = if (edition == Edition.UNLIMITED && !draft.editionChosen) {
                    finishText
                } else {
                    mark(stringResource(edition.label()), draft.editionChosen) + " " + finishText
                }
                // The condition in the form's words ("Near mint"), so the summary matches the chips below (#218).
                // "Near mint" stays on one line (#250).
                val condition = mark(stringResource(conditionLabel(draft.condition)).replace(' ', '\u00A0'), draft.conditionChosen)
                val saved = stringResource(R.string.collections_copy_line, draft.quantity, "$print, $condition")
                val summary = when {
                    card == null -> stringResource(if (defaults) R.string.review_confirm_adds_defaults else R.string.review_confirm_adds, saved)
                    defaults -> stringResource(R.string.review_confirm_adds_card_defaults, card, saved)
                    else -> stringResource(R.string.review_confirm_adds_card, card, saved)
                }
                Text(
                    summary,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextAction(
                stringResource(R.string.scan_variant_change),
                onClick = onChange,
                enabled = enabled,
                modifier = Modifier.offset(x = TextActionInset),
                contentDescription = stringResource(R.string.review_change_description),
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
        if (saveFailed) StatusNotice(stringResource(R.string.review_confirm_failed), tone = NoticeTone.ERROR)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SecondaryButton(stringResource(R.string.review_skip), onSkip, Modifier.weight(1f), enabled = enabled)
            PrimaryButton(
                stringResource(if (saving) R.string.review_confirm_saving else R.string.review_confirm),
                onConfirm,
                Modifier.weight(1f),
                enabled = enabled && draft.canConfirm,
            )
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
        // A disabled button's outline dims with its icon, as on the secondary buttons (#250).
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        ),
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

/** Material's alpha for disabled content, for a whole row that is off. */
private const val DisabledAlpha = 0.38f
