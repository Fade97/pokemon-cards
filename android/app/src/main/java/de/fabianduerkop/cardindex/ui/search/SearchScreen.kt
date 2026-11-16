// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.TopLevelScaffold

/** Placeholder for searching the card database of the installed language packs. */
@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    TopLevelScaffold(title = stringResource(R.string.nav_search), modifier = modifier) { padding ->
        PlaceholderText(
            text = stringResource(R.string.search_placeholder),
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}
