// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.ui.theme.Spacing

/**
 * A section heading with an optional quiet note at the end, e.g. "Recently added · Today", or an [action] there (a
 * [TextAction]); its 48 dp touch height then stands in for part of the padding. [dense] uses that tighter padding
 * without an action, for a heading that sits right on its own explanation.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    dense: Boolean = false,
    action: (@Composable RowScope.() -> Unit)? = null,
    /** The first heading on a screen sits 8 dp under the bar, as content does on the other tabs (#221). */
    first: Boolean = false,
) {
    val tight = dense || action != null
    Row(
        modifier.fillMaxWidth().padding(
            top = if (first) Spacing.sm else if (tight) Spacing.md else Spacing.xl,
            bottom = if (tight) Spacing.xs else Spacing.md,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke(this)
    }
}

/**
 * A label and its value on one line with a divider below, e.g. "Cardmarket trend · 112,40 €". Values use
 * proportional figures: Schibsted's tabular set also widens `.` and `,`, which breaks up prices. A long value wraps
 * instead of being cut when the font is large.
 */
@Composable
fun DataRow(label: String, value: String, modifier: Modifier = Modifier) = DataRow(listOf(label), value, modifier)

/** A [DataRow] whose label has facts joined with " · " ([JoinedText]): "Cardmarket trend · All printings". */
@Composable
fun DataRow(label: List<String>, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).padding(vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            JoinedText(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A thin completion bar for a set, e.g. 87 of 165. The surrounding text says the numbers; this repeats them for TalkBack. */
@Composable
fun CompletionBar(
    owned: Int,
    total: Int,
    modifier: Modifier = Modifier,
    marks: List<Float> = emptyList(),
    /** The rest of the set: a visible tone on the page and on raised covers alike, not the divider one (#221). */
    trackColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.40f),
) {
    val fraction = if (total > 0) (owned.toFloat() / total).coerceIn(0f, 1f) else 0f
    val track = trackColor
    val fill = MaterialTheme.colorScheme.primary
    val gap = MaterialTheme.colorScheme.background
    Canvas(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        // At least a dot of fill once anything is owned: 1 of 191 on a short bar was under a pixel and read as none.
        if (fraction > 0f) {
            drawRoundRect(fill, size = Size((size.width * fraction).coerceAtLeast(size.height), size.height), cornerRadius = radius)
        }
        // Milestones (#169) as small gaps in the bar, so 25, 50 and 75 % read as steps.
        val gapWidth = 2.dp.toPx()
        marks.forEach { mark ->
            drawRect(gap, topLeft = Offset(size.width * mark - gapWidth / 2, 0f), size = Size(gapWidth, size.height))
        }
    }
}

/**
 * A small count that asks for attention, e.g. scans waiting for review (#98). Amber, so it never reads as a selected
 * state. Hidden at zero. Put the spoken meaning on the control it sits on.
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Badge(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(if (count > 99) "99+" else count.toString(), style = MaterialTheme.typography.labelSmall)
    }
}
