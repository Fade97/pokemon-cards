// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.TopLevelScaffold
import de.fabianduerkop.cardindex.ui.theme.CardIndexTheme

/** Placeholder for live scanning (CameraX + recognition). Shows the collector number as it will read a card. */
@Composable
fun ScanScreen(modifier: Modifier = Modifier) {
    TopLevelScaffold(title = stringResource(R.string.nav_scan), modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Sample reading from the mockup, to show the signature type.
            CollectorNumber(localId = "6", printedTotal = 165)
            Text(
                text = stringResource(R.string.scan_sample_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlaceholderText(stringResource(R.string.scan_placeholder), Modifier.padding(top = 18.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScanScreenPreview() {
    CardIndexTheme { ScanScreen() }
}
