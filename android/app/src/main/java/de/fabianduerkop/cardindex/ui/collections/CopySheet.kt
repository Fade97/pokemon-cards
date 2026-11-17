// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CollectionSummary
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Grader
import de.fabianduerkop.cardindex.model.Money
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
    var priceText by rememberSaveable(copy.uuid) {
        mutableStateOf(copy.purchasePriceCents?.let { Money.formatPlain(it) }.orEmpty())
    }
    var pickingDate by remember { mutableStateOf(false) }
    val graded = draft.grader != null
    val priceValid = priceText.isBlank() || Money.parseMinor(priceText) != null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(if (copy.id == 0L) R.string.copy_add_title else R.string.copy_edit_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel(stringResource(R.string.copy_quantity), Modifier.weight(1f))
                    OutlinedIconButton(
                        onClick = { draft = draft.copy(quantity = (draft.quantity - 1).coerceAtLeast(1)) },
                        enabled = draft.quantity > 1,
                    ) { Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.copy_quantity_decrease)) }
                    Text(
                        draft.quantity.toString(),
                        style = MaterialTheme.typography.titleLarge.merge(TextStyle(fontFeatureSettings = TabularFigures)),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    OutlinedIconButton(onClick = { draft = draft.copy(quantity = draft.quantity + 1) }) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.copy_quantity_increase))
                    }
                }

                if (prints.size > 1) {
                    FieldLabel(stringResource(R.string.copy_language))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        prints.forEach { print ->
                            IndexChip(
                                selected = draft.cardLanguage == print.language,
                                onClick = { draft = draft.copy(cardLanguage = print.language) },
                                label = languageName(print.language),
                            )
                        }
                    }
                }

                FieldLabel(stringResource(R.string.copy_finish))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Finish.entries.forEach { finish ->
                        IndexChip(
                            selected = draft.finish == finish,
                            onClick = { draft = draft.copy(finish = finish) },
                            label = stringResource(finish.label()),
                        )
                    }
                }
                FieldLabel(stringResource(R.string.copy_edition))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Edition.entries.forEach { edition ->
                        IndexChip(
                            selected = draft.edition == edition,
                            onClick = { draft = draft.copy(edition = edition) },
                            label = stringResource(edition.label()),
                        )
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel(stringResource(R.string.copy_graded))
                        Text(
                            stringResource(R.string.copy_graded_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = graded,
                        onCheckedChange = { on ->
                            draft = if (on) {
                                draft.copy(grader = Grader.PSA, condition = null)
                            } else {
                                draft.copy(grader = null, grade = null, condition = Condition.NEAR_MINT)
                            }
                        },
                    )
                }
                if (graded) {
                    FieldLabel(stringResource(R.string.copy_grader))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Grader.entries.forEach { grader ->
                            IndexChip(
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
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(140.dp),
                    )
                } else {
                    FieldLabel(stringResource(R.string.copy_condition))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Condition.entries.forEach { condition ->
                            IndexChip(
                                selected = draft.condition == condition,
                                onClick = { draft = draft.copy(condition = condition) },
                                label = stringResource(condition.label()),
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it.take(12) },
                        label = { Text(stringResource(R.string.copy_purchase_price)) },
                        suffix = { Text(draft.purchaseCurrency ?: Money.localCurrency()) },
                        isError = !priceValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                        Text(draft.purchaseDate?.let(::formatDate) ?: stringResource(R.string.copy_bought_on), maxLines = 1)
                    }
                }

                if (collections.isNotEmpty()) {
                    FieldLabel(stringResource(R.string.copy_collections))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IndexChip(
                            selected = chosenCollections.isEmpty(),
                            onClick = { chosenCollections = emptySet() },
                            label = stringResource(R.string.collections_only_all_cards),
                        )
                        collections.forEach { collection ->
                            IndexChip(
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
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.copy_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(
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
                ) { Text(stringResource(if (copy.id == 0L) R.string.copy_add else R.string.copy_save)) }
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
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = modifier.padding(top = 8.dp))
}

/** A new copy of [card] with the defaults a scan would use. */
internal fun newCopyOf(card: CardEntity): OwnedCopyEntity =
    OwnedCopyEntity(cardId = card.id, cardLanguage = card.language, createdAt = Instant.EPOCH)
