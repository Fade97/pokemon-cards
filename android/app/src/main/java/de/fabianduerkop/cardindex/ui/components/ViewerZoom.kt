// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The geometry of the card viewer (#166), kept free of Compose state so it can be tested on the JVM. Offsets are
 * translations of the image's centre from the container's centre, in px; scale 1 is the image fitted to the screen.
 */
internal object ViewerZoom {
    const val MIN_SCALE = 1f

    /** About 3× the 600 px CDN image on a phone, enough to read the attack text and the set symbol. */
    const val MAX_SCALE = 5f

    /** Where a double tap zooms to from the fitted image. */
    const val DOUBLE_TAP_SCALE = 2.5f

    /** Above this the image counts as zoomed: pan instead of swipe-to-close and paging. */
    private const val ZOOMED_EPSILON = 0.01f

    fun isZoomed(scale: Float): Boolean = scale > MIN_SCALE + ZOOMED_EPSILON

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /** A double tap zooms in from the fitted image and back out from any zoom. */
    fun doubleTapTarget(scale: Float): Float = if (isZoomed(scale)) MIN_SCALE else DOUBLE_TAP_SCALE

    /** The size of an image of [intrinsic] size fitted inside [container] (ContentScale.Fit), at scale 1. */
    fun fit(intrinsic: Size, container: Size): Size {
        // Unspecified (NaN) or infinite while loading, or for painters without a size: fill the container.
        val usable = intrinsic.width.isFinite() && intrinsic.height.isFinite() && intrinsic.width > 0f && intrinsic.height > 0f
        if (!usable) return container
        val ratio = min(container.width / intrinsic.width, container.height / intrinsic.height)
        return Size(intrinsic.width * ratio, intrinsic.height * ratio)
    }

    /**
     * Keeps the zoomed image over the screen: it can move only as far as it overhangs the container on each axis, so
     * no empty gap opens at an edge. An image smaller than the container on an axis stays centred on it.
     */
    fun clampOffset(offset: Offset, scale: Float, content: Size, container: Size): Offset {
        val maxX = max(0f, (content.width * scale - container.width) / 2f)
        val maxY = max(0f, (content.height * scale - container.height) / 2f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    /**
     * The offset after zooming from [scale] to [newScale] around [focus] (relative to the container's centre), so the
     * point under the fingers or the double tap stays where it is.
     */
    fun zoomAround(offset: Offset, scale: Float, newScale: Float, focus: Offset): Offset =
        focus - (focus - offset) * (newScale / scale)

    /**
     * Whether a released swipe-down closes the viewer: dragged further than [DISMISS_FRACTION] of the screen height,
     * or flung downwards faster than [flingVelocity] (px/s) after moving at all. An upward fling never closes it.
     */
    fun shouldDismiss(dragY: Float, velocityY: Float, height: Float, flingVelocity: Float): Boolean {
        if (dragY <= 0f) return false
        return dragY > height * DISMISS_FRACTION || velocityY > flingVelocity
    }

    /** The backdrop fades as the card is dragged down, so it is clear that letting go closes the viewer. */
    fun backdropAlpha(dragY: Float, height: Float): Float {
        if (height <= 0f) return 1f
        val progress = (abs(dragY) / (height * DISMISS_FRACTION * 2f)).coerceIn(0f, 1f)
        return 1f - progress * (1f - MIN_BACKDROP_ALPHA)
    }

    const val DISMISS_FRACTION = 0.15f
    private const val MIN_BACKDROP_ALPHA = 0.35f
}
