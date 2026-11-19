// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The Sets / Binders / Wishlist switch on the Collections home (#164) remembers the last view. */
@RunWith(AndroidJUnit4::class)
class CollectionsViewTest {
    @Test
    fun switchOrderIsSetsBindersWishlist() {
        assertEquals(
            listOf(CollectionsView.SETS, CollectionsView.BINDERS, CollectionsView.WISHLIST),
            CollectionsView.entries.toList(),
        )
    }

    @Test
    fun aStoredNameRestoresItsView() {
        CollectionsView.entries.forEach { assertEquals(it, CollectionsView.fromStored(it.name)) }
    }

    @Test
    fun nothingOrAnUnknownNameMeansSets() {
        assertEquals(CollectionsView.SETS, CollectionsView.fromStored(null))
        assertEquals(CollectionsView.SETS, CollectionsView.fromStored(""))
        // A view a later version dropped, or a different case.
        assertEquals(CollectionsView.SETS, CollectionsView.fromStored("TRADES"))
        assertEquals(CollectionsView.SETS, CollectionsView.fromStored("binders"))
    }

    @Test
    fun theChosenViewSurvivesANewSettingsInstance() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = AppSettings(context)
        assertNull(settings.collectionsView)

        settings.collectionsView = CollectionsView.WISHLIST.name
        // A new instance reads the same SharedPreferences, as after an app restart.
        assertEquals(CollectionsView.WISHLIST, CollectionsView.fromStored(AppSettings(context).collectionsView))

        settings.collectionsView = null
        assertNull(AppSettings(context).collectionsView)
    }
}
