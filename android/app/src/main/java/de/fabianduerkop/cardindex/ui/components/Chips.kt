// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R

/**
 * Pearl's choice chip (ADR 0014): selected is a sea-glass outline on the selection tint with sea-glass text, not
 * selected is a quiet outline. No check mark, so a row of chips keeps its width. Used for language filters and
 * finish, edition and condition choices. Material keeps the touch target at 48 dp. A chip that opens a choice of its
 * own is a [MenuChip].
 */
@Composable
fun ChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** What TalkBack says instead of a short [label] ("All languages" for "All", "German" for "DE"). */
    contentDescription: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier
            .focusRing(shape)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        enabled = enabled,
        shape = shape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.background,
            labelColor = colors.onSurface,
            selectedContainerColor = colors.secondaryContainer,
            selectedLabelColor = colors.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            // The control role, not the divider one: at least 3:1 against the page in both themes (#221).
            borderColor = colors.outline,
            selectedBorderColor = colors.primary,
            selectedBorderWidth = 1.dp,
        ),
    )
}

/**
 * A chip that opens a choice of its own, such as the language sheet on the set detail screen (#165). It looks like a
 * [ChoiceChip], tinted while [active] (a choice narrows what is shown), but TalkBack announces it as a button, not
 * as a checkbox a tap would tick.
 */
@Composable
fun MenuChip(
    active: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    @DrawableRes trailingIcon: Int = R.drawable.ic_expand_more,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier.focusRing(shape).semantics { role = Role.Button },
        trailingIcon = {
            Icon(painterResource(trailingIcon), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        shape = shape,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (active) colors.secondaryContainer else colors.background,
            labelColor = if (active) colors.primary else colors.onSurface,
            trailingIconContentColor = if (active) colors.primary else colors.onSurfaceVariant,
        ),
        border = BorderStroke(1.dp, if (active) colors.primary else colors.outline),
    )
}
