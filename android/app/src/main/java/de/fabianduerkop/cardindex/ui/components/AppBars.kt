// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    scrollBehavior: TopAppBarScrollBehavior? = null,
    /** A pinned block under the bar (search field, view switch) takes the tint and draws the edge itself (#221). */
    pinnedBelow: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.background,
                scrolledContainerColor = colors.surfaceContainer,
                titleContentColor = colors.onSurface,
                navigationIconContentColor = colors.onSurface,
                actionIconContentColor = colors.onSurfaceVariant,
            ),
        )
        // A hairline once content runs under the bar, like the bottom bars have, so a half-hidden row has an edge to
        // pass under (#218). Always laid out, only coloured, so nothing below moves when it shows.
        val scrolled = (scrollBehavior?.state?.overlappedFraction ?: 0f) > 0.01f
        // Under a pinned block it takes the raised tint, so no 1 dp strip of the page shows between the two (#221).
        HorizontalDivider(
            color = when {
                !scrolled -> Color.Transparent
                pinnedBelow -> colors.surfaceContainer
                else -> colors.outlineVariant
            },
        )
    }
}

/**
 * The title of a bar: one line, so a long set name never pushes the actions off screen. At a large font it first
 * shrinks to fit ("Delta-Herrschaft" whole at 1.3x on a 360 dp phone, #250); the ellipsis is the last resort.
 */
@Composable
fun AppBarTitle(text: String) {
    val style = LocalTextStyle.current
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            // Never above the full size: at a font scale under 0.8 the 16 dp floor would exceed it, which StepBased rejects.
            minFontSize = minOf(with(LocalDensity.current) { TitleMinSize.toSp() }.value, style.fontSize.value).sp,
            maxFontSize = style.fontSize,
        ),
    )
}

/** The smallest a bar title shrinks to, in dp so a large font setting cannot undo it. */
private val TitleMinSize = 16.dp

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopLevelScaffold(
    title: String,
    modifier: Modifier = Modifier,
    wordmark: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    pinnedBelow: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    PearlScaffold(
        title = title,
        modifier = modifier,
        topBar = { scrollBehavior ->
            LumifoilTopAppBar(
                // Material starts a bar title without a back arrow 16 dp in; the screen's content starts at the gutter.
                // The extra inset puts both on one left edge (#218).
                title = {
                    Box(Modifier.padding(start = Spacing.gutter - BarTitleInset)) {
                        if (wordmark) LumifoilWordmark() else AppBarTitle(title)
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
                pinnedBelow = pinnedBelow,
            )
        },
        content = content,
    )
}

/** Where Material's top app bar starts its title when there is no navigation icon. */
private val BarTitleInset = 16.dp

/** Scaffold of a screen below a tab or a task (set, card, review): back arrow, title, optional actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backDescription: String = stringResource(R.string.action_back),
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    pinnedBelow: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    PearlScaffold(
        title = title,
        modifier = modifier,
        topBar = { scrollBehavior ->
            LumifoilTopAppBar(
                title = { AppBarTitle(title) },
                navigationIcon = { BackButton(onBack, contentDescription = backDescription) },
                actions = actions,
                scrollBehavior = scrollBehavior,
                pinnedBelow = pinnedBelow,
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
 *
 * The bar stays pinned and takes its raised tint while content scrolls under it, so a half-hidden row never seems to
 * touch the title (#218).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PearlScaffold(
    title: String,
    modifier: Modifier,
    topBar: @Composable (TopAppBarScrollBehavior) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection).semantics { paneTitle = title },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = bottomBar,
    ) { padding ->
        val direction = LocalLayoutDirection.current
        Column(Modifier.fillMaxSize()) {
            topBar(scrollBehavior)
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        // Sea-glass like the other header actions (#218).
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
    ) {
        Icon(painterResource(R.drawable.ic_nav_scan), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(stringResource(R.string.shortcut_scan))
    }
}
