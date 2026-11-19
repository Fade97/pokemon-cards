// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * Pearl's choice chip (ADR 0014): selected is a sea-glass outline on the selection tint with sea-glass text, not
 * selected is a quiet outline. No check mark, so a row of chips keeps its width. Used for language filters and
 * finish, edition and condition choices. Material keeps the touch target at 48 dp. [trailingIcon] marks a chip that
 * opens a choice of its own, like the language sheet on the set detail screen (#165).
 */
@Composable
fun ChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes trailingIcon: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier.focusRing(shape),
        enabled = enabled,
        trailingIcon = trailingIcon?.let { icon ->
            { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        },
        shape = shape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.background,
            labelColor = colors.onSurface,
            selectedContainerColor = colors.secondaryContainer,
            selectedLabelColor = colors.primary,
            iconColor = colors.onSurfaceVariant,
            selectedTrailingIconColor = colors.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = colors.outlineVariant,
            selectedBorderColor = colors.primary,
            selectedBorderWidth = 1.dp,
        ),
    )
}
