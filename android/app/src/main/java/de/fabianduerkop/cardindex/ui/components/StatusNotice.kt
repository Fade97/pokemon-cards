// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import de.fabianduerkop.cardindex.ui.theme.Spacing

/** How a [StatusNotice] reads: plain information, something to check, or something that failed. */
enum class NoticeTone { INFO, WARNING, ERROR }

/**
 * A short message in a tinted box, with an optional action, e.g. "Download failed. Try again". Warnings and errors
 * are announced by TalkBack when they appear. The tint is never the only signal: the text says what happened.
 */
@Composable
fun StatusNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val (container: Color, content: Color) = when (tone) {
        NoticeTone.INFO -> colors.surfaceContainer to colors.onSurface
        NoticeTone.WARNING -> colors.tertiaryContainer to colors.onTertiaryContainer
        NoticeTone.ERROR -> colors.errorContainer to colors.onErrorContainer
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.medium)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .then(if (tone == NoticeTone.INFO) Modifier else Modifier.semantics { liveRegion = LiveRegionMode.Polite }),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null) {
                val shape = MaterialTheme.shapes.medium
                TextButton(onClick = onAction, modifier = Modifier.focusRing(shape), shape = shape) {
                    Text(actionLabel, color = content)
                }
            }
        }
    }
}
