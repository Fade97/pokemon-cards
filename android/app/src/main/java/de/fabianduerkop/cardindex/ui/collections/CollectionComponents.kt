// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.collections.SetProgress
import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.ui.components.ChoiceChip
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.theme.Spacing
import de.fabianduerkop.cardindex.ui.theme.TabularFigures
import java.time.Instant
import java.time.ZoneId

/** Side padding of the collection screens: the shared Pearl gutter (ADR 0014). */
internal val ScreenPadding = Spacing.gutter

/** "All languages" plus one chip per language code; an empty selection means all. Wraps at large font sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LanguageChips(
    languages: List<String>,
    selected: Set<String>,
    onToggle: (String?) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = ScreenPadding,
) {
    if (languages.size < 2) return
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ChoiceChip(
            selected = selected.isEmpty(),
            onClick = { onToggle(null) },
            label = stringResource(R.string.collections_all_languages),
        )
        languages.forEach { code ->
            ChoiceChip(selected = code in selected, onClick = { onToggle(code) }, label = code.uppercase())
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
    )
}

/**
 * The value summary of the Pearl collection screens: what the cards are worth in the main market, with its source
 * ([label]) above and the price date and how many copies it covers below (#161). Only real values: without any price it says "No
 * prices yet". With [onCaptionClick] the caption is a 48 dp link (e.g. to All cards).
 */
@Composable
internal fun ValueSummary(
    value: CollectionValue?,
    caption: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    priceDate: Instant? = null,
    onCaptionClick: (() -> Unit)? = null,
) {
    val priced = value != null && value.amountMinor > 0
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = if (priced) Money.format(value.amountMinor, value.currency) else stringResource(R.string.collections_no_prices),
            style = if (priced) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineSmall,
            color = if (priced) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        val details = buildList {
            if (value != null && value.unpricedCopies > 0 && priced) {
                add(
                    pluralStringResource(
                        R.plurals.collections_value_coverage,
                        value.totalCopies,
                        value.pricedCopies,
                        value.totalCopies,
                        value.currency,
                    ),
                )
            }
            // "Prices from Nov 20, 2026" wraps as a whole, never with the date split over two lines (#218).
            priceDate?.let {
                add(stringResource(R.string.price_as_of, formatDate(it.atZone(ZoneId.systemDefault()).toLocalDate())).replace(' ', '\u00A0'))
            }
        }
        if (details.isNotEmpty()) {
            Text(
                // One line at 1x; at a large font each part on its own line, never a lone "·" at a line end (#218).
                details.joinToString(if (LocalDensity.current.fontScale > 1.15f) "\n" else " · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        if (onCaptionClick != null) {
            LinkRow(caption, onCaptionClick, Modifier.padding(top = Spacing.xs))
        } else {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/** A sea-glass text link with a chevron, at least 48 dp high, for "All cards, 12 copies" and similar. */
@Composable
private fun LinkRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier
            .focusRing(shape)
            .heightIn(min = Spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = Spacing.xxs).size(18.dp),
        )
    }
}

/**
 * A count over a total like "87/165": the count in [countColor], the slash and total muted, tabular figures (ADR
 * 0010 number hierarchy). [suffix] adds a muted word after it, e.g. "collected".
 */
@Composable
internal fun progressText(owned: Int, total: Int, countColor: Color = Color.Unspecified, suffix: String? = null): AnnotatedString {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        withStyle(SpanStyle(color = countColor, fontFeatureSettings = TabularFigures)) {
            append(owned.toString())
        }
        withStyle(SpanStyle(color = muted, fontWeight = FontWeight.Medium, fontFeatureSettings = TabularFigures)) {
            append('/')
            append(total.toString())
        }
        if (suffix != null) {
            withStyle(SpanStyle(color = muted, fontWeight = FontWeight.Normal)) {
                append(' ')
                append(suffix)
            }
        }
    }
}

