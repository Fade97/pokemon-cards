// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.pro

import de.fabianduerkop.cardindex.data.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Whether the user has Pro (ADR 0007): price history, portfolio charts, later alerts and sync. The backend enforces it
 * on every Pro request; the app only uses this to decide what to show and which token to send.
 *
 * Sign-in and Play Billing (#16) don't exist yet. Release builds are never Pro; debug builds have a switch in
 * Settings ([DebugEntitlements]). #16 adds the real implementation behind this interface.
 */
interface Entitlements {
    val isPro: Flow<Boolean>

    /** The bearer token for Pro requests, or null when there is none (the backend then answers 401). */
    fun token(): String?
}

/** Until #16: nobody has Pro. */
object NoEntitlements : Entitlements {
    override val isPro: Flow<Boolean> = flowOf(false)
    override fun token(): String? = null
}

/**
 * Debug builds only: Pro follows the "Pro (debug)" switch in Settings, and requests carry the tester token from the
 * Gradle property `cardindex.proTestToken` (backend: its SHA-256 in `PRO_TEST_TOKENS`). Without a token the switch
 * still shows the Pro screens, but the backend refuses the history requests.
 */
class DebugEntitlements(private val settings: AppSettings, private val testToken: String?) : Entitlements {
    override val isPro: Flow<Boolean> = settings.debugProFlow
    override fun token(): String? = testToken?.takeIf { it.isNotBlank() }
}
