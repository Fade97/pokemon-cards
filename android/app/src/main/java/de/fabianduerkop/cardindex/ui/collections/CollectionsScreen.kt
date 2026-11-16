// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.TopLevelScaffold
import de.fabianduerkop.cardindex.ui.components.numberOverTotal
import de.fabianduerkop.cardindex.ui.theme.CardIndexTheme
import de.fabianduerkop.cardindex.ui.theme.LocalIndexExtraColors
import de.fabianduerkop.cardindex.ui.theme.TabularFigures

/** Sample values from the mockup until the screen reads Room. Prices are preformatted strings for now. */
private data class SampleSet(val name: String, val value: String, val owned: Int, val total: Int)

private val sampleSets = listOf(
    SampleSet("151", "612,30 €", 87, 165),
    SampleSet("Prismatic Evolutions", "238,10 €", 41, 131),
    SampleSet("Surging Sparks", "96,75 €", 58, 191),
    SampleSet("SV2a", "188,40 €", 19, 165),
)

/** Collections: the value header (from direction Slab, ADR 0010) above the set collections. */
@Composable
fun CollectionsScreen(modifier: Modifier = Modifier) {
    TopLevelScaffold(title = stringResource(R.string.nav_collections), modifier = modifier) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {
            item { ValueHeader(value = "1.284,60 €", copies = 412, weeklyChange = "+18,20 €") }
            items(sampleSets, key = { it.name }) { SetRow(it) }
            item {
                PlaceholderText(
                    text = stringResource(R.string.collections_placeholder),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ValueHeader(value: String, copies: Int, weeklyChange: String) {
    Column(Modifier.padding(horizontal = 18.dp)) {
        Text(
            text = stringResource(R.string.collections_value_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = pluralStringResource(R.plurals.collections_summary, copies, copies),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = stringResource(R.string.collections_weekly_change, weeklyChange),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = LocalIndexExtraColors.current.positive,
            )
        }
        HorizontalDivider(Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun SetRow(set: SampleSet) {
    val description = pluralStringResource(R.plurals.collections_set_row, set.total, set.name, set.owned, set.total, set.value)
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Name and value share the space left of the count; a long name is ellipsized, the value stays.
            Row(Modifier.weight(1f)) {
                Text(
                    text = set.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                )
                Text(
                    text = set.value,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp).alignByBaseline(),
                )
            }
            Text(
                text = numberOverTotal(
                    number = set.owned.toString(),
                    total = set.total.toString(),
                    totalColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                style = MaterialTheme.typography.titleMedium.merge(TextStyle(fontFeatureSettings = TabularFigures)),
            )
        }
        HorizontalDivider()
    }
}

@Preview(showBackground = true)
@Composable
private fun CollectionsScreenPreview() {
    CardIndexTheme { CollectionsScreen() }
}
