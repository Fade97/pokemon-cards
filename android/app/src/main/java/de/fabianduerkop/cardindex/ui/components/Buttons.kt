// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import de.fabianduerkop.cardindex.R
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
        // Control boundaries use the outline role (≥3:1); outlineVariant stays for dividers (#221).
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.onSurface.copy(alpha = 0.12f)),
        // Material's outlined label is grey now; Pearl's is sea-glass, or an enabled action reads as dimmed (#218).
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
    ) { ButtonContent(text, icon) }
}

/**
 * A small inline action such as Undo or Show all. [contentDescription] gives TalkBack a fuller name when the visible
 * word is short (it must start with the visible text, so voice control still finds it). [destructive] shows the
 * text in the error colour, for actions like "Delete collection"; the word itself must still say what happens.
 * [alignToText] shifts the button by its inner padding, so a link under a text column starts on that column's edge
 * instead of 12 dp inside it; the touch area and ripple reach into the gutter.
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    destructive: Boolean = false,
    /** A quiet action in the muted text colour, for one that should not draw the eye (#221). */
    muted: Boolean = false,
    alignToText: Boolean = false,
    /** The link leaves the app (a market page, a website): a trailing open-in-new mark says so (#221). */
    external: Boolean = false,
) {
    val shape = MaterialTheme.shapes.medium
    TextButton(
        onClick = onClick,
        modifier = modifier
            .then(if (alignToText) Modifier.offset(x = -TextActionInset) else Modifier)
            .focusRing(shape)
            .heightIn(min = Spacing.touchTarget)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        enabled = enabled,
        shape = shape,
        colors = if (destructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else if (muted) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
        if (external) {
            // The mark inside the text, so it follows the last word when the label wraps at large font, instead of
            // drifting to the row's end (#221).
            val label = buildAnnotatedString {
                append(text)
                append('\u00A0')
                // Compose needs a non-empty stand-in text; a space adds nothing to what TalkBack reads.
                appendInlineContent(EXTERNAL_MARK, " ")
            }
            val mark = InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Text(label, inlineContent = mapOf(EXTERNAL_MARK to mark))
        } else {
            Text(text)
        }
    }
}

private const val EXTERNAL_MARK = "external"

/** A text button's own start padding (Material's 12 dp); [TextAction] with alignToText, or a row of them, moves back by it. */
val TextActionInset = 12.dp

@Composable
private fun RowScope.ButtonContent(text: String, @DrawableRes icon: Int?) {
    if (icon != null) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(Spacing.sm))
    }
    Text(text)
}
