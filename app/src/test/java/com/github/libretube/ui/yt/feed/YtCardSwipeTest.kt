package com.github.libretube.ui.yt.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class YtCardSwipeTest {
    private val threshold = 280f
    private val width = 1080f

    @Test
    fun theCardFollowsOneToOneUpToTheThreshold() {
        assertEquals(120f, YtSwipePhysics.band(120f, threshold, width), 0f)
        assertEquals(-280f, YtSwipePhysics.band(-280f, threshold, width), 0f)
    }

    @Test
    fun pastTheThresholdItResistsMoreAndMore() {
        val a = YtSwipePhysics.band(threshold + 100f, threshold, width) - threshold
        val b = YtSwipePhysics.band(threshold + 200f, threshold, width) - threshold
        assertTrue(a in 1f..100f)
        assertTrue(b - a < a)
        assertTrue(YtSwipePhysics.band(1e6f, threshold, width) < threshold + width)
        assertEquals(-YtSwipePhysics.band(400f, threshold, width), YtSwipePhysics.band(-400f, threshold, width), 0.001f)
    }

    @Test
    fun unbandFindsTheFingerTravelAgain() {
        for (raw in listOf(-900f, -400f, -100f, 0f, 50f, 280f, 300f, 700f, 2000f)) {
            val shown = YtSwipePhysics.band(raw, threshold, width)
            assertEquals(raw, YtSwipePhysics.unband(shown, threshold, width), abs(raw) * 1e-3f + 0.01f)
        }
    }

    @Test
    fun lettingGoPastTheThresholdCommitsThatSide() {
        assertEquals(1, YtSwipePhysics.decide(300f, 0f, threshold))
        assertEquals(-1, YtSwipePhysics.decide(-300f, 200f, threshold))
        // thrown back hard, it does not
        assertEquals(0, YtSwipePhysics.decide(300f, -2000f, threshold))
    }

    @Test
    fun aThrowCommitsEarly() {
        assertEquals(-1, YtSwipePhysics.decide(-120f, -2000f, threshold))
        assertEquals(1, YtSwipePhysics.decide(120f, 2000f, threshold))
        // too little way yet, or a slow throw
        assertEquals(0, YtSwipePhysics.decide(-80f, -2000f, threshold))
        assertEquals(0, YtSwipePhysics.decide(-200f, -900f, threshold))
        // thrown the other way
        assertEquals(0, YtSwipePhysics.decide(-200f, 2000f, threshold))
    }

    @Test
    fun theArmedSide() {
        assertEquals(0, YtSwipePhysics.side(279f, threshold))
        assertEquals(1, YtSwipePhysics.side(280f, threshold))
        assertEquals(-1, YtSwipePhysics.side(-281f, threshold))
    }

    @Test
    fun aHiddenCardFliesPastTheLeftEdge() {
        assertEquals(-1112f, YtSwipePhysics.flyOut(1080f, 32f), 0f)
        assertEquals(-32f, YtSwipePhysics.flyOut(-10f, 32f), 0f)
    }

    @Test
    fun theCookieClicksEveryThirtyDegrees() {
        assertEquals(0, YtSwipePhysics.detents(threshold * 0.3f, threshold))
        assertEquals(1, YtSwipePhysics.detents(threshold / 3f + 1f, threshold))
        assertEquals(3, YtSwipePhysics.detents(-threshold, threshold))
        assertEquals(0, YtSwipePhysics.detents(100f, 0f))
    }

    @Test
    fun theTokenLeavesWithTheFingersVelocityAndLands() {
        val spring = YtFlightSpring(x = 0f, y = 0f, vx = 3000f, vy = 0f)
        spring.step(tx = -200f, ty = 1500f, seconds = 1f / 60f)
        // first it keeps going the way it was thrown
        assertTrue(spring.x > 0f)
        var t = 0f
        while (!spring.arrived(-200f, 1500f) && t < 3f) {
            spring.step(-200f, 1500f, 1f / 60f)
            t += 1f / 60f
        }
        assertTrue(spring.arrived(-200f, 1500f))
        assertTrue("landed after $t s", t < 1.6f)
    }

    @Test
    fun theTokenFollowsATargetThatMoves() {
        val spring = YtFlightSpring(x = 0f, y = 0f)
        repeat(30) { spring.step(500f, 500f, 1f / 60f) }
        repeat(120) { spring.step(500f, 900f, 1f / 60f) }
        assertTrue(spring.distanceTo(500f, 900f) < 6f)
    }

    @Test
    fun aLongFrameStaysStable() {
        val spring = YtFlightSpring(x = 0f, y = 0f, vx = 9000f, vy = -9000f)
        repeat(20) { spring.step(100f, 100f, 0.5f) }
        assertTrue(spring.distanceTo(100f, 100f) < 6f)
    }
}
