// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user turned animations off (#166): Accessibility → "Remove animations", or the animator duration scale
 * at 0 in the developer options. Both set [Settings.Global.ANIMATOR_DURATION_SCALE] to 0. Motion that only explains a
 * change (a card springing back, sliding away) then snaps instead; motion under the finger still follows the finger.
 * The tilt light (#167) stays still and its sensor off.
 *
 * It follows the setting live (#167): turning animations off while a screen is open applies at once, not on the next
 * visit.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(readReducedMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readReducedMotion(resolver)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        // A change between the first read and the registration would otherwise be missed.
        reduced = readReducedMotion(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun readReducedMotion(resolver: ContentResolver): Boolean =
    isReducedMotion(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))

/** An animator duration scale of 0 means no animations. */
internal fun isReducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f
