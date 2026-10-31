package com.github.libretube.ui.yt.mini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class YtUnderFingerTest {
    // a 1080 x 2340 phone (2.625 px per dp, the bar measured 2.75 px per dp)
    private val portraitStart = YtBox(0f, 63f, 1080f, 63f + 607.5f)
    private val portraitThumb = YtBox(55f, 2184f, 297f, 2321f)
    private val portraitCurve = YtContainerCurve(end = -247f + 19f)

    // the same phone turned: the video takes 60 % of the width, the rest is the related list
    private val landscapeStart = YtBox(0f, 0f, 1404f, 789.75f)
    private val landscapeThumb = YtBox(40f, 966f, 230f, 1073f)
    private val landscapeCurve = YtContainerCurve(end = -150f + 12f)

    private fun portrait(u: Float = 0.5f, v: Float = 0.5f) =
        YtUnderFinger(portraitStart, portraitThumb, u, v, pageLeft = 0f, pageTop = 0f, curve = portraitCurve)

    private fun landscape(u: Float = 0.5f, v: Float = 0.5f) =
        YtUnderFinger(landscapeStart, landscapeThumb, u, v, pageLeft = 0f, pageTop = 0f, curve = landscapeCurve)

    @Test
    fun theGrabbedPointMovesDownTheWholeWay() {
        for (finger in listOf(portrait(), portrait(0.1f, 0.9f), portrait(0.9f, 0.05f), landscape(), landscape(0f, 1f))) {
            var last = finger.pointY(0f)
            for (i in 1..1000) {
                val y = finger.pointY(i / 1000f)
                assertTrue("y must grow at p=${i / 1000f}: $last -> $y", y > last)
                last = y
            }
        }
    }

    @Test
    fun theFitPutsThePointUnderTheFingerWithinHalfAPixel() {
        for (finger in listOf(portrait(), portrait(0.2f, 0.8f), landscape(), landscape(0.7f, 0.3f))) {
            val top = finger.pointY(0f)
            val bottom = finger.pointY(1f)
            var y = top
            var near = 0f
            while (y <= bottom) {
                val fit = finger.fit(y, near)
                assertEquals("no rest inside the range at y=$y", 0f, fit.beyond, 0f)
                assertEquals("y=$y p=${fit.progress}", y, finger.pointY(fit.progress), 0.5f)
                near = fit.progress
                y += 3.7f
            }
        }
    }

    @Test
    fun theEndsAreWhereTheyAre() {
        val finger = portrait()
        assertEquals(0f, finger.fit(finger.pointY(0f)).progress, 1e-5f)
        assertEquals(1f, finger.fit(finger.pointY(1f), 1f).progress, 1e-5f)
        // the container waits for 35 %: the point's way is straight before and after, bent there
        val atHold = finger.pointY(YtContainerCurve.HOLD)
        assertEquals(YtContainerCurve.HOLD, finger.fit(atHold, 0.3f).progress, 1e-4f)
    }

    @Test
    fun pastTheEndsTheRestIsBeyond() {
        val finger = portrait()
        val above = finger.fit(finger.pointY(0f) - 80f)
        assertEquals(0f, above.progress, 0f)
        assertEquals(-80f, above.beyond, 1e-3f)
        val below = finger.fit(finger.pointY(1f) + 120f, 1f)
        assertEquals(1f, below.progress, 0f)
        assertEquals(120f, below.beyond, 1e-3f)
    }

    @Test
    fun withoutTabsTheContainerStaysWhereItIs() {
        val curve = YtContainerCurve(end = -228f, fixed = 40f)
        assertEquals(40f, curve.at(0f), 0f)
        assertEquals(40f, curve.at(0.7f), 0f)
        assertEquals(2, curve.breaks.size)
        val finger = YtUnderFinger(portraitStart, portraitThumb, 0.5f, 0.5f, 0f, 0f, curve)
        val y = (finger.pointY(0f) + finger.pointY(1f)) / 2f
        assertEquals(y, finger.pointY(finger.fit(y).progress), 0.5f)
    }

    @Test
    fun theContainerCurveHoldsThenRunsStraight() {
        val curve = YtContainerCurve(end = -200f)
        assertEquals(0f, curve.at(0f), 0f)
        assertEquals(0f, curve.at(0.35f), 0f)
        assertEquals(-100f, curve.at(0.675f), 1e-3f)
        assertEquals(-200f, curve.at(1f), 0f)
        assertEquals(-200f, curve.at(1.4f), 0f)
    }

    @Test
    fun whereThePointMovesBackTheNearestProgressWins() {
        // a made-up layout in which the container rises faster than the view sinks: the point
        // goes down until the container starts to move (p = 0.35), then back up past its start
        val start = YtBox(0f, 0f, 100f, 100f)
        val end = YtBox(0f, 600f, 100f, 700f)
        val finger = YtUnderFinger(start, end, 0.5f, 0.5f, 0f, 0f, YtContainerCurve(end = -1500f))
        val y = 100f
        val late = finger.fit(y, near = 0.4f)
        assertEquals(y, finger.pointY(late.progress), 0.5f)
        assertTrue(late.progress > 0.35f)
        val early = finger.fit(y, near = 0.05f)
        assertEquals(y, finger.pointY(early.progress), 0.5f)
        assertTrue(early.progress < 0.35f)
    }

    @Test
    fun aSlowReleaseStaysOnItsSideOfHalfWay() {
        val finger = portrait()
        val at40 = finger.pointY(0.4f)
        val at60 = finger.pointY(0.6f)
        assertEquals(0, finger.settle(at40, 0f, 0.4f))
        assertEquals(1, finger.settle(at60, 0f, 0.6f))
    }

    @Test
    fun aFlickCarriesTheReleaseAcross() {
        val finger = portrait()
        assertEquals(1, finger.settle(finger.pointY(0.2f), 1600f, 0.2f))
        assertEquals(0, finger.settle(finger.pointY(0.8f), -1600f, 0.8f))
        // a flick against the way it is going wins over the position
        assertEquals(0, finger.settle(finger.pointY(0.55f), -900f, 0.55f))
    }

    @Test
    fun theRubberBandGivesLessAndLess() {
        assertEquals(0f, YtDragMath.rubberBand(0f, 150f), 0f)
        var last = 0f
        for (pull in 1..2000) {
            val moved = YtDragMath.rubberBand(pull.toFloat(), 150f)
            assertTrue(moved > last)
            assertTrue(moved < pull.toFloat())
            assertTrue(moved < 150f)
            last = moved
        }
        assertEquals(-YtDragMath.rubberBand(90f, 150f), YtDragMath.rubberBand(-90f, 150f), 1e-5f)
        assertEquals(0f, YtDragMath.rubberBand(50f, 0f), 0f)
    }

    @Test
    fun sidewaysTheVideoFollowsOneToOneThenGently() {
        assertEquals(40f, YtDragMath.follow(40f, 270f, 1080f), 0f)
        assertEquals(-270f, YtDragMath.follow(-270f, 270f, 1080f), 0f)
        var last = 270f
        for (offset in 271..2000) {
            val moved = YtDragMath.follow(offset.toFloat(), 270f, 1080f)
            assertTrue(moved > last)
            assertTrue(moved <= offset.toFloat())
            last = moved
        }
        assertEquals(-YtDragMath.follow(600f, 270f, 1080f), YtDragMath.follow(-600f, 270f, 1080f), 1e-4f)
    }

    @Test
    fun theRubberBandAndTheFollowCanBeUndone() {
        for (pull in listOf(-900f, -120f, -3f, 0f, 2f, 45f, 300f, 1500f)) {
            val moved = YtDragMath.rubberBand(pull, 147f)
            assertEquals("pull $pull", pull, YtDragMath.unRubberBand(moved, 147f), abs(pull) * 1e-3f + 1e-3f)
            val followed = YtDragMath.follow(pull, 270f, 1080f)
            assertEquals("offset $pull", pull, YtDragMath.unFollow(followed, 270f, 1080f), abs(pull) * 1e-3f + 1e-3f)
        }
        // past what the band can show it stays finite
        assertTrue(YtDragMath.unRubberBand(500f, 147f).isFinite())
    }

    @Test
    fun theDockedPlayerClosesWhenPulledOrFlickedFarEnough() {
        assertFalse(YtDragMath.closes(0f, 2000f, 150f))
        assertFalse(YtDragMath.closes(100f, 0f, 150f))
        assertTrue(YtDragMath.closes(160f, 0f, 150f))
        assertTrue(YtDragMath.closes(40f, 1500f, 150f))
        // flicked back up: it stays
        assertFalse(YtDragMath.closes(200f, -800f, 150f))
    }

    @Test
    fun aFlickProjectsLikeScrolling() {
        assertEquals(0f, YtDragMath.project(0f), 0f)
        assertEquals(499f, YtDragMath.project(1000f), 0.5f)
        assertEquals(-99f, YtDragMath.project(-1000f, 0.99f), 0.5f)
    }

    @Test
    fun aCriticalSpringArrivesWithoutOvershootFromRest() {
        val spring = YtSpring(YtSpring.stiffnessFor(0.4f), 1f)
        spring.position = 1000f
        var t = 0f
        while (t < 2f) {
            spring.step(0f, 1f / 120f)
            assertTrue("no overshoot: ${spring.position}", spring.position >= -1e-3f)
            t += 1f / 120f
        }
        assertTrue(spring.isAtRest(0f))
    }

    @Test
    fun theSpringStartsWithTheFingersVelocity() {
        val spring = YtSpring(YtSpring.stiffnessFor(0.4f), 1f)
        spring.position = 500f
        spring.velocity = 2400f
        spring.step(1500f, 0.001f)
        // in the first ms it moves on at the finger's speed (the pull adds a tenth of a px)
        assertEquals(502.4f, spring.position, 0.15f)
        assertEquals(2400f, spring.velocity, 200f)
    }

    @Test
    fun oneLongFrameEqualsManyShortOnes() {
        for (damping in listOf(0.7f, 1f, 1.3f)) {
            val a = YtSpring(YtSpring.stiffnessFor(0.35f), damping).apply { position = 300f; velocity = -900f }
            val b = YtSpring(YtSpring.stiffnessFor(0.35f), damping).apply { position = 300f; velocity = -900f }
            a.step(0f, 0.1f)
            repeat(100) { b.step(0f, 0.001f) }
            assertEquals("damping $damping", a.position, b.position, 0.01f)
            assertEquals("damping $damping", a.velocity, b.velocity, 0.1f)
        }
    }

    @Test
    fun anUnderdampedSpringSettlesALittlePastAndBack() {
        val spring = YtSpring(YtSpring.stiffnessFor(0.4f), 0.8f)
        spring.position = 0f
        var lowest = 0f
        var t = 0f
        while (t < 3f) {
            spring.step(-500f, 1f / 60f)
            lowest = minOf(lowest, spring.position)
            t += 1f / 60f
        }
        assertTrue("it swings past the target: $lowest", lowest < -500f)
        assertTrue("but not far: $lowest", lowest > -560f)
        assertTrue(spring.isAtRest(-500f))
    }

    @Test
    fun aSpringThrownAtTheTargetCrossesItOnce() {
        val spring = YtSpring(YtSpring.stiffnessFor(0.38f), 1f)
        spring.position = 100f
        spring.velocity = -6000f
        var crossings = 0
        var last = spring.position
        repeat(600) {
            spring.step(0f, 1f / 240f)
            if ((last > 0f) != (spring.position > 0f)) crossings++
            last = spring.position
        }
        assertEquals(1, crossings)
        assertTrue(abs(spring.position) < 0.5f)
    }

    @Test
    fun landscapeAndPortraitBothFit() {
        for (finger in listOf(portrait(0.3f, 0.6f), landscape(0.3f, 0.6f))) {
            val mid = finger.pointY(0.5f)
            assertEquals(0.5f, finger.fit(mid, 0.4f).progress, 1e-4f)
            assertEquals(finger.start.left + 0.3f * finger.start.width, finger.pointX(0f), 1e-3f)
            assertEquals(finger.end.left + 0.3f * finger.end.width, finger.pointX(1f), 1e-3f)
        }
    }

    /** Runs a release as YtMinimizeDrag does: the point springs to the end, the transition hands over there. */
    private fun release(finger: YtUnderFinger, from: Float, velocity: Float, target: Int): Pair<Float, List<Float>> {
        val spring = if (target == 1) YtMinimizeSprings.dock() else YtMinimizeSprings.open()
        spring.position = finger.pointY(from)
        spring.velocity = velocity
        val goal = finger.pointY(target.toFloat())
        val path = mutableListOf(spring.position)
        var t = 0f
        var near = from
        while (t < 2f) {
            spring.step(goal, 1f / 120f)
            t += 1f / 120f
            path += spring.position
            val asked = finger.fit(spring.position, near).progress
            near = asked
            val arrived = if (target == 1) asked >= 0.999f else asked <= 0.001f
            if (arrived || spring.isAtRest(goal)) return t to path
        }
        return Float.NaN to path
    }

    @Test
    fun aReleaseDocksQuicklyAndOnlyForwards() {
        val finger = portrait()
        for ((from, velocity) in listOf(0.6f to 0f, 0.3f to 2500f, 0.8f to 600f, 0.55f to 0f)) {
            assertEquals(1, finger.settle(finger.pointY(from), velocity, from))
            val (time, path) = release(finger, from, velocity, 1)
            assertTrue("from $from at $velocity: arrives in time ($time s)", time < 0.45f)
            // it never goes back up before it arrives
            for (i in 1 until path.size) assertTrue(path[i] >= path[i - 1] - 1e-3f)
        }
    }

    @Test
    fun aReleaseOpensQuickly() {
        val finger = portrait()
        for ((from, velocity) in listOf(0.3f to 0f, 0.7f to -2500f, 0.45f to -300f)) {
            assertEquals(0, finger.settle(finger.pointY(from), velocity, from))
            val (time, _) = release(finger, from, velocity, 0)
            assertTrue("from $from at $velocity: arrives in time ($time s)", time < 0.5f)
        }
    }

    @Test
    fun theReleaseGoesOnAtTheFingersSpeed() {
        val finger = portrait()
        val (_, path) = release(finger, 0.4f, 1800f, 1)
        // the first frame moves on as far as the finger would have, plus a little of the pull
        val step = path[1] - path[0]
        assertTrue("first step $step", step >= 1800f / 120f - 0.5f && step < 1800f / 120f + 10f)
    }
}
