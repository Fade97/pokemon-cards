// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.ui.theme.Spacing

/*
 * Pearl buttons (ADR 0014). One solid sea-glass button per screen for the main task, outlined buttons for the
 * others, text buttons for small inline actions. All are at least 48 dp high, use the medium (10 dp) corner, show the
 * focus ring, and dim with Material's disabled roles. No gradients or shimmer on controls: the foil is only on the
 * featured set cover.
 */

private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

/** The main action of a screen, e.g. "Add a copy". */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val shape = MaterialTheme.shapes.medium
    Button(
        onClick = onClick,
        modifier = modifier.focusRing(shape).heightIn(min = Spacing.touchTarget),
        enabled = enabled,
        shape = shape,
        contentPadding = ButtonPadding,
    ) { ButtonContent(text, icon) }
}

/** A further action next to or below the primary one. Sea-glass text on a quiet outline. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.focusRing(shape).heightIn(min = Spacing.touchTarget),
        enabled = enabled,
        shape = shape,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, if (enabled) colors.outlineVariant else colors.onSurface.copy(alpha = 0.12f)),
    ) { ButtonContent(text, icon) }
}

/**
 * A small inline action such as Undo or Show all. [contentDescription] gives TalkBack a fuller name when the visible
 * word is short (it must start with the visible text, so voice control still finds it). [destructive] shows the
 * text in the error colour, for actions like "Delete collection"; the word itself must still say what happens.
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    destructive: Boolean = false,
) {
    val shape = MaterialTheme.shapes.medium
    TextButton(
        onClick = onClick,
        modifier = modifier
            .focusRing(shape)
            .heightIn(min = Spacing.touchTarget)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        enabled = enabled,
        shape = shape,
        colors = if (destructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) { Text(text) }
}

@Composable
private fun RowScope.ButtonContent(text: String, @DrawableRes icon: Int?) {
    if (icon != null) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(Spacing.sm))
    }
    Text(text)
}