/** The same count spoken: "87 of 165 collected", with ", plus 10 secret cards" when there are any (#132). */
@Composable
internal fun progressDescription(progress: SetProgress): String =
    listOfNotNull(
        stringResource(R.string.collections_progress_description, progress.base, progress.printedTotal),
        secretDescription(progress),
    ).joinToString(", ")

/** "+10 secret" for owned slots beyond the printed total (#132), null without any. */
@Composable
internal fun secretText(progress: SetProgress): String? =
    progress.secret.takeIf { it > 0 }?.let { stringResource(R.string.collections_secret, it) }

/** "plus 10 secret cards", spoken, null without any. */
@Composable
internal fun secretDescription(progress: SetProgress): String? =
    progress.secret.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.collections_secret_description, it, it) }

/**
 * One row of a Pearl list (collections, binders): optional leading icon or [leading] content (a binder's cover, #172),
 * a title that may wrap to two lines, a muted second line (at most [subtitleMaxLines]), an optional [below] line under
 * it (e.g. the weekly price change, #173), an optional [trailing] block and a chevron. The whole row is one button of
 * at least 56 dp.
 * [contentDescription] replaces what TalkBack would read from the texts, e.g. "87 of 165" instead of "87 slash 165".
 */
@Composable
internal fun ListRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    onClickLabel: String? = null,
    contentDescription: String? = null,
    below: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    trailing: @Composable () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .focusRing(MaterialTheme.shapes.small, gap = (-2).dp)
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            // After clickable, so the click action survives the cleared texts.
            .then(if (contentDescription != null) Modifier.clearAndSetSemantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = ScreenPadding, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(Spacing.md))
            }
            if (leading != null) {
                leading()
                Spacer(Modifier.width(Spacing.md))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.xxs),
                    )
                }
                below?.invoke()
            }
            Spacer(Modifier.width(Spacing.md))
            trailing()
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs).size(20.dp),
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = ScreenPadding), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The quiet line under a checklist that says what filled and hollow slots mean, so shape is never a guess. */
@Composable
internal fun ChecklistLegend(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Style of a collector number in a list or slot: tabular, so columns line up. */
@Composable
internal fun numberStyle(base: TextStyle = MaterialTheme.typography.labelMedium): TextStyle =
    base.merge(TextStyle(fontFeatureSettings = TabularFigures))

/**
 * A switch between a few views of one screen (#164), e.g. Sets / Binders / Wishlist on the Collections home. Looks
 * like Pearl's segmented buttons (selected: sea-glass outline and text on the selection tint, the rest a quiet
 * outline), with no animation, but is a group of tabs for TalkBack ("Selected, Binders, tab"): it changes what the
 * screen below shows rather than a setting. Each segment is at least 48 dp high; labels wrap at large font sizes,
 * and all segments then grow to the tallest.
 */
@Composable
internal fun ViewSwitch(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val shape = SegmentedButtonDefaults.itemShape(index, labels.size, MaterialTheme.shapes.medium)
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .weight(1f)
                    // Neighbours share one outline, as in Material's segmented buttons; the selected one draws on top,
                    // and a focused one above that, so no neighbour covers its focus ring.
                    .offset(x = (-1).dp * index)
                    .zIndex(if (focused) 2f else if (selected) 1f else 0f)
                    .onFocusChanged { focused = it.isFocused }
                    .fillMaxHeight()
                    .heightIn(min = Spacing.touchTarget)
                    .focusRing(shape)
                    .clip(shape)
                    // Unselected segments show the surface beneath, the page or a sheet, instead of painting the page colour.
                    .background(if (selected) colors.secondaryContainer else Color.Transparent)
                    .border(1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    // At large font sizes a long label breaks at a syllable with a hyphen ("Wish-list"), not mid-word.
                    style = MaterialTheme.typography.labelLarge.copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph),
                    color = if (selected) colors.primary else colors.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
