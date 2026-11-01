package com.github.libretube.ui.yt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtLoopPlanTest {
    @Test
    fun marksInEitherOrderMakeTheSameLoop() {
        assertEquals(YtLoop(10_000, 20_000), YtLoopPlan.of(20_000, 10_000, 180_000))
        assertEquals(YtLoop(10_000, 20_000), YtLoopPlan.of(10_000, 20_000, 180_000))
    }

    @Test
    fun tooShortIsNoLoop() {
        assertNull(YtLoopPlan.of(10_000, 10_500, 180_000))
        assertNull(YtLoopPlan.of(10_000, 10_000, 180_000))
    }

    @Test
    fun bStaysOffTheEnd() {
        val loop = YtLoopPlan.of(170_000, 180_000, 180_000)!!
        assertEquals(180_000 - YtLoopPlan.END_MARGIN_MS, loop.bMs)
        // unknown duration: only the start is clamped
        assertEquals(YtLoop(0, 5_000), YtLoopPlan.of(-300, 5_000, 0))
        // clamped into nothing
        assertNull(YtLoopPlan.of(179_500, 182_000, 180_000))
    }

    @Test
    fun aBrokenSpeedBecomesOne() {
        assertEquals(1f, YtLoopPlan.clamp(YtLoop(0, 5_000, Float.NaN), 0)!!.speed)
        assertEquals(1f, YtLoopPlan.clamp(YtLoop(0, 5_000, 0f), 0)!!.speed)
    }

    @Test
    fun theChapterAtThePosition() {
        val starts = longArrayOf(90_000, 0, 45_000, 135_000)
        assertEquals(YtLoop(45_000, 90_000), YtLoopPlan.chapter(60_000, starts, 180_000))
        assertEquals(YtLoop(0, 45_000), YtLoopPlan.chapter(0, starts, 180_000))
        // the last chapter runs to the end, B kept off it
        assertEquals(YtLoop(135_000, 179_800), YtLoopPlan.chapter(170_000, starts, 180_000))
        // before the first chapter
        assertEquals(YtLoop(0, 10_000), YtLoopPlan.chapter(3_000, longArrayOf(10_000), 60_000))
        assertNull(YtLoopPlan.chapter(3_000, LongArray(0), 60_000))
        assertNull(YtLoopPlan.chapter(3_000, starts, 0))
    }

    @Test
    fun anEditKeepsTheSettings() {
        val current = YtLoop(10_000, 20_000, 0.5f, 4_000)
        assertEquals(YtLoop(10_000, 30_000, 0.5f, 4_000), YtLoopPlan.edited(current, 10_000, 30_000, 180_000, 1f))
        // dragged past the other end: the ends swap
        assertEquals(YtLoop(5_000, 10_000, 0.5f, 4_000), YtLoopPlan.edited(current, 10_000, 5_000, 180_000, 1f))
        // a new loop from the bar takes the speed it was given
        assertEquals(YtLoop(10_000, 20_000, 1.5f, 0), YtLoopPlan.edited(null, 10_000, 20_000, 180_000, 1.5f))
    }

    @Test
    fun aScrubOutOfTheLoopLeavesIt() {
        val loop = YtLoop(10_000, 20_000)
        assertFalse(YtLoopPlan.leaves(loop, 15_000))
        assertFalse(YtLoopPlan.leaves(loop, 9_700))
        assertFalse(YtLoopPlan.leaves(loop, 20_400))
        assertTrue(YtLoopPlan.leaves(loop, 25_000))
        assertTrue(YtLoopPlan.leaves(loop, 2_000))
    }

    @Test
    fun theNearestChoices() {
        assertEquals(0.75f, YtLoopPlan.nearestSpeed(0.8f))
        assertEquals(1f, YtLoopPlan.nearestSpeed(1.5f))
        assertEquals(4_000L, YtLoopPlan.nearestPause(3_500))
        assertEquals(0L, YtLoopPlan.nearestPause(0))
    }

    @Test
    fun rememberPutsTheNewestFirstAndKeepsFour() {
        var saved = emptyList<YtLoop>()
        for (i in 0 until 6) saved = YtLoopPlan.remember(saved, YtLoop(i * 10_000L, i * 10_000L + 5_000))
        assertEquals(YtLoopPlan.KEEP, saved.size)
        assertEquals(50_000L, saved.first().aMs)
        // the same passage again replaces the old one and moves up
        saved = YtLoopPlan.remember(saved, YtLoop(30_500, 35_200, 0.5f))
        assertEquals(YtLoop(30_500, 35_200, 0.5f), saved.first())
        assertEquals(1, saved.count { YtLoopPlan.samePassage(it, YtLoop(30_000, 35_000)) })
    }

    @Test
    fun encodeAndDecode() {
        val loops = listOf(YtLoop(1_000, 9_000, 0.75f, 2_000), YtLoop(20_000, 40_000, 1f, 0))
        assertEquals(loops, YtLoopPlan.decode(YtLoopPlan.encode(loops)))
        assertEquals(emptyList<YtLoop>(), YtLoopPlan.decode(null))
        assertEquals(emptyList<YtLoop>(), YtLoopPlan.decode(""))
        // broken parts are left out, the rest stays
        assertEquals(listOf(YtLoop(1_000, 9_000, 0.5f, 0)), YtLoopPlan.decode("x:1;1000:9000:50:0;5:5:100:0;1:9000:1000:0"))
    }

    @Test
    fun speedLabels() {
        assertEquals("1×", YtLoopPlan.formatSpeed(1f))
        assertEquals("0.75×", YtLoopPlan.formatSpeed(0.75f))
        assertEquals("0,5×", YtLoopPlan.formatSpeed(0.5f, ','))
        assertEquals("1.25×", YtLoopPlan.formatSpeed(1.25f))
        assertEquals("0.05×", YtLoopPlan.formatSpeed(0.05f))
    }

    @Test
    fun pauseCountdown() {
        assertEquals(4, YtLoopPlan.secondsLeft(10_000, 6_001))
        assertEquals(1, YtLoopPlan.secondsLeft(10_000, 9_999))
        assertEquals(0, YtLoopPlan.secondsLeft(10_000, 10_000))
        assertEquals(0.5f, YtLoopPlan.pauseLeft(10_000, 4_000, 8_000), 1e-6f)
        assertEquals(0f, YtLoopPlan.pauseLeft(0, 4_000, 8_000), 0f)
        assertEquals(1f, YtLoopPlan.pauseLeft(10_000, 4_000, 1_000), 0f)
    }

    @Test
    fun withoutAPauseBGoesBackToA() {
        val run = YtLoopRun(YtLoop(10_000, 20_000))
        assertEquals(YtLoopAction.SEEK_A, run.atB(1_000))
        assertEquals(1, run.passes)
        assertFalse(run.pausing)
    }

    @Test
    fun theMessageAndTheTickCountOneEnd() {
        val run = YtLoopRun(YtLoop(10_000, 20_000))
        assertEquals(YtLoopAction.SEEK_A, run.atB(1_000))
        // the tick saw the same end (the seek back was not read yet)
        assertEquals(YtLoopAction.NONE, run.tick(20_300, 1_100))
        assertEquals(1, run.passes)
        // a pass later it counts again
        assertEquals(YtLoopAction.SEEK_A, run.atB(11_000))
        assertEquals(2, run.passes)
    }

    @Test
    fun aJumpPastBEndsThePass() {
        val run = YtLoopRun(YtLoop(10_000, 20_000))
        assertEquals(YtLoopAction.NONE, run.tick(15_000, 1_000))
        assertEquals(YtLoopAction.NONE, run.tick(20_200, 1_250))
        assertEquals(YtLoopAction.SEEK_A, run.tick(26_000, 1_500))
        assertEquals(1, run.passes)
    }

    @Test
    fun thePauseHoldsThenResumes() {
        val run = YtLoopRun(YtLoop(10_000, 20_000, pauseMs = 4_000))
        assertEquals(YtLoopAction.PAUSE, run.atB(1_000))
        assertTrue(run.pausing)
        assertEquals(5_000L, run.pauseEndsAt)
        // held at B: neither the position past B nor B again count while it thinks
        assertEquals(YtLoopAction.NONE, run.tick(20_400, 2_000))
        assertEquals(YtLoopAction.NONE, run.atB(3_000))
        assertEquals(YtLoopAction.RESUME, run.tick(20_000, 5_000))
        assertFalse(run.pausing)
        assertEquals(1, run.passes)
        // a resume that comes late changes nothing
        assertEquals(YtLoopAction.NONE, run.resume())
    }

    @Test
    fun pressingPlayEndsThePause() {
        val run = YtLoopRun(YtLoop(10_000, 20_000, pauseMs = 8_000))
        run.atB(1_000)
        assertEquals(YtLoopAction.RESUME, run.resume())
        assertEquals(YtLoopAction.NONE, run.tick(10_100, 1_500))
    }

    @Test
    fun anUpdateWithoutAPauseEndsTheHold() {
        val run = YtLoopRun(YtLoop(10_000, 20_000, pauseMs = 8_000))
        run.atB(1_000)
        assertEquals(YtLoopAction.RESUME, run.update(YtLoop(10_000, 20_000, pauseMs = 0)))
        assertFalse(run.pausing)
        assertEquals(1, run.passes)
        // with a pause still there, nothing changes
        assertEquals(YtLoopAction.NONE, run.update(YtLoop(10_000, 20_000, pauseMs = 2_000)))
    }

    @Test
    fun statusPass() {
        val status = YtLoopStatus(YtLoop(0, 5_000), "id", passes = 2, pauseEndsAt = 0)
        assertEquals(3, status.pass)
        assertTrue(status.active)
        assertFalse(status.pausing)
        assertFalse(YtLoopStatus().active)
    }
}
