package com.github.libretube.ui.yt.mini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMinimizeGeometryTest {
    // a 1080 x 2340 phone, as in YtUnderFingerTest
    private val open = YtBox(0f, 63f, 1080f, 63f + 607.5f)
    private val docked = YtBox(55f, 2184f, 297f, 2321f)
    private val curve = YtContainerCurve(end = -247f + 19f)
    private val minTravel = YtMinimizeGeometry.MIN_TRAVEL_DP * 2.625f

    @Test
    fun theRealPlacesCarryTheDrag() {
        assertTrue(YtMinimizeGeometry.drivable(open, docked, curve, minTravel))
        // without tabs the container stays where it is
        assertTrue(YtMinimizeGeometry.drivable(open, docked, YtContainerCurve(0f, fixed = 0f), minTravel))
    }

    @Test
    fun aDockedPlaceAtTheOpenOneIsRefused() {
        // measured while the page was not really docked: the box where the open picture is
        assertFalse(YtMinimizeGeometry.drivable(open, open, curve, minTravel))
        // a little lower only: still no room for the finger to pull the transition along
        assertFalse(YtMinimizeGeometry.drivable(open, open.offset(0f, 60f), curve, minTravel))
    }

    @Test
    fun aDockedPlaceAboveTheOpenOneIsRefused() {
        assertFalse(YtMinimizeGeometry.drivable(open, YtBox(55f, 10f, 297f, 147f), curve, minTravel))
    }

    @Test
    fun theStuckDragIsWhatARefusedPlaceWouldCause() {
        // what the drag did with such a place: every pull down ends past the end, progress stays
        val finger = YtUnderFinger(open, open.offset(0f, 30f), 0.5f, 0.5f, 0f, 0f, YtContainerCurve(0f, fixed = 0f))
        val y0 = finger.pointY(0f)
        val fit = finger.fit(y0 + 400f, 0f)
        assertEquals(1f, fit.progress, 0f)
        assertTrue(fit.beyond > 300f)
        // the real place takes the transition along instead
        val real = YtUnderFinger(open, docked, 0.5f, 0.5f, 0f, 0f, curve)
        val p = real.fit(real.pointY(0f) + 400f, 0f).progress
        assertTrue(p > 0.05f && p < 0.95f)
    }

    @Test
    fun emptyBoxesAreRefused() {
        assertFalse(YtMinimizeGeometry.drivable(YtBox(0f, 0f, 0f, 0f), docked, curve, minTravel))
        assertFalse(YtMinimizeGeometry.drivable(open, YtBox(10f, 2000f, 10f, 2100f), curve, minTravel))
    }

    @Test
    fun aRememberedPlaceIsUsedOnlyOnTheSamePageAndConfiguration() {
        assertTrue(YtMinimizeGeometry.seenUsable(1080, 2200, 1080, 2200, sameConfiguration = true))
        // the same size again after a turn and back: measured anew
        assertFalse(YtMinimizeGeometry.seenUsable(1080, 2200, 1080, 2200, sameConfiguration = false))
        assertFalse(YtMinimizeGeometry.seenUsable(2200, 1000, 1080, 2200, sameConfiguration = true))
        assertFalse(YtMinimizeGeometry.seenUsable(0, 0, 0, 0, sameConfiguration = true))
    }
}
