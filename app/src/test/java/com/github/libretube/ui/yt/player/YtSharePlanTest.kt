package com.github.libretube.ui.yt.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSharePlanTest {
    private fun zone(x: Float, y: Float) =
        YtSharePlan.inZone(x, y, barWidth = 1000f, barCenterY = 20f, topY = -500f, sidePx = 24f, belowPx = 48f)

    @Test
    fun theZoneIsTheBarAndThePictureAboveIt() {
        assertTrue(zone(500f, 20f))
        assertTrue(zone(0f, -499f))
        assertTrue(zone(-24f, 68f))
        assertTrue(zone(1024f, 20f))
        // too far below, beside or above the picture
        assertFalse(zone(500f, 69f))
        assertFalse(zone(-25f, 20f))
        assertFalse(zone(1025f, 20f))
        assertFalse(zone(500f, -501f))
    }

    @Test
    fun secondsAreTruncatedAndStayInTheVideo() {
        assertEquals(723L, YtSharePlan.seconds(723_999.0, 1_000_000L))
        assertEquals(0L, YtSharePlan.seconds(-5.0, 1_000_000L))
        assertEquals(0L, YtSharePlan.seconds(Double.NaN, 1_000_000L))
        // a 10 s video has seconds 0 to 9
        assertEquals(9L, YtSharePlan.seconds(10_000.0, 10_000L))
        assertEquals(9L, YtSharePlan.seconds(9_500.0, 10_000L))
        assertEquals(0L, YtSharePlan.seconds(5_000.0, 0L))
    }

    @Test
    fun sharingHasTheLevelsButNoFrames() {
        val lines = YtSharePlan.levelLines(floatArrayOf(40f, 90f, 140f, 190f))
        assertArrayEquals(floatArrayOf(40f, 90f, 140f, Float.POSITIVE_INFINITY), lines, 0f)
        val engine = YtScrubPrecision(lines, hysteresisPx = 8f, framePx = 12f, magnetPx = 6f, cancelBelowPx = Float.MAX_VALUE)
        engine.start(60_000L, 30_000L, 600_000L, 1000f, 1000.0 / 30.0, 0f)
        engine.move(0f, 120f)
        assertEquals(YtScrubLevel.QUARTER, engine.level)
        engine.move(0f, 5000f)
        assertEquals(YtScrubLevel.EIGHTH, engine.level)
        // ⅛ of 600 s across 1000 px: 75 ms per px
        engine.move(40f, 5000f)
        assertEquals(63_000.0, engine.positionMs, 0.5)
        // far below the bar it never cancels: the zone decides that
        engine.move(0f, -10_000f)
        assertFalse(engine.cancelling)
        assertEquals(YtScrubLevel.FULL, engine.level)
    }

    @Test
    fun theFlingSpeedComesFromTheLastTenthOfASecond() {
        val t = YtFlingTracker()
        assertArrayEquals(floatArrayOf(0f, 0f), t.velocity(), 0f)
        t.add(0L, 0f, 0f)
        t.add(500L, 1000f, 0f)
        // the old sample is outside the window: one sample left
        assertArrayEquals(floatArrayOf(0f, 0f), t.velocity(), 0f)
        t.add(550L, 1050f, -10f)
        t.add(600L, 1100f, -20f)
        val v = t.velocity()
        assertEquals(1000f, v[0], 0.01f)
        assertEquals(-200f, v[1], 0.01f)
        t.reset()
        assertArrayEquals(floatArrayOf(0f, 0f), t.velocity(), 0f)
    }

    @Test
    fun theTrackerKeepsOnlyItsRing() {
        val t = YtFlingTracker(windowMs = 1000L)
        for (i in 0 until 40) t.add(i * 10L, i * 2f, 0f)
        val v = t.velocity()
        // 2 px per 10 ms, over the 16 samples it keeps
        assertEquals(200f, v[0], 0.01f)
        assertNotEquals(0f, v[0])
    }
}
