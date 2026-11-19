// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import de.fabianduerkop.cardindex.ui.theme.Spacing
import de.fabianduerkop.cardindex.ui.theme.TabularFigures

/**
 * The Pearl component gallery (#96): every shared piece in the current theme, for checking light, dark and large
 * fonts on a device. Reached from Settings in debug builds only. Sample content, no app data.
 */
@Composable
fun DesignGalleryScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    DetailScaffold(title = stringResource(R.string.gallery_title), onBack = onBack, modifier = modifier) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        ) {
            LumifoilWordmark()
            Text(
                stringResource(R.string.gallery_note),
                Modifier.padding(top = Spacing.sm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionHeader(stringResource(R.string.gallery_cover))
            SampleCover()
            SectionHeader(stringResource(R.string.gallery_type))
            TypeSamples()
            SectionHeader(stringResource(R.string.gallery_controls))
            Controls()
            SectionHeader(stringResource(R.string.gallery_states), trailing = stringResource(R.string.gallery_section_trailing))
            States()
            SectionHeader(stringResource(R.string.gallery_icons))
            Icons()
            SectionHeader(stringResource(R.string.gallery_colors))
            Swatches()
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

@Composable
private fun SampleCover() {
    FoilCover(onClick = {}, onClickLabel = stringResource(R.string.gallery_cover_open)) {
        Text(
            stringResource(R.string.gallery_cover_series),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.gallery_cover_set), style = MaterialTheme.typography.displaySmall)
        Row(
            Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
        ) {
            listOf("181", "199", "173").forEach { CardPlaceholder(it, 165, Modifier.width(72.dp)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.gallery_cover_progress, 87, 165),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("684,20 €", style = MaterialTheme.typography.labelLarge)
        }
        CompletionBar(87, 165, Modifier.padding(top = Spacing.md))
    }
}

@Composable
private fun TypeSamples() {
    CollectorNumber(localId = "6", printedTotal = 165)
    Text("2.846,50 €", style = MaterialTheme.typography.displayMedium)
    Text(stringResource(R.string.gallery_cover_set), style = MaterialTheme.typography.headlineLarge)
    Text(stringResource(R.string.gallery_cover_set), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.gallery_info), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.gallery_cover_progress, 87, 165),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls() {
    var finish by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PrimaryButton(stringResource(R.string.gallery_primary), onClick = {}, icon = R.drawable.ic_add, modifier = Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.gallery_secondary), onClick = {}, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.Center) {
            TextAction(stringResource(R.string.gallery_text_action), onClick = {})
            ScanShortcut(onClick = {})
            BackButton(onClick = {})
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(R.string.gallery_chip_normal, R.string.gallery_chip_holo, R.string.gallery_chip_reverse).forEachIndexed { i, label ->
                ChoiceChip(selected = finish == i, onClick = { finish = i }, label = stringResource(label))
            }
        }
    }
}

@Composable
private fun States() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PrimaryButton(stringResource(R.string.gallery_disabled), onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.gallery_disabled), onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = "",
            onValueChange = {},
            label = { Text(stringResource(R.string.gallery_field_label)) },
            isError = true,
            supportingText = { Text(stringResource(R.string.gallery_field_error)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        StatusNotice(stringResource(R.string.gallery_info))
        StatusNotice(stringResource(R.string.gallery_warning), tone = NoticeTone.WARNING)
        StatusNotice(
            stringResource(R.string.gallery_error),
            tone = NoticeTone.ERROR,
            actionLabel = stringResource(R.string.gallery_retry),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Slot("001", owned = true)
            Slot("002", owned = false)
            Slot("003", owned = true)
            Spacer(Modifier.width(Spacing.sm))
            CountBadge(2)
        }
        DataRow(stringResource(R.string.gallery_data_label), "2")
    }
}

/** Owned and missing slot, as #97 will draw the checklist: solid sea-glass or a hollow outline, number in both. */
@Composable
private fun Slot(number: String, owned: Boolean) {
    val c = LumifoilTheme.colors
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        Modifier
            .size(width = 44.dp, height = 36.dp)
            .then(if (owned) Modifier.background(c.ownedSlot, shape) else Modifier.border(1.dp, c.missingSlotOutline, shape)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number,
            style = MaterialTheme.typography.labelSmall.merge(TextStyle(fontFeatureSettings = TabularFigures)),
            color = if (owned) c.onOwnedSlot else c.onMissingSlot,
        )
    }
}

/** The whole Pearl outline icon set: 24 dp grid, 1.7 stroke. Screens pick from these, not from other icon sets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Icons() {
    val icons = listOf(
        R.drawable.ic_nav_scan, R.drawable.ic_nav_collections, R.drawable.ic_nav_search, R.drawable.ic_nav_settings,
        R.drawable.ic_add, R.drawable.ic_remove, R.drawable.ic_close, R.drawable.ic_arrow_back,
        R.drawable.ic_chevron_right, R.drawable.ic_check, R.drawable.ic_undo, R.drawable.ic_pause,
        R.drawable.ic_play, R.drawable.ic_on_device, R.drawable.ic_foilark_mark,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        icons.forEach { Icon(painterResource(it), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches() {
    val s = MaterialTheme.colorScheme
    val roles = listOf(
        "background" to s.background, "surfaceContainer" to s.surfaceContainer, "onSurface" to s.onSurface,
        "onSurfaceVariant" to s.onSurfaceVariant, "outline" to s.outline, "outlineVariant" to s.outlineVariant,
        "primary" to s.primary, "secondaryContainer" to s.secondaryContainer, "tertiaryContainer" to s.tertiaryContainer,
        "error" to s.error, "positive" to LumifoilTheme.colors.positive, "focusRing" to LumifoilTheme.colors.focusRing,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        roles.forEach { (name, color) -> Swatch(name, color) }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Column(Modifier.width(96.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(color, MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
        )
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "Pearl light", showBackground = true, heightDp = 1800)
@Composable
private fun GalleryLightPreview() {
    de.fabianduerkop.cardindex.ui.theme.LumifoilTheme(darkTheme = false) { DesignGalleryScreen(onBack = {}) }
}

@Preview(name = "Pearl dark", showBackground = true, heightDp = 1800)
@Composable
private fun GalleryDarkPreview() {
    de.fabianduerkop.cardindex.ui.theme.LumifoilTheme(darkTheme = true) { DesignGalleryScreen(onBack = {}) }
}
