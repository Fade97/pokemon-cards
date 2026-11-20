// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import de.fabianduerkop.cardindex.model.Finish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/** The tilt light's math (#167, ADR 0015): which finish gets which light, and how the tilt moves it. */
class FoilLightTest {
    private val card = Size(600f, 838f)

    private fun assertTilt(expected: Tilt, actual: Tilt) {
        assertEquals("x", expected.x, actual.x, 0.0001f)
        assertEquals("y", expected.y, actual.y, 0.0001f)
    }

    @Test
    fun `holo wins over reverse holo, normal and other get no light`() {
        assertEquals(FoilFinish.HOLO, FoilFinish.of(listOf(Finish.NORMAL, Finish.REVERSE_HOLO, Finish.HOLO)))
        assertEquals(FoilFinish.REVERSE_HOLO, FoilFinish.of(listOf(Finish.NORMAL, Finish.REVERSE_HOLO)))
        assertNull(FoilFinish.of(listOf(Finish.NORMAL, Finish.OTHER)))
        assertNull(FoilFinish.of(emptyList()))
    }

    @Test
    fun `turning the phone tilts the light by the angle turned`() {
        // A quarter of full tilt: 0.125 rad over 0.05 s, around the device's y axis (right edge away).
        val turned = FoilLight.step(Tilt.Rest, rateX = 0f, rateY = 2.5f, quarterTurns = 0, dtSeconds = 0.05f)
        assertTilt(Tilt(0.25f, 0f), turned)

        // Around the x axis (top edge towards the viewer) moves it down.
        val pitched = FoilLight.step(Tilt.Rest, rateX = 2.5f, rateY = 0f, quarterTurns = 0, dtSeconds = 0.05f)
        assertTilt(Tilt(0f, 0.25f), pitched)
    }

    @Test
    fun `a held tilt eases back to rest`() {
        val held = FoilLight.step(Tilt(0.8f, -0.4f), rateX = 0f, rateY = 0f, quarterTurns = 0, dtSeconds = 0.1f)
        val decay = exp(-0.1f / FoilLight.SETTLE_SECONDS)
        assertTilt(Tilt(0.8f * decay, -0.4f * decay), held)

        // Six seconds of holding still: the light is back at rest.
        var tilt = Tilt(1f, 1f)
        repeat(600) { tilt = FoilLight.step(tilt, 0f, 0f, 0, 0.01f) }
        assertTrue(tilt.amount < 0.02f)
    }

    @Test
    fun `tilt stops at full on each axis`() {
        val far = FoilLight.step(Tilt(0.9f, -0.9f), rateX = -50f, rateY = 50f, quarterTurns = 0, dtSeconds = 0.1f)
        assertTilt(Tilt(1f, -1f), far)
    }

    @Test
    fun `a gap between samples never makes the light jump`() {
        // Back from the background: 3 s since the last sample count as MAX_STEP_SECONDS.
        val resumed = FoilLight.step(Tilt.Rest, rateX = 0f, rateY = 1f, quarterTurns = 0, dtSeconds = 3f)
        assertTilt(Tilt(FoilLight.MAX_STEP_SECONDS / FoilLight.MAX_TILT_RADIANS, 0f), resumed)

        // A clock going backwards does nothing.
        assertTilt(Tilt(0.5f, 0.5f), FoilLight.step(Tilt(0.5f, 0.5f), 3f, 3f, 0, -0.02f))
    }

    @Test
    fun `a turned screen maps the device axes to the screen`() {
        fun turned(rateX: Float, rateY: Float, quarterTurns: Int) =
            FoilLight.step(Tilt.Rest, rateX, rateY, quarterTurns, dtSeconds = 0.05f)
        // 2.5 rad/s for 0.05 s is a quarter of full tilt.
        // Landscape, top to the left (ROTATION_90): screen right is the device's -y, screen up its +x.
        assertTilt(Tilt(0.25f, 0f), turned(2.5f, 0f, 1))
        assertTilt(Tilt(0f, -0.25f), turned(0f, 2.5f, 1))
        // Upside down: both axes flip.
        assertTilt(Tilt(-0.25f, 0f), turned(0f, 2.5f, 2))
        assertTilt(Tilt(0f, -0.25f), turned(2.5f, 0f, 2))
        // Landscape, top to the right (ROTATION_270): screen right is the device's +y, screen up its -x.
        assertTilt(Tilt(-0.25f, 0f), turned(2.5f, 0f, 3))
        assertTilt(Tilt(0f, 0.25f), turned(0f, 2.5f, 3))
    }

