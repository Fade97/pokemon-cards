// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import de.fabianduerkop.cardindex.data.prices.PriceSourcePreference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** User settings in SharedPreferences; a handful of flags don't justify DataStore yet. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Show the user's own photo of a copy instead of the CDN image wherever both exist. */
    var preferOwnPhotos: Boolean
        get() = prefs.getBoolean(KEY_PREFER_OWN_PHOTOS, false)
        set(value) = prefs.edit().putBoolean(KEY_PREFER_OWN_PHOTOS, value).apply()

    val preferOwnPhotosFlow: Flow<Boolean> = observe { preferOwnPhotos }

    /** Main price market; AUTO follows the region (EU → Cardmarket, US → TCGplayer). */
    var priceSource: PriceSourcePreference
        get() = prefs.getString(KEY_PRICE_SOURCE, null)
            ?.let { name -> PriceSourcePreference.entries.firstOrNull { it.name == name } }
            ?: PriceSourcePreference.AUTO
        set(value) = prefs.edit().putString(KEY_PRICE_SOURCE, value.name).apply()

    val priceSourceFlow: Flow<PriceSourcePreference> = observe { priceSource }

    /**
     * The consent text version the user accepted for scan contributions (issue #14), or null when contributing is
     * off. Off by default; only the consent screen turns it on.
     */
    val contributionConsent: String?
        get() = prefs.getString(KEY_CONTRIBUTION_CONSENT, null)

    val contributionConsentFlow: Flow<String?> = observe { contributionConsent }

    fun acceptContributionConsent(version: String) = prefs.edit { putString(KEY_CONTRIBUTION_CONSENT, version) }

    fun stopContributing() = prefs.edit { remove(KEY_CONTRIBUTION_CONSENT) }

    /** Debug builds only: pretend to have Pro (#17) until sign-in and billing exist (#16). See DebugEntitlements. */
    var debugPro: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_PRO, false)
        set(value) = prefs.edit { putBoolean(KEY_DEBUG_PRO, value) }

    val debugProFlow: Flow<Boolean> = observe { debugPro }

    /** Emits [read] now and after every change; the listener is held strongly while collected. */
    private fun <T> observe(read: () -> T): Flow<T> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private companion object {
        const val KEY_PREFER_OWN_PHOTOS = "prefer_own_photos"
        const val KEY_PRICE_SOURCE = "price_source"
        const val KEY_CONTRIBUTION_CONSENT = "contribution_consent"
        const val KEY_DEBUG_PRO = "debug_pro"
    }
}
