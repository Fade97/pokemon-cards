// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.theme.Spacing

/**
 * Pearl's top app bar (ADR 0014): flat on the page colour, a quiet raised tint once content scrolls under it, title in
 * the heading weight on one line. Edge-to-edge: the bar pads for the status bar itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumifoilTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            scrolledContainerColor = colors.surfaceContainerLow,
            titleContentColor = colors.onSurface,
            navigationIconContentColor = colors.onSurface,
            actionIconContentColor = colors.onSurfaceVariant,
        ),
    )
}

/** The title of a bar: one line, ellipsized, so a long set name never pushes the actions off screen. */
@Composable
fun AppBarTitle(text: String) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Back arrow for a bar. Does the same as Android Back; [onClick] usually pops the back stack. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = stringResource(R.string.action_back)) {
    IconButton(onClick = onClick, modifier = modifier.focusRing(CircleShape)) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription)
    }
}

/**
 * Scaffold of a top-level destination (a bottom-bar tab). With [wordmark] the bar shows the Lumifoil wordmark instead
 * of [title]; screen readers still get [title] as the pane title.
 */
@Composable
fun TopLevelScaffold(
    title: String,
    modifier: Modifier = Modifier,
    wordmark: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.semantics { paneTitle = title },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LumifoilTopAppBar(
                title = { if (wordmark) LumifoilWordmark() else AppBarTitle(title) },
                actions = actions,
            )
        },
        content = content,
    )
}

/** Scaffold of a screen below a tab or a task (set, card, review): back arrow, title, optional actions. */
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backDescription: String = stringResource(R.string.action_back),
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.semantics { paneTitle = title },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LumifoilTopAppBar(
                title = { AppBarTitle(title) },
                navigationIcon = { BackButton(onBack, contentDescription = backDescription) },
                actions = actions,
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

/**
 * The header shortcut (ADR 0014): it always opens **scanning**, and says so with the scan icon and the word "Scan".
 * Never a bare plus, which could mean "add manually", "new collection" or "scan". Manual add stays where a card is
 * already chosen (card detail, search), so it is never one tap from a header.
 */
@Composable
fun ScanShortcut(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = CircleShape
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.padding(horizontal = Spacing.xs).focusRing(shape).heightIn(min = Spacing.touchTarget),
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
    ) {
        Icon(painterResource(R.drawable.ic_nav_scan), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(stringResource(R.string.shortcut_scan))
    }
}
