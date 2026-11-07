package com.github.libretube.ui.yt.feed

import com.github.libretube.ui.yt.feed.YtSwipeCalibration.Outcome
import com.github.libretube.ui.yt.feed.YtSwipeCalibration.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class YtSwipeCalibrationTest {
    /** A straight stroke of [length] dp at [degrees] from the horizontal (positive x, down), in 2 dp steps. */
    private fun line(degrees: Float, length: Float, left: Boolean = false): List<Point> {
        val r = Math.toRadians(degrees.toDouble())
        val steps = (length / 2f).toInt()
        return (0..steps).map {
            val d = it * 2f
            Point(((if (left) -1 else 1) * d * cos(r)).toFloat(), (d * sin(r)).toFloat())
        }
    }

    /** A swipe that starts flat and bends down to [endDegrees] (a thumb's arc). */
    private fun arc(endDegrees: Float, length: Float): List<Point> {
        val steps = (length / 2f).toInt()
        var x = 0f
        var y = 0f
        return (0..steps).map {
            val a = Math.toRadians((endDegrees * it / steps).toDouble())
            if (it > 0) {
                x += (2f * cos(a)).toFloat()
                y += (2f * sin(a)).toFloat()
            }
            Point(x, y)
        }
    }

    @Test
    fun classifyMatchesTheCardsRules() {
        val t = YtSwipeTuning.DEFAULT
        assertEquals(Outcome.TAP, YtSwipeCalibration.classify(listOf(Point(0f, 0f), Point(2f, 3f)), t))
        assertEquals(Outcome.SCROLL, YtSwipeCalibration.classify(line(80f, 300f), t))
        // slanted scroll at 40°: never claimed
        assertEquals(Outcome.SCROLL, YtSwipeCalibration.classify(line(40f, 300f), t))
        assertEquals(Outcome.SWIPE, YtSwipeCalibration.classify(line(5f, 200f), t))
        assertEquals(Outcome.SWIPE, YtSwipeCalibration.classify(line(5f, 200f, left = true), t))
        assertEquals(Outcome.SHORT, YtSwipeCalibration.classify(line(5f, 60f), t))
        // starts flat, bends down past 30° (or the 24 dp drift): dropped
        assertEquals(Outcome.DROPPED, YtSwipeCalibration.classify(arc(70f, 240f), t))
    }

    @Test
    fun noProposalWithTooFewStrokes() {
        val scrolls = List(2) { line(85f, 300f) }
        val swipes = List(4) { line(5f, 200f) }
        assertNull(YtSwipeCalibration.propose(scrolls, swipes, YtSwipeTuning.DEFAULT))
        // taps do not count
        val taps = List(5) { listOf(Point(0f, 0f), Point(1f, 1f)) }
        assertNull(YtSwipeCalibration.propose(taps, swipes, YtSwipeTuning.DEFAULT))
    }

    /** A swipe whose first [first] dp go at [degrees], then flat sideways for [rest] dp. */
    private fun slantedStart(degrees: Float, first: Float, rest: Float): List<Point> {
        val start = line(degrees, first)
        val end = start.last()
        return start + line(0f, rest).drop(1).map { Point(end.x + it.x, end.y + it.y) }
    }

    @Test
    fun aUserWhoseSwipesStartSlantedGetsAWiderAngle() {
        val scrolls = listOf(line(80f, 400f), line(75f, 350f), line(85f, 500f), line(78f, 420f))
        // the thumb starts at ~28°: the default claim (26.6°) gives these to the list
        val swipes = listOf(slantedStart(28f, 20f, 160f), slantedStart(29f, 22f, 180f), slantedStart(27f, 18f, 150f), slantedStart(28f, 20f, 170f))
        val p = YtSwipeCalibration.propose(scrolls, swipes, YtSwipeTuning.DEFAULT)
        assertNotNull(p)
        p!!
        assertFalse(p.conflict)
        assertTrue(p.before.swipesOk < p.before.swipes)
        assertTrue(p.after.perfect)
        assertTrue(p.tuning.angleDeg > 30f)
        // still safe for scrolls: the claim stays below the flattest scroll
        assertTrue(p.tuning.claimAngleDeg < 75f)
    }

    @Test
    fun aUserWhoSwipesInAnArcGetsMoreDrift() {
        val scrolls = List(4) { line(82f, 400f) }
        // thumbs arc down ~30 dp over the swipe: more than the default 24 dp drift
        val swipes = listOf(arc(20f, 180f), arc(22f, 190f), arc(18f, 170f), arc(21f, 185f))
        val p = YtSwipeCalibration.propose(scrolls, swipes, YtSwipeTuning.DEFAULT)!!
        assertTrue(p.before.swipesOk < p.before.swipes)
        assertTrue(p.tuning.driftDp > 24f)
        assertTrue(p.after.perfect)
    }

    @Test
    fun theDriftLeavesRoomAboveTheSwipesWanderAndTheDistanceFollowsTheirLength() {
        val scrolls = List(4) { line(85f, 400f) }
        val swipes = listOf(line(8f, 150f), line(10f, 160f), line(6f, 140f), line(9f, 170f))
        val p = YtSwipeCalibration.propose(scrolls, swipes, YtSwipeTuning.DEFAULT)!!
        val wander = 170f * sin(Math.toRadians(9.0)).toFloat()
        assertTrue(p.tuning.driftDp >= wander)
        // short swipes: a shorter distance than 96 so they reach it
        assertTrue(p.tuning.distanceDp < 96f)
        assertTrue(p.after.perfect)
    }

    @Test
    fun whenSwipesAreAsSteepAsScrollsScrollingWins() {
        val scrolls = List(4) { line(35f, 300f) }
        val swipes = List(4) { line(33f, 300f) }
        val p = YtSwipeCalibration.propose(scrolls, swipes, YtSwipeTuning.DEFAULT)!!
        assertTrue(p.conflict)
        assertEquals(p.after.scrolls, p.after.scrollsOk)
    }

    @Test
    fun proposedValuesStayInTheSettingsRanges() {
        val scrolls = List(4) { line(89f, 900f) }
        val swipes = List(4) { line(0f, 900f) }
        val t = YtSwipeCalibration.propose(scrolls, swipes, YtSwipeTuning.DEFAULT)!!.tuning
        assertTrue(t.angleDeg in YtSwipeTuning.ANGLE_RANGE)
        assertTrue(t.driftDp in YtSwipeTuning.DRIFT_RANGE)
        assertTrue(t.distanceDp in YtSwipeTuning.DISTANCE_RANGE)
    }

    @Test
    fun angleForClaimInvertsTheClaimAngle() {
        for (a in listOf(15f, 22f, 30f, 38f, 45f)) {
            val claim = YtSwipeTuning(angleDeg = a).claimAngleDeg
            assertEquals(a, YtSwipeCalibration.angleForClaim(claim), 0.01f)
        }
    }
}
