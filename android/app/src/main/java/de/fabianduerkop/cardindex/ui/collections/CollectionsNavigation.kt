// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import de.fabianduerkop.cardindex.ui.navigation.TopLevelDestination

/** Routes inside the Collections tab. The tab's own route is the nested graph, so the bottom bar stays selected. */
object CollectionsRoutes {
    const val ARG_SET_ID = "setId"
    const val ARG_CARD_ID = "cardId"
    const val ARG_LANGUAGE = "language"
    const val ARG_COLLECTION_ID = "collectionId"

    /** Collection id of the All cards list, which has no row of its own. */
    const val ALL_CARDS = -1L

    const val HOME = "collections/home"
    const val SET = "collections/set/{$ARG_SET_ID}"
    const val CARD = "collections/card/{$ARG_CARD_ID}/{$ARG_LANGUAGE}"
    const val COLLECTION = "collections/collection/{$ARG_COLLECTION_ID}"

    fun set(setId: String) = "collections/set/${Uri.encode(setId)}"
    fun card(cardId: String, language: String) = "collections/card/${Uri.encode(cardId)}/${Uri.encode(language)}"
    fun collection(id: Long) = "collections/collection/$id"
}

fun NavGraphBuilder.collectionsGraph(navController: NavController) {
    navigation(startDestination = CollectionsRoutes.HOME, route = TopLevelDestination.COLLECTIONS.route) {
        composable(CollectionsRoutes.HOME) {
            CollectionsScreen(
                onOpenSet = { navController.navigate(CollectionsRoutes.set(it)) },
                onOpenCollection = { navController.navigate(CollectionsRoutes.collection(it)) },
            )
        }
        composable(CollectionsRoutes.SET) {
            SetDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenCard = { id, language -> navController.navigate(CollectionsRoutes.card(id, language)) },
            )
        }
        composable(CollectionsRoutes.CARD) {
            CardDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(CollectionsRoutes.COLLECTION) {
            CollectionDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenCard = { id, language -> navController.navigate(CollectionsRoutes.card(id, language)) },
            )
        }
    }
}
