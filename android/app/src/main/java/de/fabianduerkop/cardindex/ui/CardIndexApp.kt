// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.ui.packs.LanguagePickerScreen
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import de.fabianduerkop.cardindex.ui.collections.collectionsGraph
import de.fabianduerkop.cardindex.ui.contributions.ContributionConsentScreen
import de.fabianduerkop.cardindex.ui.contributions.ContributionsScreen
import de.fabianduerkop.cardindex.ui.lab.LabScreen
import de.fabianduerkop.cardindex.ui.components.DesignGalleryScreen
import de.fabianduerkop.cardindex.ui.navigation.AppNavigationBar
import de.fabianduerkop.cardindex.ui.navigation.TopLevelDestination
import de.fabianduerkop.cardindex.ui.review.ReviewScreen
import de.fabianduerkop.cardindex.ui.scan.ScanScreen
import de.fabianduerkop.cardindex.ui.search.searchGraph
import de.fabianduerkop.cardindex.ui.settings.SettingsScreen

/**
 * App shell: bottom navigation with the four top-level destinations. Edge-to-edge: the outer scaffold pads for the
 * navigation bar and consumes those insets, each screen's own top app bar handles the status bar.
 */
@Composable
fun CardIndexApp(navController: NavHostController = rememberNavController()) {
    val packs = LocalContext.current.appContainer.packs
    // First run (#39): pick the card languages before anything else. Answered once; Settings changes it later.
    var picking by rememberSaveable { mutableStateOf(!packs.pickerDone && packs.wanted.isEmpty() && packs.installed.value.isEmpty) }
    if (picking) {
        LanguagePickerScreen(onDone = { picking = false })
        return
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar(navController) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.SCAN.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            // Each tab is a nested graph named after its tab, so the bottom bar keeps the tab selected on the screens
            // opened from it (#101): Scan → review and lab, Settings → contribution consent, uploads and the gallery.
            navigation(startDestination = SCAN_HOME_ROUTE, route = TopLevelDestination.SCAN.route) {
                composable(SCAN_HOME_ROUTE) {
                    ScanScreen(
                        onOpenReview = { navController.navigate(REVIEW_ROUTE) },
                        onOpenLab = { navController.navigate(LAB_ROUTE) },
                        onOpenLanguages = { navController.navigateTopLevel(TopLevelDestination.SETTINGS) },
                    )
                }
                composable(LAB_ROUTE) { LabScreen() }
                composable(REVIEW_ROUTE) { ReviewScreen(onBack = { navController.popBackStack() }) }
            }
            collectionsGraph(navController, onOpenScan = { navController.navigateTopLevel(TopLevelDestination.SCAN) })
            searchGraph(navController, onOpenLanguages = { navController.navigateTopLevel(TopLevelDestination.SETTINGS) })
            navigation(startDestination = SETTINGS_HOME_ROUTE, route = TopLevelDestination.SETTINGS.route) {
                composable(SETTINGS_HOME_ROUTE) {
                    SettingsScreen(
                        onOpenConsent = { navController.navigate(CONSENT_ROUTE) },
                        onOpenContributions = { navController.navigate(CONTRIBUTIONS_ROUTE) },
                        onOpenDesignGallery = { navController.navigate(DESIGN_ROUTE) },
                    )
                }
                composable(CONSENT_ROUTE) { ContributionConsentScreen(onBack = { navController.popBackStack() }) }
                composable(CONTRIBUTIONS_ROUTE) { ContributionsScreen(onBack = { navController.popBackStack() }) }
                composable(DESIGN_ROUTE) { DesignGalleryScreen(onBack = { navController.popBackStack() }) }
            }
        }
    }
}

/** The start screens of the Scan and Settings tabs, inside the tab graphs. */
private const val SCAN_HOME_ROUTE = "scan/home"
private const val SETTINGS_HOME_ROUTE = "settings/home"

/** The recognition lab, a debug-only test bench reached from the Scan screen. */
private const val LAB_ROUTE = "lab"

/** The review queue of unsure scans, opened from the Scan screen's tray. */
private const val REVIEW_ROUTE = "review"

/** Scan contributions (#14): the consent text, and the list of the user's uploads. */
private const val CONSENT_ROUTE = "contribute/consent"
private const val CONTRIBUTIONS_ROUTE = "contribute/mine"

/** The Pearl component gallery (#96), a debug-only page reached from Settings for checking the design system. */
private const val DESIGN_ROUTE = "design"

@Composable
private fun BottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    AppNavigationBar(
        isSelected = { destination -> current?.hierarchy?.any { it.route == destination.route } == true },
        onSelect = { navController.navigateTopLevel(it) },
    )
}

private fun NavHostController.navigateTopLevel(destination: TopLevelDestination) = navigate(destination.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
