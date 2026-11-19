// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user turned animations off (#166): Accessibility → "Remove animations", or the animator duration scale
 * at 0 in the developer options. Both set [Settings.Global.ANIMATOR_DURATION_SCALE] to 0. Motion that only explains a
 * change (a card springing back, sliding away) then snaps instead; motion under the finger still follows the finger.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        isReducedMotion(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }
}

/** An animator duration scale of 0 means no animations. */
internal fun isReducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f
