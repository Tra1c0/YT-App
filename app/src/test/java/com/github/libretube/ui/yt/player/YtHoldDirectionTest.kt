package com.github.libretube.ui.yt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtHoldDirectionTest {
    private fun walk(vararg points: Pair<Float, Float>): List<YtHoldState> {
        var state = YtHoldState()
        return points.map { (x, y) -> YtHoldDirection.next(state, x, y).also { state = it } }
    }

    @Test
    fun `the centre does nothing`() {
        val s = YtHoldDirection.next(YtHoldState(), 20f, -25f)
        assertNull(s.dir)
        assertEquals(YtHoldStage.CENTRE, s.stage)
        assertFalse(s.leftCentre)
        assertFalse(YtHoldDirection.runs(s))
    }

    @Test
    fun `up lights at 40 and arms at 88`() {
        val s = walk(0f to -39f, 0f to -40f, 0f to -87f, 0f to -88f)
        assertEquals(YtHoldStage.CENTRE, s[0].stage)
        assertEquals(YtHoldDir.UP, s[1].dir)
        assertEquals(YtHoldStage.HIGHLIGHT, s[1].stage)
        assertEquals(YtHoldStage.HIGHLIGHT, s[2].stage)
        assertEquals(YtHoldStage.ARMED, s[3].stage)
        assertTrue(YtHoldDirection.runs(s[3]))
    }

    @Test
    fun `each direction by its sign`() {
        assertEquals(YtHoldDir.DOWN, YtHoldDirection.next(YtHoldState(), 3f, 60f).dir)
        assertEquals(YtHoldDir.LEFT, YtHoldDirection.next(YtHoldState(), -60f, 3f).dir)
        assertEquals(YtHoldDir.RIGHT, YtHoldDirection.next(YtHoldState(), 60f, -3f).dir)
        assertEquals(YtHoldDir.UP, YtHoldDirection.next(YtHoldState(), -3f, -60f).dir)
    }

    @Test
    fun `a diagonal picks nothing from the centre`() {
        val s = YtHoldDirection.next(YtHoldState(), 70f, -60f)
        assertNull(s.dir)
        assertTrue(s.leftCentre)
    }

    @Test
    fun `on the diagonal the target stays while its axis leads`() {
        val s = walk(0f to -95f, 80f to -100f)
        assertEquals(YtHoldDir.UP, s[1].dir)
        assertEquals(YtHoldStage.ARMED, s[1].stage)
        // past the diagonal the axis no longer leads
        val t = YtHoldDirection.next(s[1], 100f, -95f)
        assertNull(t.dir)
    }

    @Test
    fun `hysteresis keeps the stage a little closer in`() {
        val s = walk(0f to -90f, 0f to -82f, 0f to -79f, 0f to -35f, 0f to -31f)
        assertEquals(YtHoldStage.ARMED, s[0].stage)
        assertEquals(YtHoldStage.ARMED, s[1].stage)
        assertEquals(YtHoldStage.HIGHLIGHT, s[2].stage)
        assertEquals(YtHoldStage.HIGHLIGHT, s[3].stage)
        assertEquals(YtHoldStage.CENTRE, s[4].stage)
        // left the centre once: stays so
        assertTrue(s[4].leftCentre)
    }

    @Test
    fun `back to the centre cancels`() {
        val s = walk(0f to -100f, 0f to -10f)
        assertFalse(YtHoldDirection.runs(s[1]))
        assertEquals(YtHoldFeedback.DETENT, YtHoldDirection.feedback(s[0], s[1]))
    }

    @Test
    fun `a new target starts fresh`() {
        val s = walk(0f to -100f, 100f to -10f)
        assertEquals(YtHoldDir.RIGHT, s[1].dir)
        assertEquals(YtHoldStage.ARMED, s[1].stage)
        assertEquals(YtHoldFeedback.ARM, YtHoldDirection.feedback(s[0], s[1]))
    }

    @Test
    fun `feedback at each threshold`() {
        val s = walk(0f to -50f, 0f to -60f, 0f to -90f, 0f to -95f, 0f to -70f)
        assertEquals(YtHoldFeedback.DETENT, YtHoldDirection.feedback(YtHoldState(), s[0]))
        assertNull(YtHoldDirection.feedback(s[0], s[1]))
        assertEquals(YtHoldFeedback.ARM, YtHoldDirection.feedback(s[1], s[2]))
        assertNull(YtHoldDirection.feedback(s[2], s[3]))
        assertEquals(YtHoldFeedback.DISARM, YtHoldDirection.feedback(s[3], s[4]))
    }

    @Test
    fun `the knob follows 1 to 1 then gives without a kink`() {
        val limit = 76f
        assertEquals(30f, YtHoldDirection.knob(30f, limit), 0.0001f)
        val free = limit * 0.7f
        assertEquals(free, YtHoldDirection.knob(free, limit), 0.0001f)
        // the slope just past the join is still about 1
        val slope = (YtHoldDirection.knob(free + 0.01f, limit) - YtHoldDirection.knob(free, limit)) / 0.01f
        assertEquals(1f, slope, 0.01f)
        var last = 0f
        for (d in 1..400) {
            val k = YtHoldDirection.knob(d.toFloat(), limit)
            assertTrue(k >= last)
            assertTrue(k < limit)
            last = k
        }
        assertEquals(0f, YtHoldDirection.knob(-5f, limit), 0f)
    }

    @Test
    fun `edge strips`() {
        assertTrue(YtHoldDirection.inEdge(10f, 1000f, 60f))
        assertTrue(YtHoldDirection.inEdge(950f, 1000f, 60f))
        assertFalse(YtHoldDirection.inEdge(500f, 1000f, 60f))
    }

    @Test
    fun `previous chapter`() {
        val starts = longArrayOf(0L, 45_000L, 90_000L)
        // a while into a chapter: its own start
        assertEquals(45_000L, YtHoldDirection.previousChapterMs(60_000L, starts))
        // just after it began: the one before
        assertEquals(0L, YtHoldDirection.previousChapterMs(46_000L, starts))
        assertEquals(0L, YtHoldDirection.previousChapterMs(1_000L, starts))
        assertNull(YtHoldDirection.previousChapterMs(1_000L, longArrayOf()))
        // unsorted input, before the first chapter
        assertEquals(0L, YtHoldDirection.previousChapterMs(2_000L, longArrayOf(90_000L, 5_000L)))
    }

    @Test
    fun `next chapter`() {
        val starts = longArrayOf(90_000L, 0L, 45_000L)
        assertEquals(45_000L, YtHoldDirection.nextChapterMs(10_000L, starts))
        // right at a boundary it goes on to the next one
        assertEquals(90_000L, YtHoldDirection.nextChapterMs(44_800L, starts))
        assertNull(YtHoldDirection.nextChapterMs(95_000L, starts))
        assertNull(YtHoldDirection.nextChapterMs(0L, longArrayOf()))
    }

    @Test
    fun `speed steps`() {
        assertEquals(1.25f, YtHoldDirection.speedUp(1f), 0f)
        assertEquals(1.25f, YtHoldDirection.speedUp(1.1f), 0f)
        assertEquals(4f, YtHoldDirection.speedUp(4f), 0f)
        assertEquals(0.75f, YtHoldDirection.speedDown(1f), 0f)
        assertEquals(1f, YtHoldDirection.speedDown(1.1f), 0f)
        assertEquals(0.25f, YtHoldDirection.speedDown(0.25f), 0f)
    }

    @Test
    fun `actions from settings`() {
        assertEquals(YtHoldAction.FULLSCREEN, YtHoldAction.default(YtHoldDir.UP))
        assertEquals(YtHoldAction.MINIMIZE, YtHoldAction.default(YtHoldDir.DOWN))
        assertEquals(YtHoldAction.CHAPTER_PREV, YtHoldAction.default(YtHoldDir.LEFT))
        assertEquals(YtHoldAction.CHAPTER_NEXT, YtHoldAction.default(YtHoldDir.RIGHT))
        assertEquals(YtHoldAction.SPEED_UP, YtHoldAction.of("speed_up", YtHoldDir.UP))
        assertEquals(YtHoldAction.NONE, YtHoldAction.of("none", YtHoldDir.UP))
        assertEquals(YtHoldAction.MINIMIZE, YtHoldAction.of(null, YtHoldDir.DOWN))
        assertEquals(YtHoldAction.CHAPTER_NEXT, YtHoldAction.of("gone", YtHoldDir.RIGHT))
        // stored values are unique
        assertEquals(YtHoldAction.entries.size, YtHoldAction.entries.map { it.value }.toSet().size)
    }
}
