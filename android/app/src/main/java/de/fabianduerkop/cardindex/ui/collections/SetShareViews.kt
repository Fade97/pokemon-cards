// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.SubcomposeLayout
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
    var numbersShown by rememberSaveable { mutableStateOf(false) }
    // How many numbers the missing-list image fits, as measured when it is laid out (#250).
    var fitted by remember(data) { mutableIntStateOf(-1) }
    val scroll = rememberScrollState()
    val numbersStart = remember { BringIntoViewRequester() }
    // Opened, the numbers come into view at once instead of waiting below the fold (#250).
    LaunchedEffect(numbersShown, tab) { if (numbersShown) numbersStart.bringIntoView() }
    // The heading, preview and numbers scroll; caption and actions stay in a footer, whole at any font size. The
    // preview is sized to the room the sheet actually has above the footer, measured, not a share of the window, so
    // nothing in view is cut (#250).
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            val room = maxHeight
            var headerHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = Spacing.gutter)
                    .padding(bottom = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
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
                }
                ShareImagePreview(
                    layer,
                    description,
                    maxHeight = if (room == Dp.Infinity) null else (room - headerHeight - Spacing.md - Spacing.sm).coerceAtLeast(PreviewMin),
                ) {
                    if (mapTab) SlotMapImage(data) else MissingListImage(data, onFitted = { fitted = it })
                }
                // The image's numbers are small in the preview: on request the same numbers as app text, at the
                // system font size, in groups TalkBack can step through (#250).
                if (numbersShown) ImageNumbers(data, mapTab, Modifier.bringIntoViewRequester(numbersStart))
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
                .padding(top = Spacing.sm, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The slot map has numbers only; the missing list has names while they fit (#218), numbers when there are
            // more, and when not all of them fit it says how many it shows and that the text has them all (#221, #250).
            val missing = data.missingLines.size
            val multilingualCaption = mapTab && data.multilingual && data.languages != null
            // The preview's spoken summary already ends with the language rule: the caption then adds only "no pictures".
            val captionSpoken = stringResource(R.string.set_share_map_no_art)
            Text(
                when {
                    // The language rule also in the sheet's own text, which follows the system font size (#250).
                    multilingualCaption -> stringResource(R.string.set_share_map_caption_languages, data.languages!!)
                    mapTab -> stringResource(R.string.set_share_map_no_art)
                    missing <= NamedLines -> stringResource(R.string.set_share_no_art)
                    fitted in 0 until missing -> stringResource(R.string.set_share_cut_note, fitted)
                    else -> stringResource(R.string.set_share_map_no_art)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = if (multilingualCaption) Modifier.semantics { contentDescription = captionSpoken } else Modifier,
            )
            if (hasNumbers(data, mapTab)) {
                TextAction(
                    stringResource(if (numbersShown) R.string.set_share_hide_numbers else R.string.set_share_show_numbers),
                    onClick = { numbersShown = !numbersShown },
                )
            }
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
}

/** The smallest the share preview gets on a very short window: still a recognisable picture. */
private val PreviewMin = 160.dp

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
 * "Show the numbers": the image's numbers as wrapping app text. The slot map lists the shorter of its owned and
 * missing numbers, the missing list all missing ones; wishlist numbers carry the star. Ten to a group, each its own
 * TalkBack stop, so a slot can be checked without zooming into the preview (#250).
 */
@Composable
private fun ImageNumbers(data: SetShareData, mapTab: Boolean, modifier: Modifier = Modifier) {
    val owned = data.slots.filter { it.owned }.map { it.label }
    val missing = data.missingLines.map { it.number.substringBefore('/') + if (it.wanted) " " + SetShare.WANTED_MARK else "" }
    // The slot map lists the shorter non-empty list: owned on a complete set, never an empty "Missing (0)" (#250).
    val (heading, numbers) = when {
        !mapTab -> R.string.set_share_numbers_missing to missing
        missing.isEmpty() || (owned.isNotEmpty() && owned.size <= missing.size) -> R.string.set_share_numbers_owned to owned
        else -> R.string.set_share_numbers_missing to missing
    }
    if (numbers.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(heading, numbers.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        numbers.chunked(NumberGroup).forEach { group ->
            Text(
                group.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val wanted = data.slots.filter { it.wanted }.map { it.label }
        if (mapTab && wanted.isNotEmpty()) {
            Text(
                stringResource(R.string.set_share_numbers_wanted, wanted.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = Spacing.xs).semantics { heading() },
            )
            wanted.chunked(NumberGroup).forEach { group ->
                Text(
                    group.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (!mapTab && data.anyWanted) {
            Text(stringResource(R.string.set_share_wanted_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Whether "Show the numbers" has any to show for this tab. */
private fun hasNumbers(data: SetShareData, mapTab: Boolean): Boolean =
    if (mapTab) data.slots.isNotEmpty() else data.missingLines.isNotEmpty()

/** Numbers per line of "Show the numbers": one TalkBack stop each. */
private const val NumberGroup = 10

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
                // Secret rares start a row of their own after a small gap, so the map shows where 165 ends (#250).
                val (main, secret) = data.slots.partition { !it.secret }
                val secretCount = if (main.isEmpty()) 0 else secret.size
                val columns = SetShare.gridColumns(data.slots.size, maxWidth.value, maxHeight.value, secretCount)
                val cell = SetShare.cellSize(data.slots.size, columns, maxWidth.value, maxHeight.value, secretCount).dp
                // A height-bound grid is narrower than the page: centred, like the legend's page margins.
                Column(Modifier.align(Alignment.TopCenter)) {
                    val groups = if (secretCount > 0) listOf(main, secret) else listOf(data.slots)
                    groups.forEachIndexed { index, slots ->
                        if (index > 0) Spacer(Modifier.height(cell * SetShare.SECRET_GAP))
                        slots.chunked(columns).forEach { row ->
                            Row { row.forEach { SlotCell(it, cell) } }
                        }
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
            // With more than one language on the map, a filled square means a copy in any of them, not in each (#250).
            if (data.multilingual) {
                Text(
                    stringResource(R.string.set_share_legend_any_language),
                    Modifier.padding(top = Spacing.xxs),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
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
@Composable
internal fun MissingListImage(data: SetShareData, onFitted: (Int) -> Unit = {}) {
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
                    FittedChips(lines, onFitted, Modifier.fillMaxSize())
                }
            }
            if (data.anyWanted) {
                Text(stringResource(R.string.set_share_wanted_note), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * The missing numbers as chips, as many as the image has room for, then "+ 40 more prints": measured, not a fixed
 * count, so long ids (SWSH050) never overfill the page (#250). [onFitted] gets how many it shows, for the caption.
 */
@Composable
private fun FittedChips(lines: List<MissingLine>, onFitted: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    SubcomposeLayout(modifier) { constraints ->
        val gap = Spacing.xs.roundToPx()
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val chips = subcompose("chips") {
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
        }.map { it.measure(loose) }
        // Where each chip goes, row by row, and the bottom of the rows so far after each chip.
        val positions = ArrayList<IntOffset>(chips.size)
        val bottoms = IntArray(chips.size)
        var x = 0
        var y = 0
        var rowHeight = 0
        chips.forEachIndexed { index, chip ->
            if (x > 0 && x + chip.width > width) {
                y += rowHeight + gap
                x = 0
                rowHeight = 0
            }
            positions += IntOffset(x, y)
            x += chip.width + gap
            rowHeight = maxOf(rowHeight, chip.height)
            bottoms[index] = y + rowHeight
        }
        val moreHeight = subcompose("probe") { MoreLine(lines.size) }.first().measure(loose).height + gap
        val maxHeight = constraints.maxHeight
        fun fits(count: Int): Boolean {
            if (count == 0) return true
            val bottom = bottoms[count - 1]
            return if (count == lines.size) bottom <= maxHeight else bottom + moreHeight <= maxHeight
        }
        var shown = chips.size
        while (shown > 0 && !fits(shown)) shown--
        onFitted(shown)
        val rest = lines.size - shown
        val more = if (rest > 0) subcompose("more") { MoreLine(rest) }.first().measure(loose) else null
        val chipsBottom = if (shown > 0) bottoms[shown - 1] else 0
        layout(width, if (constraints.hasBoundedHeight) maxHeight else chipsBottom + (more?.let { it.height + gap } ?: 0)) {
            for (index in 0 until shown) chips[index].place(positions[index])
            more?.place(0, chipsBottom + if (shown > 0) gap else 0)
        }
    }
}

@Composable
private fun MoreLine(count: Int) {
    Text(
        pluralStringResource(R.plurals.receipt_more, count, count),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/**
 * What TalkBack says for the slot map (#250): counts in words, not "7/165", and on a certificate its status and date:
 * "Slot map of 151 (DE, EN): 7 of 165 main-set cards owned, 0 of 42 secret rares owned".
 */
@Composable
private fun mapDescription(data: SetShareData): String {
    val progress = data.progress
    val secretTotal = progress.secret + data.missing.secret
    val parts = listOfNotNull(
        stringResource(R.string.set_share_main_owned_description, progress.base, progress.printedTotal),
        when {
            data.missing.secret > 0 -> stringResource(R.string.set_share_secret_owned_description, progress.secret, secretTotal)
            progress.secret > 0 -> pluralStringResource(R.plurals.set_share_secret, progress.secret, progress.secret)
            else -> null
        },
        data.completedOn?.let { date ->
            stringResource(
                if (data.missing.secret > 0) R.string.set_share_main_completed_on else R.string.set_share_completed_on,
                formatDate(date),
            )
        },
    )
    val rule = if (data.multilingual) stringResource(R.string.set_share_legend_any_language) else null
    // Identity, progress and language rule only: the numbers themselves are in "Show the numbers", ten to a TalkBack
    // stop, not read out twice (#250).
    return listOfNotNull(
        stringResource(R.string.set_share_map_description, scopedName(data), parts.joinToString(", ")),
        rule,
    ).joinToString(". ")
}

@Composable
private fun missingDescription(data: SetShareData): String =
    stringResource(
        R.string.set_share_missing_description,
        scopedName(data),
        pluralStringResource(R.plurals.set_share_missing_count, data.missingLines.size, data.missingLines.size),
    )
