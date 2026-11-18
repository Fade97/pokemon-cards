// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import de.fabianduerkop.cardindex.ui.theme.WordmarkStyle

/** The faceted mark on its own, e.g. on an empty state. Decorative: it has no content description. */
@Composable
fun LumifoilMark(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Icon(
        painterResource(R.drawable.ic_lumifoil_mark),
        contentDescription = null,
        modifier = modifier.size(size),
        tint = LumifoilTheme.colors.mark,
    )
}

/**
 * The Lumifoil wordmark: the faceted mark and lower-case "lumifoil" in Schibsted (ADR 0014). It scales with the font
 * size. Screen readers hear the app name once, as a heading.
 */
@Composable
fun LumifoilWordmark(modifier: Modifier = Modifier) {
    val name = stringResource(R.string.app_name)
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = name
            heading()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        LumifoilMark()
        Text(stringResource(R.string.wordmark), style = WordmarkStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}
