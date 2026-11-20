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

    /**
     * Language packs may download over mobile data (#137). Off by default: all packs together are about 110 MB, so
     * they wait for an unmetered network (PackSyncWorker). Change it with AppContainer.setPacksOnMobileData, which
     * also moves waiting downloads to the new rule.
     */
    var packsOnMobileData: Boolean
        get() = prefs.getBoolean(KEY_PACKS_ON_MOBILE_DATA, false)
        set(value) = prefs.edit { putBoolean(KEY_PACKS_ON_MOBILE_DATA, value) }

    val packsOnMobileDataFlow: Flow<Boolean> = observe { packsOnMobileData }

    /**
     * Uuid of the custom collection scans are added to besides All Cards (#139), or null for All Cards only. Kept
     * across app starts: sorting a pile into one binder often takes more than one sitting, and the Scan screen always
     * shows the choice.
     */
    var scanTargetCollection: String?
        get() = prefs.getString(KEY_SCAN_TARGET, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_SCAN_TARGET) else putString(KEY_SCAN_TARGET, value) }

    val scanTargetCollectionFlow: Flow<String?> = observe { scanTargetCollection }

    /** Back to All Cards only, if the target still is [uuid] (a deleted collection); a newer choice stays. */
    fun clearScanTarget(uuid: String) {
        if (scanTargetCollection == uuid) scanTargetCollection = null
    }

    /**
     * Set id the user pinned to the Collections foil cover (#156), or null for the default: the most valuable set.
     * Kept when the set leaves the list, so it returns to the cover once the user owns its cards again.
     */
    var featuredSet: String?
        get() = prefs.getString(KEY_FEATURED_SET, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_FEATURED_SET) else putString(KEY_FEATURED_SET, value) }

    val featuredSetFlow: Flow<String?> = observe { featuredSet }

    /**
     * The user has scanned a card before (#162): the Scan tray's long explanation is a first-run hint, later sessions
     * start with a short line.
     */
    var scanIntroSeen: Boolean
        get() = prefs.getBoolean(KEY_SCAN_INTRO_SEEN, false)
        set(value) = prefs.edit { putBoolean(KEY_SCAN_INTRO_SEEN, value) }

    val scanIntroSeenFlow: Flow<Boolean> = observe { scanIntroSeen }

    /**
     * The first-run goal (#174) is behind the user: reached on the Scan screen, or the collection already had 10
     * cards when the screen first looked. The Scan screen then stops counting.
     */
    var firstGoalDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST_GOAL_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_FIRST_GOAL_DONE, value) }

    /**
     * How many times the set detail screen showed its slot legend on its own (#165). After SetLegend.FIRST_VISITS the
     * legend waits behind the info button.
     */
    var setLegendVisits: Int
        get() = prefs.getInt(KEY_SET_LEGEND_VISITS, 0)
        set(value) = prefs.edit { putInt(KEY_SET_LEGEND_VISITS, value) }

    /**
     * Name of the view the Collections home showed last (#164): sets, binders or the wishlist; null before the first
     * switch. The screen reads it with CollectionsView.fromStored, which turns an unknown name into sets.
     */
    var collectionsView: String?
        get() = prefs.getString(KEY_COLLECTIONS_VIEW, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_COLLECTIONS_VIEW) else putString(KEY_COLLECTIONS_VIEW, value) }

    /**
     * Set milestones already celebrated (#169), as "setId|language|level". A milestone fires once per set and language:
     * Undo takes the progress back, but redoing the card does not celebrate again.
     */
    var celebratedMilestones: Set<String>
        get() = prefs.getStringSet(KEY_CELEBRATED_MILESTONES, null)?.toSet().orEmpty()
        set(value) = prefs.edit { putStringSet(KEY_CELEBRATED_MILESTONES, value) }

    /** Milestones reached before #169 shipped were marked celebrated once, so an update celebrates nothing old. */
    var milestonesSeeded: Boolean
        get() = prefs.getBoolean(KEY_MILESTONES_SEEDED, false)
        set(value) = prefs.edit { putBoolean(KEY_MILESTONES_SEEDED, value) }

    /** When the set detail screen of [setId] was last opened (#169), to pulse the slots filled since; null never. */
    fun setVisitedAt(setId: String): Long? = prefs.getLong(KEY_SET_VISIT_PREFIX + setId, -1L).takeIf { it >= 0 }

    fun markSetVisited(setId: String, atMillis: Long) = prefs.edit { putLong(KEY_SET_VISIT_PREFIX + setId, atMillis) }

    /** Recaps (#175) opened at least once, by [RecapPeriod.key] ("2026-11", "2026"); the others show as new. */
    var openedRecaps: Set<String>
        get() = prefs.getStringSet(KEY_OPENED_RECAPS, null)?.toSet().orEmpty()
        set(value) = prefs.edit { putStringSet(KEY_OPENED_RECAPS, value) }

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
        const val KEY_PACKS_ON_MOBILE_DATA = "packs_on_mobile_data"
        const val KEY_SCAN_TARGET = "scan_target_collection"
        const val KEY_FEATURED_SET = "featured_set"
        const val KEY_SCAN_INTRO_SEEN = "scan_intro_seen"
        const val KEY_FIRST_GOAL_DONE = "first_goal_done"
        const val KEY_CELEBRATED_MILESTONES = "celebrated_milestones"
        const val KEY_MILESTONES_SEEDED = "milestones_seeded"
        const val KEY_SET_VISIT_PREFIX = "set_visit_"
        const val KEY_OPENED_RECAPS = "opened_recaps"
        const val KEY_SET_LEGEND_VISITS = "set_legend_visits"
        const val KEY_COLLECTIONS_VIEW = "collections_view"
    }
}
