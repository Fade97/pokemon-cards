// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant

/**
 * The user's language choice, in SharedPreferences so it survives in the Android backup: after a restore the packs
 * themselves (not backed up) are downloaded again for the languages chosen here.
 */
class PackPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("packs", Context.MODE_PRIVATE)

    /** Languages the user wants installed (TCGdex codes). */
    var wanted: Set<String>
        get() = prefs.getStringSet(KEY_WANTED, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(KEY_WANTED, value.toSet()) }

    /** The first-run language picker was answered (with a choice or "not now"). */
    var pickerDone: Boolean
        get() = prefs.getBoolean(KEY_PICKER_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_PICKER_DONE, value) }

    var lastPolledAt: Instant?
        get() = prefs.getLong(KEY_LAST_POLL, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        set(value) = prefs.edit { putLong(KEY_LAST_POLL, value?.toEpochMilli() ?: 0L) }

    private companion object {
        const val KEY_WANTED = "wanted"
        const val KEY_PICKER_DONE = "picker_done"
        const val KEY_LAST_POLL = "last_poll"
    }
}
