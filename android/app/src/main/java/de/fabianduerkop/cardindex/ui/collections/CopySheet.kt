// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CollectionSummary
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.scan.CopyDefaults
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Grader
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.model.finishOrder
import de.fabianduerkop.cardindex.ui.components.ChoiceChip
import de.fabianduerkop.cardindex.ui.components.PrimaryButton
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.theme.Spacing
import de.fabianduerkop.cardindex.ui.theme.TabularFigures
import java.time.Instant
import java.time.ZoneOffset

/**
 * The copy sheet from the mockup ("Edit copy"): it edits a copy or, for a new [copy] (id 0), adds one. Turning on
 * Graded swaps the condition for grader and grade. [prints] are the languages the card can be switched to.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CopySheet(
    title: String,
    copy: OwnedCopyEntity,
    prints: List<CardEntity>,
    collections: List<CollectionSummary>,
    initialCollections: Set<Long>,
    onDismiss: () -> Unit,
    onSave: (OwnedCopyEntity, Set<Long>) -> Unit,
    onDelete: (() -> Unit)?,
    extraContent: @Composable (OwnedCopyEntity) -> Unit = {},
) {
    var draft by remember(copy.uuid) { mutableStateOf(copy) }
    var chosenCollections by remember(copy.uuid, initialCollections) { mutableStateOf(initialCollections) }
    val initialPrice = remember(copy.uuid) { copy.purchasePriceCents?.let { Money.formatPlain(it) }.orEmpty() }
    var priceText by rememberSaveable(copy.uuid) { mutableStateOf(initialPrice) }
    var pickingDate by remember { mutableStateOf(false) }
    val graded = draft.grader != null
    val priceValid = priceText.isBlank() || Money.parseMinor(priceText) != null
    // An edit of a saved copy says so above the buttons, so leaving with Cancel or Back is a clear choice.
    val changed = copy.id != 0L && (draft != copy || priceText != initialPrice || chosenCollections != initialCollections)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    stringResource(if (copy.id == 0L) R.string.copy_add_title else R.string.copy_edit_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel(stringResource(R.string.copy_quantity), Modifier.weight(1f))
                    StepButton(
                        icon = R.drawable.ic_remove,
                        description = stringResource(R.string.copy_quantity_decrease),
                        enabled = draft.quantity > 1,
                        onClick = { draft = draft.copy(quantity = (draft.quantity - 1).coerceAtLeast(1)) },
                    )
                    Text(
                        draft.quantity.toString(),
                        style = MaterialTheme.typography.titleLarge.merge(TextStyle(fontFeatureSettings = TabularFigures)),
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                    StepButton(
                        icon = R.drawable.ic_add,
                        description = stringResource(R.string.copy_quantity_increase),
                        onClick = { draft = draft.copy(quantity = draft.quantity + 1) },
                    )
                }

                if (prints.size > 1) {
                    FieldLabel(stringResource(R.string.copy_language))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        prints.forEach { print ->
                            ChoiceChip(
                                selected = draft.cardLanguage == print.language,
                                onClick = { draft = draft.copy(cardLanguage = print.language) },
                                label = languageName(print.language),
                            )
                        }
                    }
                }

                FieldLabel(stringResource(R.string.copy_finish))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    // Printings are the same in every language; the chosen print's row may still lack them.
                    val printings = prints.firstOrNull { it.language == draft.cardLanguage }?.printings
                        ?: prints.firstNotNullOfOrNull { it.printings }
                    finishOrder(printings).forEach { finish ->
                        ChoiceChip(
                            selected = draft.finish == finish,
                            onClick = { draft = draft.copy(finish = finish) },
                            label = stringResource(finish.label()),
                        )
                    }
                }
                FieldLabel(stringResource(R.string.copy_edition))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Edition.entries.forEach { edition ->
                        ChoiceChip(
                            selected = draft.edition == edition,
                            onClick = { draft = draft.copy(edition = edition) },
                            label = stringResource(edition.label()),
                        )
                    }
                }

                // The whole row toggles, so TalkBack reads "Graded" with the switch's state as one control (#131).
                val gradedShape = MaterialTheme.shapes.medium
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm)
                        .focusRing(gradedShape)
                        .toggleable(
                            value = graded,
                            role = Role.Switch,
                            onValueChange = { on ->
                                draft = if (on) {
                                    draft.copy(grader = Grader.PSA, condition = null)
                                } else {
                                    draft.copy(grader = null, grade = null, condition = Condition.NEAR_MINT)
                                }
                            },
                        )
                        .heightIn(min = Spacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = Spacing.md)) {
                        FieldLabel(stringResource(R.string.copy_graded))
                        Text(
                            stringResource(R.string.copy_graded_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = graded, onCheckedChange = null)
                }
                if (graded) {
                    FieldLabel(stringResource(R.string.copy_grader))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Grader.entries.forEach { grader ->
                            ChoiceChip(
                                selected = draft.grader == grader,
                                onClick = { draft = draft.copy(grader = grader) },
                                label = grader.displayName(),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft.grade.orEmpty(),
                        onValueChange = { draft = draft.copy(grade = it.take(8)) },
                        label = { Text(stringResource(R.string.copy_grade)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(140.dp),
                    )
                } else {
                    FieldLabel(stringResource(R.string.copy_condition))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Condition.entries.forEach { condition ->
                            ChoiceChip(
                                selected = draft.condition == condition,
                                onClick = { draft = draft.copy(condition = condition) },
                                label = stringResource(condition.label()),
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it.take(12) },
                        label = { Text(stringResource(R.string.copy_purchase_price)) },
                        suffix = { Text(draft.purchaseCurrency ?: Money.localCurrency()) },
                        isError = !priceValid,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        draft.purchaseDate?.let(::formatDate) ?: stringResource(R.string.copy_bought_on),
                        onClick = { pickingDate = true },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (collections.isNotEmpty()) {
                    FieldLabel(stringResource(R.string.copy_collections))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ChoiceChip(
                            selected = chosenCollections.isEmpty(),
                            onClick = { chosenCollections = emptySet() },
                            label = stringResource(R.string.collections_only_all_cards),
                        )
                        collections.forEach { collection ->
                            ChoiceChip(
                                selected = collection.id in chosenCollections,
                                onClick = {
                                    chosenCollections = if (collection.id in chosenCollections) {
                                        chosenCollections - collection.id
                                    } else {
                                        chosenCollections + collection.id
                                    }
                                },
                                label = collection.name,
                            )
                        }
                    }
                }

                extraContent(draft)

                if (onDelete != null) {
                    TextAction(stringResource(R.string.copy_delete), onClick = onDelete, destructive = true)
                }
            }
            HorizontalDivider(Modifier.padding(top = Spacing.sm), color = MaterialTheme.colorScheme.outlineVariant)
            if (changed) {
                Text(
                    stringResource(R.string.copy_unsaved),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = ScreenPadding)
                        .padding(top = Spacing.md)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                SecondaryButton(stringResource(R.string.cancel), onClick = onDismiss)
                PrimaryButton(
                    text = stringResource(if (copy.id == 0L) R.string.copy_add else R.string.copy_save),
                    onClick = {
                        val cents = Money.parseMinor(priceText)
                        val saved = draft.copy(
                            purchasePriceCents = cents,
                            purchaseCurrency = cents?.let { draft.purchaseCurrency ?: Money.localCurrency() },
                        )
                        onSave(saved, chosenCollections)
                    },
                    enabled = priceValid && (!graded || !draft.grade.isNullOrBlank()),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = draft.purchaseDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    // The picker works in UTC midnight millis, so read the date back in UTC as well.
                    draft = draft.copy(
                        purchaseDate = state.selectedDateMillis?.let {
                            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        },
                    )
                    pickingDate = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    draft = draft.copy(purchaseDate = null)
                    pickingDate = false
                }) { Text(stringResource(R.string.copy_clear_date)) }
            },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = modifier.padding(top = Spacing.md))
}

/** A round 48 dp minus or plus for the quantity, on the quiet Pearl outline. */
@Composable
private fun StepButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.focusRing(CircleShape).size(Spacing.touchTarget),
        border = BorderStroke(1.dp, if (enabled) colors.outlineVariant else colors.onSurface.copy(alpha = 0.12f)),
    ) { Icon(painterResource(icon), description, tint = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f)) }
}

/** A new copy of [card] with the defaults a scan would use. */
internal fun newCopyOf(card: CardEntity): OwnedCopyEntity =
    OwnedCopyEntity(
        cardId = card.id,
        cardLanguage = card.language,
        finish = CopyDefaults.finishFor(card.printings),
        createdAt = Instant.EPOCH,
    )
