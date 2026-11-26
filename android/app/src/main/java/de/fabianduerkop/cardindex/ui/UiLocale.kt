// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui

import android.content.Context
import de.fabianduerkop.cardindex.R
import java.util.Locale

/**
 * The language the app's texts are in, from the `ui_locale` string of the resources Android picked (#250). Language
 * and market names are formatted in it, so an English screen on a German phone says "German", not "Deutsch". While
 * the app has English strings only, this is English on every phone; a translation sets its own `ui_locale`.
 */
object UiLocale {
    @Volatile
    var current: Locale = Locale.ENGLISH
        private set

    fun init(context: Context) {
        current = Locale.forLanguageTag(context.getString(R.string.ui_locale))
    }
}
