// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import de.fabianduerkop.cardindex.ui.components.SectionHeader
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.theme.Spacing

/*
 * Pearl rows for Settings and the flows reached from it (#100): flat on the page like the study's settings, a section
 * heading above each topic, quiet hint text, whole-row targets of at least 48 dp. Built only from the shared theme
 * and components, so they follow light, dark and the font size.
 */

/** One topic of Settings: a heading, then its rows. */
@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionHeader(title)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
}

/** Explanatory text under a section heading or a control. */
@Composable
internal fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A switch with its title and hint. The whole row toggles, so TalkBack reads one control with its state. */
@Composable
internal fun SwitchRow(title: String, hint: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(shape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = Spacing.touchTarget)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.md)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SettingsHint(hint)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * One option of a single choice, e.g. a price source with its currency below. Put the options in a
 * `Modifier.selectableGroup()` column. Stacked rows instead of segments, so long names never get cut at large fonts.
 */
@Composable
internal fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = Spacing.touchTarget)
            // Room between two-line options at a large font, as the switch rows have (#218).
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = Spacing.md))
        Column(Modifier.weight(1f)) {
            // The same title size as the switch rows, below the section heading (#218).
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) SettingsHint(subtitle)
        }
    }
}

/** A confirmation before something that can't be undone from here, e.g. removing a language. Cancel is always offered. */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** The confirm word in the error colour, where the loss is decided (#221). */
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextAction(confirmLabel, onClick = onConfirm, destructive = destructive) },
        dismissButton = { TextAction(stringResource(android.R.string.cancel), onClick = onDismiss) },
        // Material's dialog already uses the 22 dp sheet corner (shapes.extraLarge) and a raised container.
    )
}