    @Test
    fun `the touch fallback tilts towards the finger`() {
        assertTilt(Tilt.Rest, FoilLight.fromTouch(Offset(300f, 419f), card))
        assertTilt(Tilt(1f, -1f), FoilLight.fromTouch(Offset(600f, 0f), card))
        assertTilt(Tilt(-0.5f, 0.5f), FoilLight.fromTouch(Offset(150f, 628.5f), card))
        // Dragged past the card's edge: still full tilt, no further.
        assertTilt(Tilt(-1f, 1f), FoilLight.fromTouch(Offset(-200f, 2000f), card))
        assertTilt(Tilt.Rest, FoilLight.fromTouch(Offset(10f, 10f), Size.Zero))
    }

    @Test
    fun `at rest the band crosses the middle of the card`() {
        val band = FoilLight.band(Tilt.Rest, card, FoilFinish.HOLO)
        val middle = (band.start + band.end) / 2f
        assertEquals(300f, middle.x, 0.01f)
        assertEquals(419f, middle.y, 0.01f)
        // The holo band is 55 % of the diagonal wide.
        assertEquals(0.55f * 600f, band.end.x - band.start.x, 0.01f)
    }

    @Test
    fun `the reverse holo band is narrower`() {
        val holo = FoilLight.band(Tilt.Rest, card, FoilFinish.HOLO)
        val reverse = FoilLight.band(Tilt.Rest, card, FoilFinish.REVERSE_HOLO)
        assertTrue(reverse.end.x - reverse.start.x < holo.end.x - holo.start.x)
    }

    @Test
    fun `full tilt sweeps the band off the card and the glare the other way`() {
        val right = FoilLight.band(Tilt(1f, 0f), card, FoilFinish.HOLO)
        val left = FoilLight.band(Tilt(-1f, 0f), card, FoilFinish.HOLO)
        // The band's centre is past the far corner at full tilt one way and before the near corner the other way.
        assertTrue((right.start.x + right.end.x) / 2f > 600f)
        assertTrue((left.start.x + left.end.x) / 2f < 0f)

        val glare = FoilLight.glareCentre(Tilt(1f, 0f), card)
        assertTrue(glare.x < 300f)
        assertEquals(419f, glare.y, 0.01f)
        val rest = FoilLight.glareCentre(Tilt.Rest, card)
        assertEquals(Offset(300f, 419f), rest)
    }

    @Test
    fun `the light is faint at rest and strongest at full tilt`() {
        assertEquals(FoilFinish.HOLO.restAlpha, FoilLight.alpha(Tilt.Rest, FoilFinish.HOLO), 0.0001f)
        assertEquals(FoilFinish.HOLO.peakAlpha, FoilLight.alpha(Tilt(1f, 0f), FoilFinish.HOLO), 0.0001f)
        assertEquals(FoilFinish.HOLO.peakAlpha, FoilLight.alpha(Tilt(-1f, 1f), FoilFinish.HOLO), 0.0001f)
        val half = FoilLight.alpha(Tilt(0f, 0.5f), FoilFinish.REVERSE_HOLO)
        assertEquals((FoilFinish.REVERSE_HOLO.restAlpha + FoilFinish.REVERSE_HOLO.peakAlpha) / 2f, half, 0.0001f)
        assertTrue(FoilLight.alpha(Tilt(1f, 0f), FoilFinish.REVERSE_HOLO) < FoilLight.alpha(Tilt(1f, 0f), FoilFinish.HOLO))
    }
}
