// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.fabianduerkop.cardindex.R

/** The four tabs of the bottom navigation bar, in mockup order. Icons are the mockup outline icons. */
enum class TopLevelDestination(val route: String, @param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    SCAN("scan", R.string.nav_scan, R.drawable.ic_nav_scan),
    COLLECTIONS("collections", R.string.nav_collections, R.drawable.ic_nav_collections),
    SEARCH("search", R.string.nav_search, R.drawable.ic_nav_search),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_nav_settings),
}
