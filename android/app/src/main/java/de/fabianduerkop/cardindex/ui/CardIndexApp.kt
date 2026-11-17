// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.fabianduerkop.cardindex.ui.collections.collectionsGraph
import de.fabianduerkop.cardindex.ui.lab.LabScreen
import de.fabianduerkop.cardindex.ui.review.ReviewScreen
import de.fabianduerkop.cardindex.ui.navigation.TopLevelDestination
import de.fabianduerkop.cardindex.ui.scan.ScanScreen
import de.fabianduerkop.cardindex.ui.search.SearchScreen
import de.fabianduerkop.cardindex.ui.settings.SettingsScreen

/**
 * App shell: bottom navigation with the four top-level destinations. Edge-to-edge: the outer scaffold pads for the
 * navigation bar and consumes those insets, each screen's own top app bar handles the status bar.
 */
@Composable
fun CardIndexApp(navController: NavHostController = rememberNavController()) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = { BottomBar(navController) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.SCAN.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(TopLevelDestination.SCAN.route) {
                ScanScreen(
                    onOpenReview = { navController.navigate(REVIEW_ROUTE) },
                    onOpenLab = { navController.navigate(LAB_ROUTE) },
                )
            }
            collectionsGraph(navController)
            composable(TopLevelDestination.SEARCH.route) { SearchScreen() }
            composable(TopLevelDestination.SETTINGS.route) { SettingsScreen() }
            composable(LAB_ROUTE) { LabScreen() }
            composable(REVIEW_ROUTE) { ReviewScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

/** The recognition lab, a debug-only test bench reached from the Scan screen. */
private const val LAB_ROUTE = "lab"

/** The review queue of unsure scans, opened from the Scan screen's tray. */
private const val REVIEW_ROUTE = "review"

@Composable
private fun BottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    Column {
        HorizontalDivider()
        NavigationBar {
            TopLevelDestination.entries.forEach { destination ->
                NavigationBarItem(
                    selected = current?.hierarchy?.any { it.route == destination.route } == true,
                    onClick = {
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        }
    }
}
