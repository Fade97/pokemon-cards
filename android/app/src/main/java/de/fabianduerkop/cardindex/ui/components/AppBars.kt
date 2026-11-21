// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalLayoutDirection
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
    PearlScaffold(
        title = title,
        modifier = modifier,
        topBar = {
            LumifoilTopAppBar(
                // Material starts a bar title without a back arrow 16 dp in; the screen's content starts at the gutter.
                // The extra inset puts both on one left edge (#218).
                title = {
                    Box(Modifier.padding(start = Spacing.gutter - BarTitleInset)) {
                        if (wordmark) LumifoilWordmark() else AppBarTitle(title)
                    }
                },
                actions = actions,
            )
        },
        content = content,
    )
}

/** Where Material's top app bar starts its title when there is no navigation icon. */
private val BarTitleInset = 16.dp

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
    PearlScaffold(
        title = title,
        modifier = modifier,
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
 * A Scaffold with the top bar at the start of its body instead of in the `topBar` slot (#120). Keyboard Tab follows
 * the order in which a layout places its children, and Scaffold places the body before the top bar, so Tab went
 * screen content → header → navigation tabs. Here the header comes first, then the content, then the bottom bar
 * (Scaffold places that after the body) and the app's navigation bar.
 *
 * [content] gets the same padding as from Scaffold, minus the top: the bar pads for the status bar itself and the
 * content starts below it. The bar's part of the top insets counts as consumed, as it did in the `topBar` slot.
 */
@Composable
private fun PearlScaffold(
    title: String,
    modifier: Modifier,
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.semantics { paneTitle = title },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = bottomBar,
    ) { padding ->
        val direction = LocalLayoutDirection.current
        Column(Modifier.fillMaxSize()) {
            topBar()
            Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(PaddingValues(top = padding.calculateTopPadding()))) {
                content(
                    PaddingValues(
                        start = padding.calculateStartPadding(direction),
                        end = padding.calculateEndPadding(direction),
                        bottom = padding.calculateBottomPadding(),
                    ),
                )
            }
        }
    }
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
