// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.search

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import de.fabianduerkop.cardindex.ui.collections.CardDetailScreen
import de.fabianduerkop.cardindex.ui.collections.CollectionsRoutes
import de.fabianduerkop.cardindex.ui.navigation.TopLevelDestination

/**
 * Routes inside the Search tab. A found card opens its detail inside this tab, so the bottom bar stays on Search and
 * Back returns to the results. The card route uses the same argument names as the Collections one, since both show
 * [CardDetailScreen].
 */
object SearchRoutes {
    const val HOME = "search/home"
    const val CARD = "search/card/{${CollectionsRoutes.ARG_CARD_ID}}/{${CollectionsRoutes.ARG_LANGUAGE}}"

    fun card(cardId: String, language: String) = "search/card/${Uri.encode(cardId)}/${Uri.encode(language)}"
}

/** [onOpenLanguages] opens Settings, where the card languages are chosen. */
fun NavGraphBuilder.searchGraph(navController: NavController, onOpenLanguages: () -> Unit) {
    navigation(startDestination = SearchRoutes.HOME, route = TopLevelDestination.SEARCH.route) {
        composable(SearchRoutes.HOME) {
            SearchScreen(
                onOpenCard = { id, language -> navController.navigate(SearchRoutes.card(id, language)) },
                onOpenLanguages = onOpenLanguages,
            )
        }
        composable(SearchRoutes.CARD) { CardDetailScreen(onBack = { navController.popBackStack() }) }
    }
}
