// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card viewer's zoom, pan and swipe-to-close geometry (#166). */
class ViewerZoomTest {
    private val phone = Size(1080f, 2340f)

    // A card fitted to the width of the phone.
    private val card = Size(1080f, 1080f / CARD_ASPECT)

    // Offset packs both floats into one value, so 0f and -0f would differ; compare the numbers instead.
    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
    }

    @Test
    fun `a card fits the width of a portrait phone and the height of a landscape one`() {
        val portrait = ViewerZoom.fit(Size(600f, 825f), phone)
        assertEquals(1080f, portrait.width, 0.01f)
        assertEquals(1485f, portrait.height, 0.01f)

        val landscape = ViewerZoom.fit(Size(600f, 825f), Size(2340f, 1080f))
        assertEquals(1080f, landscape.height, 0.01f)
        assertEquals(785.45f, landscape.width, 0.01f)
    }

    @Test
    fun `an image without a known size fills the container`() {
        assertEquals(phone, ViewerZoom.fit(Size.Unspecified, phone))
        assertEquals(phone, ViewerZoom.fit(Size(0f, 0f), phone))
    }

    @Test
    fun `scale stays between the fitted image and five times it`() {
        assertEquals(1f, ViewerZoom.clampScale(0.4f))
        assertEquals(3f, ViewerZoom.clampScale(3f))
        assertEquals(5f, ViewerZoom.clampScale(12f))
    }

    @Test
    fun `a double tap zooms in from the fitted image and back out from any zoom`() {
        assertEquals(ViewerZoom.DOUBLE_TAP_SCALE, ViewerZoom.doubleTapTarget(1f))
        assertEquals(1f, ViewerZoom.doubleTapTarget(1.6f))
        assertEquals(1f, ViewerZoom.doubleTapTarget(5f))
        // Rounding after a pinch back to the fitted size does not count as zoomed.
        assertEquals(ViewerZoom.DOUBLE_TAP_SCALE, ViewerZoom.doubleTapTarget(1.004f))
    }

    @Test
    fun `the fitted image cannot be panned`() {
        assertOffset(Offset.Zero, ViewerZoom.clampOffset(Offset(300f, -200f), 1f, card, phone))
    }

    @Test
    fun `a zoomed image pans only as far as it overhangs the screen`() {
        // At 2× the card is 2160 × 3017 px: 540 px over each side, 338 px over the top and bottom.
        val clamped = ViewerZoom.clampOffset(Offset(5000f, -5000f), 2f, card, phone)
        assertEquals(540f, clamped.x, 0.01f)
        assertEquals(-(card.height * 2f - phone.height) / 2f, clamped.y, 0.01f)

        val inside = ViewerZoom.clampOffset(Offset(100f, 50f), 2f, card, phone)
        assertOffset(Offset(100f, 50f), inside)
    }

    @Test
    fun `an axis the zoomed image does not fill stays centred`() {
        // At 1.2× the card is 1296 px wide (overhangs) but 1782 px high (shorter than the screen).
        val clamped = ViewerZoom.clampOffset(Offset(500f, 400f), 1.2f, card, phone)
        assertEquals(108f, clamped.x, 0.01f)
        assertEquals(0f, clamped.y, 0.01f)
    }

    @Test
    fun `zooming keeps the point under the fingers in place`() {
        val focus = Offset(200f, -300f)
        val offset = ViewerZoom.zoomAround(Offset.Zero, 1f, 2f, focus)
        // The content point under the focus before: (focus - offset) / scale. After: the same point maps to focus.
        val before = (focus - Offset.Zero) / 1f
        assertOffset(focus, before * 2f + offset)

        assertOffset(Offset.Zero, ViewerZoom.zoomAround(Offset.Zero, 1f, 3f, Offset.Zero))
        // Halving the zoom around the centre halves the pan.
        assertOffset(Offset(200f, 0f), ViewerZoom.zoomAround(Offset(400f, 0f), 2f, 1f, Offset.Zero))
    }

    @Test
    fun `a long or fast swipe down closes, a short slow one does not`() {
        val fling = 3000f
        assertTrue(ViewerZoom.shouldDismiss(dragY = 400f, velocityY = 0f, height = 2340f, flingVelocity = fling))
        assertTrue(ViewerZoom.shouldDismiss(dragY = 60f, velocityY = 4000f, height = 2340f, flingVelocity = fling))
        assertFalse(ViewerZoom.shouldDismiss(dragY = 200f, velocityY = 500f, height = 2340f, flingVelocity = fling))
    }

    @Test
    fun `an upward fling or no drag never closes`() {
        assertFalse(ViewerZoom.shouldDismiss(dragY = 0f, velocityY = 9000f, height = 2340f, flingVelocity = 3000f))
        assertFalse(ViewerZoom.shouldDismiss(dragY = 120f, velocityY = -9000f, height = 2340f, flingVelocity = 3000f))
    }

    @Test
    fun `the backdrop fades with the drag but never disappears`() {
        assertEquals(1f, ViewerZoom.backdropAlpha(0f, 2340f))
        val half = ViewerZoom.backdropAlpha(2340f * ViewerZoom.DISMISS_FRACTION, 2340f)
        assertTrue(half < 1f && half > 0.35f)
        assertEquals(0.35f, ViewerZoom.backdropAlpha(5000f, 2340f), 0.001f)
        assertEquals(1f, ViewerZoom.backdropAlpha(100f, 0f))
    }

    @Test
    fun `only an animator scale of zero counts as reduced motion`() {
        assertTrue(isReducedMotion(0f))
        assertFalse(isReducedMotion(0.5f))
        assertFalse(isReducedMotion(1f))
    }
}
