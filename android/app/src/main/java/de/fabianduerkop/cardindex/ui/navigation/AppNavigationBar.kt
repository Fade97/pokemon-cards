// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.theme.HeadingWeight

/**
 * Pearl's bottom navigation (ADR 0014): the page colour with a hairline on top. The selected tab gets the sea-glass
 * icon on the selection pill and an ink label in the heading weight; the others are muted. Selection is shown by
 * shape, colour and weight together, never by colour alone. Pads for the gesture or button bar itself.
 */
@Composable
fun AppNavigationBar(
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // A quarter of a 360 dp phone fits "Collections" only up to about 1.2x font size. Larger settings shrink the label
    // just enough to fit, never below 12 sp at default size, the ADR 0014 floor (#101). The ellipsis is the last resort.
    val labelAutoSize = TextAutoSize.StepBased(
        minFontSize = with(LocalDensity.current) { LabelMinSize.toSp() },
        maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
    )
    Column(modifier) {
        HorizontalDivider(color = colors.outlineVariant)
        NavigationBar(containerColor = colors.background, contentColor = colors.onSurfaceVariant, tonalElevation = 0.dp) {
            TopLevelDestination.entries.forEach { destination ->
                val selected = isSelected(destination)
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(destination) },
                    icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                    label = {
                        Text(
                            stringResource(destination.label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) HeadingWeight else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = labelAutoSize,
                        )
                    },
                    modifier = Modifier.focusRing(NavItemShape, gap = (-4).dp),
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.primary,
                        selectedTextColor = colors.onSurface,
                        indicatorColor = colors.secondaryContainer,
                        unselectedIconColor = colors.onSurfaceVariant,
                        unselectedTextColor = colors.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

private val NavItemShape = RoundedCornerShape(16.dp)

/** 12 sp at the default font size, as a fixed size so the floor does not grow with the font setting. */
private val LabelMinSize = 12.dp
