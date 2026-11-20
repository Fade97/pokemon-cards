// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import de.fabianduerkop.cardindex.model.Finish
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Which simulated light the card viewer draws over a card (ADR 0015, #167). It follows the finish the user recorded on
 * their own copy, never anything the app detected, and it is the same generic light for every card: it decorates, it
 * does not describe where a card's foil sits.
 *
 * @property bandWidth width of the light band as a fraction of the card's diagonal.
 * @property restAlpha strength of the light with the phone at rest.
 * @property peakAlpha strength at full tilt.
 */
enum class FoilFinish(internal val bandWidth: Float, internal val restAlpha: Float, internal val peakAlpha: Float) {
    /** A broad, coloured band: holo cards catch the light in a wide sweep. */
    HOLO(bandWidth = 0.55f, restAlpha = 0.3f, peakAlpha = 0.75f),

    /** A narrower, paler band. */
    REVERSE_HOLO(bandWidth = 0.32f, restAlpha = 0.18f, peakAlpha = 0.5f),
    ;

    companion object {
        /** The light for a print the user owns in [finishes]: Holo wins over Reverse Holo; Normal and Other get none. */
        fun of(finishes: Iterable<Finish>): FoilFinish? {
            val owned = finishes.toSet()
            return when {
                Finish.HOLO in owned -> HOLO
                Finish.REVERSE_HOLO in owned -> REVERSE_HOLO
                else -> null
            }
        }
    }
}

/**
 * How far the phone (or the finger, without a gyroscope) is tilted, per screen axis, from -1 to 1; 0 is at rest.
 * [x] > 0: the right edge tilted away from the viewer. [y] > 0: the top edge tilted towards them. On screen both move
 * the light right and down.
 */
@Immutable
data class Tilt(val x: Float, val y: Float) {
    /** 0 at rest, 1 at full tilt on either axis or beyond. */
    val amount: Float get() = min(1f, hypot(x, y))

    companion object {
        val Rest = Tilt(0f, 0f)
    }
}

/** The two points a linear gradient runs between, in px of the card. */
@Immutable
internal data class GradientLine(val start: Offset, val end: Offset)

/**
 * The tilt light's math (ADR 0015), free of Compose state and Android sensors so it can be tested on the JVM.
 *
 * The tilt is the gyroscope's rotation rate integrated over time and eased back to rest: it follows movement and
 * settles wherever the phone is held, so there is no baseline to reset when the grip changes, and gyro drift fades out
 * instead of adding up.
 */
internal object FoilLight {
    /** A turn of this many radians (about 29°) is full tilt. */
    const val MAX_TILT_RADIANS = 0.5f

    /** Time constant of the ease back to rest: after this long a held tilt has faded to ~37 %. */
    const val SETTLE_SECONDS = 1.2f

    /** A longer gap between gyroscope samples (the app was paused) counts as this long, so the light never jumps. */
    const val MAX_STEP_SECONDS = 0.1f

    /** How far one unit of tilt moves the band along the card's diagonal; full tilt on one axis sweeps it off the card. */
    private const val BAND_SWEEP = 0.6f

    /** How far one unit of tilt moves the glare, against the band: the two layers move apart (the slight parallax). */
    private const val GLARE_SWEEP = -0.35f

    /** Radius of the glare as a fraction of the card's width. */
    const val GLARE_RADIUS = 0.6f

    /**
     * Advances [tilt] by one gyroscope sample: [rateX] and [rateY] are the rotation rates (rad/s) around the
     * device's natural x and y axes, as [android.hardware.Sensor.TYPE_GYROSCOPE] reports them, and [quarterTurns] is
     * the display rotation (Surface.ROTATION_0 to ROTATION_270 are 0 to 3).
     */
    fun step(tilt: Tilt, rateX: Float, rateY: Float, quarterTurns: Int, dtSeconds: Float): Tilt {
        val dt = dtSeconds.coerceIn(0f, MAX_STEP_SECONDS)
        // Rotation rates around the screen's right and up axes. Turned 90° (top to the left), screen right is the
        // device's -y and screen up its +x; the other turns follow.
        val (aboutRight, aboutUp) = when (Math.floorMod(quarterTurns, 4)) {
            1 -> -rateY to rateX
            2 -> -rateX to -rateY
            3 -> rateY to -rateX
            else -> rateX to rateY
        }
        val decay = exp(-dt / SETTLE_SECONDS)
        // Around the up axis (positive turns the right edge away) moves the light sideways; around the right axis
        // (positive turns the top edge towards the viewer) moves it up and down.
        return Tilt(
            x = (tilt.x * decay + aboutUp * dt / MAX_TILT_RADIANS).coerceIn(-1f, 1f),
            y = (tilt.y * decay + aboutRight * dt / MAX_TILT_RADIANS).coerceIn(-1f, 1f),
        )
    }

    /** The touch fallback: a finger at [position] on a card of [size] tilts it towards that point; the centre is rest. */
    fun fromTouch(position: Offset, size: Size): Tilt {
        if (size.width <= 0f || size.height <= 0f) return Tilt.Rest
        return Tilt(
            x = (position.x / size.width * 2f - 1f).coerceIn(-1f, 1f),
            y = (position.y / size.height * 2f - 1f).coerceIn(-1f, 1f),
        )
    }

    /**
     * Where the light band lies on a card of [size]: a gradient across the diagonal from top left to bottom right,
     * [FoilFinish.bandWidth] wide, centred mid-card at rest and pushed along the diagonal by the tilt.
     */
    fun band(tilt: Tilt, size: Size, finish: FoilFinish): GradientLine {
        val centre = 0.5f + BAND_SWEEP * (tilt.x + tilt.y)
        val half = finish.bandWidth / 2f
        val diagonal = Offset(size.width, size.height)
        return GradientLine(start = diagonal * (centre - half), end = diagonal * (centre + half))
    }

    /** The centre of the glare on a card of [size]: mid-card at rest, moving against the band. */
    fun glareCentre(tilt: Tilt, size: Size): Offset =
        Offset(size.width * (0.5f + GLARE_SWEEP * tilt.x), size.height * (0.5f + GLARE_SWEEP * tilt.y))

    /** How strong the light is: faint at rest, strongest at full tilt, like foil catching the light. */
    fun alpha(tilt: Tilt, finish: FoilFinish): Float =
        finish.restAlpha + (finish.peakAlpha - finish.restAlpha) * tilt.amount
}
