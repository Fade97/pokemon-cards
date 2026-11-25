// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.material3.LocalTextStyle
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.components.LumifoilWordmark
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.share.ImageShare
import de.fabianduerkop.cardindex.ui.share.ShareCanvas
import de.fabianduerkop.cardindex.ui.share.ShareImageButton
import de.fabianduerkop.cardindex.ui.share.ShareImagePreview
import de.fabianduerkop.cardindex.ui.theme.Spacing

/** Up to this many missing cards are listed with their names on the image; more become a grid of numbers. */
private const val NamedLines = 18

/** Numbers shown on the missing-list image before "+ 40 more"; the text checklist always has all of them. */
private const val NumberChips = 150

/** The share images' page padding. */
private val PagePadding = 24.dp

/**
 * Share a set (#170): the slot map ("these are my last nine") or the missing list, as an image as it will be posted,
 * and the missing list also as plain text to paste into a trade chat. No card art (ADR 0009), nothing uploaded.
 */
@Composable
internal fun SetShareSheet(data: SetShareData, onDone: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val layer = rememberGraphicsLayer()
    val mapTab = tab == 0
    val chooserTitle = stringResource(R.string.set_share_chooser)
    val text = checklistText(data)
    val description = if (mapTab) mapDescription(data) else missingDescription(data)
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.set_share_title),
            Modifier.fillMaxWidth().semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        ViewSwitch(
            labels = listOf(
                stringResource(if (data.completedOn != null) R.string.set_share_certificate else R.string.set_share_map),
                stringResource(R.string.set_share_missing),
            ),
            selectedIndex = tab,
            onSelect = { tab = it },
        )
        ShareImagePreview(layer, description) {
            if (mapTab) SlotMapImage(data) else MissingListImage(data)
        }
        // The slot map has numbers only; the missing list has names while they fit (#218), numbers when there are
        // more, and past the image's limit it says the text has them all (#221).
        val missing = data.missingLines.size
        Text(
            when {
                mapTab || (missing > NamedLines && missing <= NumberChips) -> stringResource(R.string.set_share_map_no_art)
                missing > NumberChips -> stringResource(R.string.set_share_cut_note)
                else -> stringResource(R.string.set_share_no_art)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        ShareImageButton(
            stringResource(R.string.set_share_image),
            layer = layer,
            fileName = "foilark-" + (if (mapTab) "slots-" else "missing-") + data.setName.filter { it.isLetterOrDigit() }.lowercase(),
            chooserTitle = chooserTitle,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!mapTab && data.missingLines.isNotEmpty()) {
            SecondaryButton(
                stringResource(R.string.set_share_text),
                onClick = { context.startActivity(ImageShare.textIntent(text, chooserTitle)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextAction(stringResource(R.string.receipt_close), onClick = onDone)
    }
}

/** Wordmark, set name with its languages, and how far the set is: the top of both images. */
@Composable
private fun ShareHeader(data: SetShareData, overline: String) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        LumifoilWordmark()
        Spacer(Modifier.weight(1f))
        data.languages?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant) }
    }
    Spacer(Modifier.height(Spacing.md))
    Text(overline, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
    Text(
        data.setName,
        style = MaterialTheme.typography.headlineSmall,
        color = colors.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(progressText(data), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
}

/**
 * The slot map: every number of the set as a square, owned filled in sea-glass, missing hollow, wishlist ones with a
 * sea-glass outline. The squares grow to fill the page, so small sets read at thumbnail size.
 */
@Composable
internal fun SlotMapImage(data: SetShareData) {
    ShareCanvas {
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().padding(PagePadding), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            // Complete, the slot map is the certificate (#169): every square filled, and the day it was done.
            // With secret rares still open the main set is what is complete, so the image never says "Set complete"
            // above 42 empty squares (#250).
            val completedOn = data.completedOn
            val mainOnly = data.missing.secret > 0
            ShareHeader(
                data,
                stringResource(
                    when {
                        completedOn == null -> R.string.set_share_map_overline
                        mainOnly -> R.string.set_share_certificate_main_overline
                        else -> R.string.set_share_certificate_overline
                    },
                ),
            )
            if (completedOn != null) {
                Text(
                    stringResource(
                        if (mainOnly) R.string.set_share_main_completed_on else R.string.set_share_completed_on,
                        formatDate(completedOn),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primary,
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val columns = SetShare.gridColumns(data.slots.size, maxWidth.value, maxHeight.value)
                val cell = SetShare.cellSize(data.slots.size, columns, maxWidth.value, maxHeight.value).dp
                // A height-bound grid is narrower than the page: centred, like the legend's page margins.
                Column(Modifier.align(Alignment.TopCenter)) {
                    data.slots.chunked(columns).forEach { row ->
                        Row { row.forEach { SlotCell(it, cell) } }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                LegendSquare(filled = true)
                Text(stringResource(R.string.set_share_legend_owned), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.width(Spacing.sm))
                LegendSquare(filled = false)
                Text(stringResource(R.string.set_share_legend_missing), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                if (data.anyWanted) {
                    Spacer(Modifier.width(Spacing.sm))
                    LegendSquare(filled = false, wanted = true)
                    Text(stringResource(R.string.set_share_legend_wanted), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SlotCell(slot: ShareSlot, size: Dp) {
    val colors = MaterialTheme.colorScheme
    val gap = (size * 0.08f).coerceAtLeast(1.dp)
    val shape = RoundedCornerShape(size * 0.18f)
    Box(Modifier.size(size).padding(gap / 2), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    when {
                        slot.owned -> Modifier.background(colors.primary, shape)
                        slot.wanted -> Modifier.border((size * 0.07f).coerceAtLeast(1.5.dp), colors.primary, shape)
                        else -> Modifier.border(1.dp, colors.outline, shape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                slot.label,
                color = if (slot.owned) colors.onPrimary else colors.onSurfaceVariant,
                fontWeight = if (slot.owned) FontWeight.SemiBold else FontWeight.Normal,
                // A line exactly one font size high, without font padding: the theme's fixed line height is taller
                // than a cell, so the auto-size shrank the numbers to fit it instead of the cell's width (#250).
                style = LocalTextStyle.current.copy(
                    lineHeight = 1.em,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
                maxLines = 1,
                softWrap = false,
                // As large as the cell allows, so the numbers read at a glance in the image and its preview; longer
                // ids (TG05, SWSH001) shrink to fit (#250). The share canvas has font scale 1: dp values work as sp.
                autoSize = TextAutoSize.StepBased(
                    minFontSize = (size.value * 0.2f).sp,
                    maxFontSize = (size.value * 0.44f).sp,
                    stepSize = (size.value * 0.02f).coerceAtLeast(0.25f).sp,
                ),
                modifier = Modifier.padding(horizontal = gap / 2),
            )
        }
    }
}

@Composable
private fun LegendSquare(filled: Boolean, wanted: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .size(12.dp)
            .then(
                when {
                    filled -> Modifier.background(colors.primary, shape)
                    wanted -> Modifier.border(2.dp, colors.primary, shape)
                    else -> Modifier.border(1.dp, colors.outline, shape)
                },
            ),
    )
}

/**
 * The missing list: "These are the ones I need", then each missing card with its name (few missing) or a grid of
 * numbers (many). Wishlist ones carry a star. A complete set says so instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MissingListImage(data: SetShareData) {
    ShareCanvas {
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().padding(PagePadding), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ShareHeader(data, stringResource(R.string.set_share_missing_overline))
            Spacer(Modifier.height(Spacing.sm))
            val lines = data.missingLines
            if (lines.isEmpty()) {
                Text(stringResource(R.string.set_share_complete), style = MaterialTheme.typography.headlineMedium, color = colors.primary)
                return@Column
            }
            Text(
                pluralStringResource(R.plurals.set_share_missing_count, lines.size, lines.size),
                style = MaterialTheme.typography.displaySmall,
                color = colors.onSurface,
            )
            Text(stringResource(R.string.set_share_trade), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.sm))
            Column(Modifier.weight(1f)) {
                if (lines.size <= NamedLines) {
                    lines.forEach { line ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(line.number, style = numberStyle(MaterialTheme.typography.labelLarge), color = colors.onSurface)
                            Text(
                                line.name.orEmpty(),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (line.wanted) Text(SetShare.WANTED_MARK, color = colors.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        lines.take(NumberChips).forEach { line ->
                            Text(
                                line.number.substringBefore('/') + if (line.wanted) " " + SetShare.WANTED_MARK else "",
                                Modifier
                                    .border(1.dp, if (line.wanted) colors.primary else colors.outlineVariant, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                style = numberStyle(MaterialTheme.typography.labelSmall),
                                color = colors.onSurface,
                            )
                        }
                    }
                    val more = lines.size - NumberChips
                    if (more > 0) {
                        Text(
                            pluralStringResource(R.plurals.receipt_more, more, more),
                            Modifier.padding(top = Spacing.xs),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            if (data.anyWanted) {
                Text(stringResource(R.string.set_share_wanted_note), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * "87 of 165 · Secret rares 3/42", as the set screen's header: the secret count shows even at 0, so a slot map that
 * runs past 165 adds up for whoever sees it (#218). "· 3 secret rares" when the catalog does not know the secret total.
 */
@Composable
private fun progressText(data: SetShareData): String {
    val progress = data.progress
    val base = stringResource(R.string.set_share_progress, progress.base, progress.printedTotal)
    val secretTotal = progress.secret + data.missing.secret
    return when {
        data.missing.secret > 0 -> base + " · " + stringResource(R.string.collections_secret_of, progress.secret, secretTotal)
        progress.secret > 0 -> base + " · " + pluralStringResource(R.plurals.set_share_secret, progress.secret, progress.secret)
        else -> base
    }
}

/** The plain-text checklist (#170): a heading line, every missing card, the star's meaning, and where it came from. */
@Composable
private fun checklistText(data: SetShareData): String {
    val name = scopedName(data)
    val count = pluralStringResource(R.plurals.set_share_missing_count, data.missingLines.size, data.missingLines.size)
    return buildList {
        add(stringResource(R.string.set_share_text_heading, name, count))
        addAll(SetShare.checklistLines(data.missingLines))
        if (data.anyWanted) add(stringResource(R.string.set_share_wanted_note))
        add(stringResource(R.string.set_share_text_footer))
    }.joinToString("\n")
}

/** The set's name with the languages the picture covers, "151 (DE, EN)", as the preview shows them (#221). */
private fun scopedName(data: SetShareData): String = data.languages?.let { "${data.setName} ($it)" } ?: data.setName

@Composable
private fun mapDescription(data: SetShareData): String =
    stringResource(R.string.set_share_map_description, scopedName(data), progressText(data))

@Composable
private fun missingDescription(data: SetShareData): String =
    stringResource(
        R.string.set_share_missing_description,
        scopedName(data),
        pluralStringResource(R.plurals.set_share_missing_count, data.missingLines.size, data.missingLines.size),
    )
