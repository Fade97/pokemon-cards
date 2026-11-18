// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.settings

import java.net.URLEncoder

/**
 * The tester feedback form (#88): a GitHub issue form, `.github/ISSUE_TEMPLATE/tester-feedback.yml`.
 * GitHub fills form fields from query parameters named like the field ids, so the tester doesn't have to look up
 * the app version or phone model. They see and can change every value before they submit.
 */
object TesterFeedback {
    const val NEW_ISSUE_URL = "https://github.com/Fade97/pokemon-cards/issues/new"
    const val TEMPLATE = "tester-feedback.yml"

    fun url(appVersion: String, device: String, android: String): String {
        val params = listOf(
            "template" to TEMPLATE,
            "app-version" to appVersion,
            "device" to device,
            "android" to android,
        )
        return NEW_ISSUE_URL + "?" + params.joinToString("&") { (key, value) -> "$key=${encode(value.trim())}" }
    }

    /** `Build.MANUFACTURER` + `Build.MODEL`, without repeating the maker when the model already starts with it. */
    fun device(manufacturer: String, model: String): String =
        if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()

    // URLEncoder writes form encoding: a space becomes "+", which the query of a GitHub URL would keep literally.
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
