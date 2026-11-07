package com.github.libretube.ui.yt

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/** The pill outline that Formen's search field blends into the header's cookie. */
class YtSearchMorphTest {
    @Test
    fun circleIsTheSameInEveryDirection() {
        val d = 1f / sqrt(2f)
        assertEquals(10f, stadiumRay(1f, 0f, 10f, 10f), 1e-4f)
        assertEquals(10f, stadiumRay(0f, -1f, 10f, 10f), 1e-4f)
        assertEquals(10f, stadiumRay(d, d, 10f, 10f), 1e-4f)
    }

    @Test
    fun wideStadiumReachesItsEndsAndItsFlatSides() {
        // 200 x 40: the ends are 100 away, the flat top and bottom 20
        assertEquals(100f, stadiumRay(1f, 0f, 100f, 20f), 1e-4f)
        assertEquals(100f, stadiumRay(-1f, 0f, 100f, 20f), 1e-4f)
        assertEquals(20f, stadiumRay(0f, 1f, 100f, 20f), 1e-4f)
        // a steep ray still meets the flat side
        val dx = 0.6f
        val dy = 0.8f
        assertEquals(25f, stadiumRay(dx, dy, 100f, 20f), 1e-4f)
    }

    @Test
    fun rayIntoTheRoundEndLandsOnItsArc() {
        // a shallow ray (about 5 degrees) passes the flat side and ends on the round end
        val dx = 0.9962f
        val dy = sqrt(1f - dx * dx)
        val r = stadiumRay(dx, dy, 100f, 20f)
        // the point lies 20 from the arc's centre at (80, 0)
        val x = r * dx - 80f
        val y = r * dy
        assertEquals(20f, sqrt(x * x + y * y), 1e-3f)
    }

    @Test
    fun tallStadiumIsTheWideOneTurned() {
        assertEquals(stadiumRay(1f, 0f, 100f, 20f), stadiumRay(0f, 1f, 20f, 100f), 1e-4f)
        assertEquals(stadiumRay(0.6f, 0.8f, 100f, 20f), stadiumRay(0.8f, 0.6f, 20f, 100f), 1e-4f)
    }
}
