// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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

/**
 * The Foilark mark on its own (a card with a curved foil sweep, design/logo), e.g. on an empty state. Decorative: it
 * has no content description.
 */
@Composable
fun LumifoilMark(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Icon(
        painterResource(R.drawable.ic_foilark_mark),
        contentDescription = null,
        modifier = modifier.size(size),
        tint = LumifoilTheme.colors.mark,
    )
}

/**
 * The app wordmark: the Foilark mark and the lower-case app name ("foilark") in Schibsted (ADR 0014). It scales with the font
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
        // One line always: at 2x font size on a 360 dp phone it broke into "foilar" and "k" next to the header
        // buttons (#101). It shrinks to fit instead, at most down to its size at the default font setting.
        Text(
            stringResource(R.string.wordmark),
            style = WordmarkStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(
                minFontSize = with(LocalDensity.current) { WordmarkMinSize.toSp() },
                maxFontSize = WordmarkStyle.fontSize,
            ),
        )
    }
}

/** The wordmark's 24 sp at the default font size, as a fixed size so the floor does not grow with the setting. */
private val WordmarkMinSize = 24.dp
