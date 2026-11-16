// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.TopLevelScaffold

/** Placeholder for language packs, price source and currency, export and licenses. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    TopLevelScaffold(title = stringResource(R.string.nav_settings), modifier = modifier) { padding ->
        PlaceholderText(
            text = stringResource(R.string.settings_placeholder),
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}
