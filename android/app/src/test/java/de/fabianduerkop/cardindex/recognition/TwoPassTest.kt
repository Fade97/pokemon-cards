// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [TwoPass] against ml/detect.py (`guide_crop`, `refine`, `keep_higher_sim`), issue #78. */
class TwoPassTest {
    private fun quad(vararg xy: Double): Quad = List(4) { Pt(xy[2 * it], xy[2 * it + 1]) }

    /** (left, top, right, bottom), the form detect.guide_crop returns. */
    private fun PixelRect.ltrb() = listOf(x, y, x + width, y + height)

    private val tilted = quad(612.5, 301.25, 1180.0, 340.75, 1141.5, 1150.0, 571.0, 1108.5)

    @Test
    fun guideBoxMatchesPython() {
        // detect.guide_crop(np.zeros((2048, 1536, 3)), quad) gives (478, 170, 1273, 1280); unclipped it is
        // 478.88, 170.07, 1273.62, 1280.18.
        assertEquals(listOf(478, 170, 1273, 1280), TwoPass.guideBox(tilted, 1536, 2048).ltrb())
    }

    @Test
    fun guideBoxIsClippedToTheImage() {
        // Python: (0, 1401, 470, 2048); unclipped -50.25, 1401.47, 470.25, 2128.53.
        val nearCorner = quad(20.0, 1500.0, 400.0, 1500.0, 400.0, 2030.0, 20.0, 2030.0)
        assertEquals(listOf(0, 1401, 470, 2048), TwoPass.guideBox(nearCorner, 1536, 2048).ltrb())
    }

    @Test
    fun guideBoxArithmetic() {
        // An upright card 700 px high: box height 700 / 0.78 = 897.4, width 63:88 of that, 7 % margin around the centre.
        val c = Pt(1000.0, 1000.0)
        val w = 700.0 * 63 / 88
        val q = quad(c.x - w / 2, c.y - 350, c.x + w / 2, c.y - 350, c.x + w / 2, c.y + 350, c.x - w / 2, c.y + 350)
        val gh = 700.0 / 0.78
        val gw = gh * 63 / 88
        val expected = listOf(
            (c.x - gw / 2 * 1.07).toInt(), (c.y - gh / 2 * 1.07).toInt(),
            (c.x + gw / 2 * 1.07).toInt(), (c.y + gh / 2 * 1.07).toInt(),
        )
        assertEquals(listOf(656, 519, 1343, 1480), expected)
        assertEquals(expected, TwoPass.guideBox(q, 4000, 4000).ltrb())
    }

    @Test
    fun guideBoxDoesNotDependOnCornerOrder() {
        val box = TwoPass.guideBox(tilted, 1536, 2048)
        for (k in 1..3) assertEquals(box, TwoPass.guideBox(tilted.roll(k), 1536, 2048))
        // Counter-clockwise order too: the card height is the mean of the two longest sides either way.
        assertEquals(box, TwoPass.guideBox(tilted.reversed(), 1536, 2048))
    }

    @Test
    fun refineBoxSkipsBoxesUnder24Pixels() {
        // Card 14 px high: box 17.9 px high, 12.8 px wide, so 13 px wide with the margin.
        assertNull(TwoPass.refineBox(quad(100.0, 100.0, 110.0, 100.0, 110.0, 114.0, 100.0, 114.0), 640, 480))
        // A card 22 px high: box 21 px wide (97..118), still skipped.
        assertNull(TwoPass.refineBox(quad(100.0, 100.0, 116.0, 100.0, 116.0, 122.0, 100.0, 122.0), 640, 480))
        // A card 300 px high, mostly left of the image: the box is clipped to 12 px (0..12), skipped as well.
        val offLeft = quad(-245.0, 100.0, -25.0, 100.0, -25.0, 400.0, -245.0, 400.0)
        assertEquals(12, TwoPass.guideBox(offLeft, 640, 480).width)
        assertNull(TwoPass.refineBox(offLeft, 640, 480))
        assertEquals(TwoPass.guideBox(tilted, 1536, 2048), TwoPass.refineBox(tilted, 1536, 2048))
    }

    @Test
    fun keepHigherSimKeepsTheSecondOnlyWhenStrictlyHigher() {
        assertEquals("second" to true, TwoPass.keepHigherSim(0.300f to "first", 0.307f to "second"))
        assertEquals("first" to false, TwoPass.keepHigherSim(0.8f to "first", 0.7f to "second"))
        assertEquals("first" to false, TwoPass.keepHigherSim(0.5f to "first", 0.5f to "second")) // tie
        assertEquals("first" to false, TwoPass.keepHigherSim(0.5f to "first", null)) // no second pass
        assertEquals("first" to false, TwoPass.keepHigherSim(0.5f to "first", Float.NaN to "second"))
    }
}
