// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideOpticsTest {
    // S25 Ultra, logical camera 0 at 1×: 6.3 mm, 9.792 × 7.344 mm sensor, closest focus 10 diopters (10 cm).
    private val focal = 6.3
    private val sensor = 9.792

    @Test
    fun atOneTimes_aFilledGuideIsCloserThanTheS25CanFocus() {
        val distance = GuideOptics.guideDistanceMm(focal, sensor, zoom = 1.0)
        assertEquals(72.6, distance, 0.5)
        assertTrue(distance < 100.0)
    }

    @Test
    fun theZoomMovesTheGuideBeyondTheClosestFocus_withMargin() {
        val zoom = GuideOptics.zoom(focal, sensor, minFocusDiopters = 10.0, maxZoom = 10.0)
        assertEquals(1.79, zoom, 0.01)
        assertEquals(130.0, GuideOptics.guideDistanceMm(focal, sensor, zoom), 0.5)
    }

    @Test
    fun aLensThatFocusesCloseEnough_staysAtOneTimes() {
        // Closest focus 5 cm (20 diopters): a filled guide at 7 cm is already sharp.
        assertEquals(1.0, GuideOptics.zoom(focal, sensor, minFocusDiopters = 20.0, maxZoom = 10.0), 0.0)
    }

    @Test
    fun fixedFocusOrUnknownOptics_stayAtOneTimes() {
        assertEquals(1.0, GuideOptics.zoom(focal, sensor, minFocusDiopters = 0.0, maxZoom = 10.0), 0.0)
        assertEquals(1.0, GuideOptics.zoom(0.0, sensor, minFocusDiopters = 10.0, maxZoom = 10.0), 0.0)
    }

    @Test
    fun theZoomStaysBelowTheTelephotoSwitch_andWithinTheCamera() {
        // A lens that focuses no closer than 25 cm would want about 4.5×.
        assertEquals(GuideOptics.MAX_ZOOM, GuideOptics.zoom(focal, sensor, minFocusDiopters = 4.0, maxZoom = 10.0), 0.0)
        assertEquals(1.5, GuideOptics.zoom(focal, sensor, minFocusDiopters = 4.0, maxZoom = 1.5), 0.0)
        assertEquals(1.0, GuideOptics.zoom(focal, sensor, minFocusDiopters = 10.0, maxZoom = 1.0), 0.0)
    }

    @Test
    fun theFocusRegionIsTheCentredThirtyPercent() {
        assertArrayEquals(intArrayOf(1400, 1050, 2600, 1950), GuideOptics.centreRegion(4000, 3000))
    }
}
