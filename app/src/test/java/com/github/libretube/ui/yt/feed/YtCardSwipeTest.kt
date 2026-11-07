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

    private val slop = 22f // 8 dp at 2.75x
    private val drift = 66f // 24 dp

    /**
     * Plays a straight move from (0, 0) to ([toX], [toY]) in [steps] events through the claim
     * and drift rules, then lets go with ([vx], [vy]) as the card's gesture does. @return the way
     */
    private fun play(toX: Float, toY: Float, vx: Float, vy: Float, steps: Int = 40): Int {
        var claimedAt = -1
        for (i in 1..steps) {
            val x = toX * i / steps
            val y = toY * i / steps
            if (claimedAt < 0) {
                when (YtSwipePhysics.claim(x, y, slop)) {
                    YtSwipePhysics.Claim.SCROLL -> return 0
                    YtSwipePhysics.Claim.SWIPE -> claimedAt = i
                    YtSwipePhysics.Claim.WAIT -> Unit
                }
            } else if (YtSwipePhysics.drifted(x, y, drift, slop)) {
                return 0
            }
        }
        if (claimedAt < 0) return 0
        val shown = YtSwipePhysics.band(toX - kotlin.math.sign(toX) * slop, threshold, width)
        return YtSwipePhysics.decide(shown, vx, threshold, vy)
    }

    @Test
    fun aSlantedScrollDownNeverCommits() {
        // from steep to quite flat, both ways, slow and thrown, short and long
        for (angle in listOf(30.0, 35.0, 40.0, 45.0, 55.0, 70.0, 85.0)) {
            for (length in listOf(100f, 300f, 600f, 1200f)) {
                for (side in listOf(-1f, 1f)) {
                    for (up in listOf(-1f, 1f)) {
                        val rad = Math.toRadians(angle)
                        val dx = side * length * kotlin.math.cos(rad).toFloat()
                        val dy = up * length * kotlin.math.sin(rad).toFloat()
                        for (speed in listOf(0f, 1000f, 3000f)) {
                            val vx = dx / length * speed
                            val vy = dy / length * speed
                            assertEquals("angle=$angle len=$length dx=$dx dy=$dy v=$speed", 0, play(dx, dy, vx, vy))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aSlantedScrollIsNeverClaimed() {
        assertEquals(YtSwipePhysics.Claim.SCROLL, YtSwipePhysics.claim(20f, 25f, slop))
        assertEquals(YtSwipePhysics.Claim.SCROLL, YtSwipePhysics.claim(-40f, 23f, slop))
        assertEquals(YtSwipePhysics.Claim.WAIT, YtSwipePhysics.claim(25f, 15f, slop))
        assertEquals(YtSwipePhysics.Claim.WAIT, YtSwipePhysics.claim(10f, 2f, slop))
        assertEquals(YtSwipePhysics.Claim.SWIPE, YtSwipePhysics.claim(30f, 15f, slop))
        assertEquals(YtSwipePhysics.Claim.SWIPE, YtSwipePhysics.claim(-23f, 0f, slop))
    }

    @Test
    fun aClearSidewaysSwipeStillCommits() {
        // slow past the threshold, with a little wobble
        assertEquals(1, play(400f, 20f, 0f, 0f))
        assertEquals(-1, play(-400f, -30f, -100f, 50f))
        // a slight slope over a long way
        assertEquals(1, play(500f, 60f, 300f, 40f))
        // a flick before the threshold
        assertEquals(-1, play(-150f, 10f, -2400f, 200f))
        assertEquals(1, play(150f, -15f, 2400f, -400f))
    }

    @Test
    fun aSwipeThatTurnsDownIsDropped() {
        // claimed sideways, then the finger goes down: dropped once past the drift
        assertTrue(YtSwipePhysics.drifted(300f, 70f, drift, slop))
        assertTrue(YtSwipePhysics.drifted(60f, 40f, drift, slop))
        assertTrue(!YtSwipePhysics.drifted(300f, 40f, drift, slop))
        assertTrue(!YtSwipePhysics.drifted(30f, 15f, drift, slop))
    }

    @Test
    fun aSteepReleaseNeverCommits() {
        // past the threshold, then flicked down to scroll on
        assertEquals(0, YtSwipePhysics.decide(320f, 300f, threshold, 2000f))
        assertEquals(0, YtSwipePhysics.decide(-200f, -2000f, threshold, 1500f))
        // held still past the threshold: a little vertical noise does not matter
        assertEquals(1, YtSwipePhysics.decide(320f, 20f, threshold, 300f))
        assertEquals(-1, YtSwipePhysics.decide(-200f, -2400f, threshold, 900f))
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
