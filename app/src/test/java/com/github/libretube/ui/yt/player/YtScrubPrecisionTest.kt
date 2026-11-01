package com.github.libretube.ui.yt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtScrubPrecisionTest {
    // lines at 40, 90, 140 and 190 px, 8 px hysteresis, a frame per 12 px, a 6 px magnet, cancel 48 px below
    private fun engine(levels: Boolean = true) = YtScrubPrecision(
        levelStartsPx = floatArrayOf(40f, 90f, 140f, 190f),
        hysteresisPx = 8f,
        framePx = 12f,
        magnetPx = 6f,
        cancelBelowPx = 48f,
        levels = levels,
    )

    // a 1000 px bar for 1000 s: 1 px is 1 s at the bar
    private fun started(
        startMs: Long = 300_000,
        originMs: Long = 100_000,
        heightPx: Float = 0f,
        frameMs: Double = 40.0,
        chapters: LongArray = LongArray(0),
        levels: Boolean = true,
    ) = engine(levels).apply { start(startMs, originMs, 1_000_000, 1000f, frameMs, heightPx, chapters) }

    @Test
    fun theLevelFollowsTheHeightAboveTheBar() {
        val e = started()
        assertEquals(YtScrubLevel.FULL, e.level)
        e.move(0f, 50f)
        assertEquals(YtScrubLevel.HALF, e.level)
        e.move(0f, 100f)
        assertEquals(YtScrubLevel.QUARTER, e.level)
        e.move(0f, 150f)
        assertEquals(YtScrubLevel.EIGHTH, e.level)
        e.move(0f, 200f)
        assertEquals(YtScrubLevel.FRAME, e.level)
        e.move(0f, 10f)
        assertEquals(YtScrubLevel.FULL, e.level)
        // a jump straight to the top goes through all lines at once
        e.move(0f, 400f)
        assertEquals(YtScrubLevel.FRAME, e.level)
    }

    @Test
    fun aFingerOnALineDoesNotFlicker() {
        val e = started()
        // up to the line and a little past: still 1x until half the hysteresis is passed
        assertFalse(e.move(0f, 42f).levelChanged)
        assertEquals(YtScrubLevel.FULL, e.level)
        assertTrue(e.move(0f, 44f).levelChanged)
        assertEquals(YtScrubLevel.HALF, e.level)
        // wobbling around the line keeps the level
        for (h in listOf(39f, 41f, 37f, 43f, 38f)) assertFalse(e.move(0f, h).levelChanged)
        assertEquals(YtScrubLevel.HALF, e.level)
        assertTrue(e.move(0f, 35f).levelChanged)
        assertEquals(YtScrubLevel.FULL, e.level)
    }

    @Test
    fun theHigherTheFingerTheSlowerThePosition() {
        val e = started()
        val p0 = e.positionMs
        e.move(100f, 0f)
        val coarse = e.positionMs - p0
        assertEquals(100_000.0, coarse, 0.001)
        e.move(0f, 150f)
        val p1 = e.positionMs
        e.move(100f, 150f)
        assertEquals(coarse / 8, e.positionMs - p1, 0.001)
        e.move(0f, 60f)
        val p2 = e.positionMs
        e.move(-100f, 60f)
        assertEquals(-coarse / 2, e.positionMs - p2, 0.001)
    }

    @Test
    fun changingLevelNeverJumps() {
        val e = started()
        e.move(37f, 0f)
        for (h in listOf(60f, 110f, 160f, 120f, 70f, 10f)) {
            val before = e.positionMs
            e.move(0f, h)
            assertEquals("at $h px", before, e.positionMs, 0.001)
        }
        // into the frames and out of them: at most half a frame off, onto the frame grid
        val before = e.positionMs
        e.move(0f, 250f)
        assertEquals(YtScrubLevel.FRAME, e.level)
        assertTrue(kotlin.math.abs(e.positionMs - before) <= 20.0)
        assertEquals(0.0, e.positionMs % 40.0, 0.0001)
        val inFrames = e.positionMs
        e.move(0f, 0f)
        assertEquals(inFrames, e.positionMs, 0.001)
    }

    @Test
    fun framesStepEveryTwelvePxAndSnapToTheGrid() {
        val e = started(startMs = 300_010)
        e.move(0f, 250f)
        assertEquals(300_000.0, e.positionMs, 0.001)
        assertEquals(0L, e.frameOffset)
        assertEquals(0L, e.move(11f, 250f).frames)
        assertEquals(1L, e.move(1f, 250f).frames)
        assertEquals(300_040.0, e.positionMs, 0.001)
        // three frames at once, then back past the start
        assertEquals(3L, e.move(36f, 250f).frames)
        assertEquals(4L, e.frameOffset)
        assertEquals(-6L, e.move(-72f, 250f).frames)
        assertEquals(-2L, e.frameOffset)
        assertEquals(299_920.0, e.positionMs, 0.001)
        // the seek lands between the frame before and the frame, so the frame itself shows
        assertEquals(299_900L, e.seekTarget())
    }

    @Test
    fun frameSeeksLandHalfAFrameEarly() {
        assertEquals(0L, YtScrubPrecision.frameSeekMs(0, 1000.0 / 30))
        assertEquals(16L, YtScrubPrecision.frameSeekMs(1, 1000.0 / 30))
        assertEquals(50L, YtScrubPrecision.frameSeekMs(2, 1000.0 / 30))
        // 29.97 fps, frame 3 at 100.1 ms
        assertEquals(83L, YtScrubPrecision.frameSeekMs(3, 1001.0 / 30))
        assertEquals(1000.0 / 24, YtScrubPrecision.frameMsFor(24f), 0.0001)
        assertEquals(YtScrubPrecision.DEFAULT_FRAME_MS, YtScrubPrecision.frameMsFor(-1f), 0.0001)
        assertEquals(YtScrubPrecision.DEFAULT_FRAME_MS, YtScrubPrecision.frameMsFor(Float.NaN), 0.0001)
    }

    @Test
    fun backAtTheStartLettingGoChangesNothing() {
        // the scrub begins 3 px right of where the video was
        val e = started(startMs = 103_000, originMs = 100_000)
        assertTrue(e.atOrigin)
        assertEquals(100_000.0, e.positionMs, 0.001)
        assertNull(e.seekTarget())
        assertEquals(-1, e.move(10f, 0f).magnet)
        // out of the hold it goes on from the start, without jumping by the magnet's radius
        assertEquals(107_000L, e.seekTarget())
        // and back: the magnet pulls it in and holds it exactly at the start
        assertEquals(1, e.move(-12f, 0f).magnet)
        assertEquals(100_000.0, e.positionMs, 0.001)
        assertNull(e.seekTarget())
        assertEquals(100_000L, e.liveTarget())
    }

    @Test
    fun theMagnetLetsGoWithoutAJump() {
        val e = started(startMs = 100_000, originMs = 100_000)
        e.move(4f, 0f)
        assertEquals(100_000.0, e.positionMs, 0.001)
        e.move(4f, 0f)
        // never more than the finger's own step (4 px = 4 s here) away from where it was
        assertEquals(102_000.0, e.positionMs, 0.001)
        e.move(4f, 0f)
        assertEquals(106_000.0, e.positionMs, 0.001)
    }

    @Test
    fun theMagnetHoldsThroughALevelChange() {
        val e = started(startMs = 102_000, originMs = 100_000)
        assertTrue(e.atOrigin)
        e.move(0f, 150f)
        assertTrue(e.atOrigin)
        // finer now: the position goes on from the start itself, past the (finer) magnet
        e.move(100f, 150f)
        assertEquals(111_750.0, e.positionMs, 0.001)
        // and from there by the deltas, without a jump
        e.move(8f, 150f)
        assertEquals(112_750.0, e.positionMs, 0.001)
    }

    @Test
    fun farBelowTheBarLettingGoCancels() {
        val e = started()
        e.move(50f, 0f)
        assertEquals(1, e.move(0f, -60f).cancel)
        assertTrue(e.cancelling)
        assertNull(e.seekTarget())
        // the bar shows where the video was; moving sideways down there does nothing
        assertEquals(100_000.0, e.positionMs, 0.001)
        e.move(200f, -60f)
        // back up: where the finger left off
        assertEquals(-1, e.move(0f, -30f).cancel)
        assertEquals(350_000L, e.seekTarget())
    }

    @Test
    fun theEndsHoldTheTicksOnce() {
        val e = started(startMs = 995_000, originMs = 0)
        assertTrue(e.move(10f, 0f).limit)
        assertEquals(1_000_000.0, e.positionMs, 0.001)
        assertFalse(e.move(10f, 0f).limit)
        assertFalse(e.move(-10f, 0f).limit)
        assertEquals(990_000.0, e.positionMs, 0.001)
        // in the frames too
        val f = started(startMs = 40, originMs = 500_000)
        f.move(0f, 250f)
        assertTrue(f.move(-30f, 250f).limit)
        assertEquals(0.0, f.positionMs, 0.001)
        assertEquals(0L, f.seekTarget())
    }

    @Test
    fun chapterStartsAreFelt() {
        val e = started(startMs = 295_000, originMs = 0, chapters = longArrayOf(0, 300_000, 600_000))
        assertFalse(e.move(4f, 0f).chapter)
        assertTrue(e.move(2f, 0f).chapter)
        assertFalse(e.move(2f, 0f).chapter)
        assertTrue(e.move(-10f, 0f).chapter)
    }

    @Test
    fun withoutLevelsItStaysAtTheBarsSpeed() {
        val e = started(levels = false)
        e.move(0f, 300f)
        assertEquals(YtScrubLevel.FULL, e.level)
        val p = e.positionMs
        e.move(10f, 300f)
        assertEquals(10_000.0, e.positionMs - p, 0.001)
    }

    @Test
    fun aScrubMayBeginHighAboveTheBar() {
        val e = started(heightPx = 160f)
        assertEquals(YtScrubLevel.EIGHTH, e.level)
        val f = started(heightPx = 260f, startMs = 300_010)
        assertEquals(YtScrubLevel.FRAME, f.level)
        assertEquals(300_000.0, f.positionMs, 0.001)
    }

    @Test
    fun theRulerScalesWithTheLevel() {
        val e = started()
        assertEquals(0.001, e.pxPerMs, 1e-9)
        e.move(0f, 150f)
        assertEquals(0.008, e.pxPerMs, 1e-9)
        e.move(0f, 250f)
        assertEquals(12.0 / 40.0, e.pxPerMs, 1e-9)
        // the smallest step at least 8 px apart
        assertEquals(10_000.0, YtScrubPrecision.rulerStepMs(0.001, 8.0), 0.0)
        assertEquals(1_000.0, YtScrubPrecision.rulerStepMs(0.008, 8.0), 0.0)
        assertEquals(40.0, YtScrubPrecision.rulerStepMs(0.3, 8.0, frameMs = 40.0), 0.0)
        assertEquals(50.0, YtScrubPrecision.rulerStepMs(0.3, 14.0, frameMs = 40.0), 0.0)
    }

    @Test
    fun theLensSaysAsManyDecimalsAsTheFingerCanSet() {
        // 10 min on 1000 px: 600 ms per px at the bar, 75 at an eighth
        assertEquals(0, YtScrubPrecision.decimalsFor(600.0))
        assertEquals(1, YtScrubPrecision.decimalsFor(75.0))
        assertEquals(2, YtScrubPrecision.decimalsFor(4.0))
        assertEquals(2, YtScrubPrecision.decimalsFor(0.01))
        assertEquals(0, YtScrubPrecision.decimalsFor(0.0))
    }

    @Test
    fun timesReadAsOnTheClock() {
        assertEquals("12:03", YtScrubPrecision.formatTime(723_999.0, 0))
        assertEquals("12:03.9", YtScrubPrecision.formatTime(723_999.0, 1))
        assertEquals("12:03.233", YtScrubPrecision.formatTime(723_233.4, 3))
        assertEquals("0:07.25", YtScrubPrecision.formatTime(7_250.0, 2))
        assertEquals("1:02:03", YtScrubPrecision.formatTime(3_723_000.0, 0))
        assertEquals("0:05:00", YtScrubPrecision.formatTime(300_000.0, 0, withHours = true))
        assertEquals("0:00", YtScrubPrecision.formatTime(-5.0, 0))
        assertEquals("+7", YtScrubPrecision.formatFrames(7))
        assertEquals("−3", YtScrubPrecision.formatFrames(-3))
        assertEquals("±0", YtScrubPrecision.formatFrames(0))
    }

    @Test
    fun frameModeKnowsOnlySaneFrameLengths() {
        val e = started(frameMs = 0.5)
        e.move(0f, 250f)
        assertEquals(YtScrubPrecision.DEFAULT_FRAME_MS, e.frameMs, 0.0001)
    }
}
