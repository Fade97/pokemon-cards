// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.settings

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The contribution consent lives outside the backed-up "settings" file (#253), so a restore never turns it on. */
@RunWith(AndroidJUnit4::class)
class ContributionConsentSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun consentIsKeptInItsOwnFile() {
        val settings = AppSettings(context)
        settings.acceptContributionConsent("train-2")

        assertEquals("train-2", settings.contributionConsent)
        assertEquals("train-2", context.getSharedPreferences("contribution_consent", Context.MODE_PRIVATE).getString("contribution_consent", null))
        assertFalse(context.getSharedPreferences("settings", Context.MODE_PRIVATE).contains("contribution_consent"))

        settings.stopContributing()
        assertNull(settings.contributionConsent)
    }

    /** A consent in the old place (before #253, or brought back by a restore) is dropped, not honoured. */
    @Test
    fun consentInTheBackedUpSettingsIsDropped() {
        val old = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        old.edit(commit = true) { putString("contribution_consent", "train-1") }

        val settings = AppSettings(context)

        assertNull(settings.contributionConsent)
        assertFalse(old.contains("contribution_consent"))
        assertTrue(context.getSharedPreferences("contribution_consent", Context.MODE_PRIVATE).all.isEmpty())
    }
}
