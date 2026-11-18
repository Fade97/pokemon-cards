// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.db.CollectionEntity
import de.fabianduerkop.cardindex.ui.components.PrimaryButton
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.theme.Spacing

/**
 * Creates a custom collection ([initial] null) or edits one (Pearl sheet: 22 dp top corners, one solid button). Create stays disabled until there is a name, as in the
 * mockup. [onDelete] is offered only when editing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionSheet(
    initial: CollectionEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String?) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initial?.description.orEmpty()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = ScreenPadding).navigationBarsPadding()) {
            Text(
                stringResource(if (initial == null) R.string.collection_new_title else R.string.collection_edit_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.collection_new_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.lg),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.collection_name)) },
                placeholder = { Text(stringResource(R.string.collection_name_placeholder)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.collection_description)) },
                minLines = 2,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
            )
            if (onDelete != null) {
                TextAction(
                    stringResource(R.string.collection_delete),
                    onClick = onDelete,
                    destructive = true,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                Text(
                    stringResource(R.string.collection_delete_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                SecondaryButton(stringResource(R.string.cancel), onClick = onDismiss)
                PrimaryButton(
                    stringResource(if (initial == null) R.string.collection_create else R.string.collection_save),
                    onClick = { onSave(name, description) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
