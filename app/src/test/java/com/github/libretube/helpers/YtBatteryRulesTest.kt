package com.github.libretube.helpers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtBatteryRulesTest {
    private fun active(was: Boolean, level: Int, charging: Boolean = false, powerSave: Boolean = false,
                       enabled: Boolean = true, threshold: Int = 20, usePowerSave: Boolean = true) =
        YtBatteryCapRules.active(was, level, charging, powerSave, enabled, threshold, usePowerSave)

    @Test
    fun capTurnsOnBelowThresholdAndOffWithHysteresis() {
        assertFalse(active(false, 20))
        assertTrue(active(false, 19))
        assertTrue(active(true, 20))
        assertTrue(active(true, 24))
        assertFalse(active(true, 25)) // back at threshold + 5, as the settings text says
        assertFalse(active(false, 22))
        assertTrue(active(false, 29, threshold = 30))
        assertTrue(active(true, 34, threshold = 30))
        assertFalse(active(true, 35, threshold = 30))
        assertFalse(active(true, 21, threshold = 15))
    }

    @Test
    fun capChargingPowerSaveAndSwitch() {
        assertFalse(active(true, 5, charging = true))
        assertTrue(active(false, 80, powerSave = true))
        assertTrue(active(false, 80, charging = true, powerSave = true))
        assertFalse(active(false, 80, powerSave = true, usePowerSave = false))
        assertFalse(active(true, 5, enabled = false))
        assertFalse(active(false, 5, enabled = false, powerSave = true))
        assertFalse(active(false, -1))
    }

    @Test
    fun capHeightAndFrameRate() {
        assertEquals(480, YtBatteryCapRules.height(Int.MAX_VALUE, 480, active = true, exempt = false, short = false))
        assertEquals(360, YtBatteryCapRules.height(360, 480, active = true, exempt = false, short = false))
        assertEquals(1080, YtBatteryCapRules.height(1080, 480, active = false, exempt = false, short = false))
        assertEquals(1080, YtBatteryCapRules.height(1080, 480, active = true, exempt = true, short = false))
        assertEquals(854, YtBatteryCapRules.height(1920, 480, active = true, exempt = false, short = true))
        assertEquals(30, YtBatteryCapRules.maxFrameRate(active = true, exempt = false))
        assertEquals(Int.MAX_VALUE, YtBatteryCapRules.maxFrameRate(active = true, exempt = true))
        assertEquals(Int.MAX_VALUE, YtBatteryCapRules.maxFrameRate(active = false, exempt = false))
        assertFalse(YtBatteryCapRules.isManualPick(true, Int.MAX_VALUE))
        assertTrue(YtBatteryCapRules.isManualPick(true, 720))
        assertFalse(YtBatteryCapRules.isManualPick(false, 720))
        assertEquals(20, YtBatteryCapRules.parsePercent("x", 20))
        assertEquals(480, YtBatteryCapRules.parseHeight("", 480))
        assertEquals(720, YtBatteryCapRules.parseHeight("720", 480))
    }

    @Test
    fun pickByHandIsExemptOnlyForItsOwnHeight() {
        // pick 360p in portrait (the page stores it for fullscreen), then rotate: the page
        // re-sends its 720p default without "by hand" - that must be capped, not pass at 60 fps
        var picked: Int? = YtBatteryCapRules.pickAfter(null, 360, byUser = true)
        assertEquals(360, picked)
        assertTrue(YtBatteryCapRules.exempt(picked, 360))
        picked = YtBatteryCapRules.pickAfter(picked, 720, byUser = false)
        assertFalse(YtBatteryCapRules.exempt(picked, 720))
        val exempt720 = YtBatteryCapRules.exempt(picked, 720)
        assertEquals(480, YtBatteryCapRules.height(720, 480, active = true, exempt = exempt720, short = false))
        assertEquals(30, YtBatteryCapRules.maxFrameRate(active = true, exempt = exempt720))
        // rotating back re-sends the pick: exempt again
        picked = YtBatteryCapRules.pickAfter(picked, 360, byUser = false)
        assertTrue(YtBatteryCapRules.exempt(picked, 360))
        assertEquals(Int.MAX_VALUE, YtBatteryCapRules.maxFrameRate(active = true, exempt = true))
        // a high pick by hand plays uncapped
        picked = YtBatteryCapRules.pickAfter(picked, 1080, byUser = true)
        assertEquals(1080, YtBatteryCapRules.height(1080, 480, true, YtBatteryCapRules.exempt(picked, 1080), false))
        // "Auto" by hand clears the pick
        picked = YtBatteryCapRules.pickAfter(picked, Int.MAX_VALUE, byUser = true)
        assertNull(picked)
        assertFalse(YtBatteryCapRules.exempt(picked, Int.MAX_VALUE))
        assertFalse(YtBatteryCapRules.exempt(null, 360))
    }

    @Test
    fun shortsPlayerCap() {
        assertEquals(854, YtBatteryCapRules.shortsMaxSide(480, active = true))
        assertEquals(640, YtBatteryCapRules.shortsMaxSide(360, active = true))
        assertEquals(1280, YtBatteryCapRules.shortsMaxSide(720, active = true))
        assertEquals(Int.MAX_VALUE, YtBatteryCapRules.shortsMaxSide(480, active = false))
        // the same widening as the service's shorts request
        assertEquals(
            YtBatteryCapRules.height(Int.MAX_VALUE, 480, active = true, exempt = false, short = true),
            YtBatteryCapRules.shortsMaxSide(480, active = true),
        )
    }

    @Test
    fun vibrationBlockedAndStrongVisual() {
        fun b(haptics: Boolean = true, vib: Boolean = true, ps: Boolean = false, silent: Boolean = false, off: Boolean = false) =
            YtBatteryVibeRules.blocked(haptics, vib, ps, silent, off)
        assertNull(b())
        assertEquals(YtBatteryVibeRules.Block.HAPTICS_OFF, b(haptics = false, ps = true))
        assertEquals(YtBatteryVibeRules.Block.NO_VIBRATOR, b(vib = false, ps = true))
        assertEquals(YtBatteryVibeRules.Block.POWER_SAVE, b(ps = true, silent = true))
        assertEquals(YtBatteryVibeRules.Block.SILENT, b(silent = true, off = true))
        assertEquals(YtBatteryVibeRules.Block.SYSTEM_OFF, b(off = true))
        assertFalse(YtBatteryVibeRules.strongVisual(null))
        assertTrue(YtBatteryVibeRules.strongVisual(YtBatteryVibeRules.Block.POWER_SAVE))
    }

    @Test
    fun strongCueHoldsLongerAndInsetsDeeper() {
        assertEquals(YtBatteryCues.TOTAL_MS, YtBatteryCues.totalMs(false))
        assertEquals(YtBatteryCues.TOTAL_MS + 400L, YtBatteryCues.totalMs(true))
        assertEquals(YtBatteryCues.COLOR_FADE_MS + 400L, YtBatteryCues.colorFadeMs(true))
        assertTrue(YtBatteryCues.insetScale(true) < YtBatteryCues.insetScale(false))
        val endOfNormalHold = YtBatteryCues.IN_MS + YtBatteryCues.HOLD_MS + 100
        assertTrue(YtBatteryCues.inset(endOfNormalHold) < 1f)
        assertEquals(1f, YtBatteryCues.inset(endOfNormalHold, strong = true))
        assertEquals(0f, YtBatteryCues.inset(YtBatteryCues.totalMs(true), strong = true))
        assertEquals(1f, YtBatteryCues.colorFraction(YtBatteryCues.colorFadeMs(true), strong = true))
    }

    @Test
    fun marksFireOncePerFall() {
        val m = YtBatteryMarks()
        assertNull(m.observe(30, false, true)) // first reading only takes note
        assertNull(m.observe(21, false, true))
        assertEquals(20, m.observe(20, false, true))
        assertNull(m.observe(19, false, true))
        assertNull(m.observe(21, false, true)) // not far enough up to come back
        assertNull(m.observe(20, false, true))
        assertNull(m.observe(16, false, true))
        assertEquals(15, m.observe(15, false, true))
        assertEquals(10, m.observe(10, false, true))
        assertEquals(5, m.observe(4, false, true))
        assertNull(m.observe(3, false, true))
    }

    @Test
    fun marksComeBackUpwardsAndWhenCharging() {
        val m = YtBatteryMarks()
        m.observe(25, false, true)
        assertEquals(20, m.observe(20, false, true))
        assertNull(m.observe(23, false, true)) // 3 above: 20 comes back
        assertEquals(20, m.observe(20, false, true))
        assertNull(m.observe(20, true, true)) // charging
        assertNull(m.observe(18, false, true)) // charger pulled: first reading only notes
        assertEquals(15, m.observe(15, false, true))
    }

    @Test
    fun marksQuietWhileHiddenAndAfterPause() {
        val m = YtBatteryMarks()
        m.observe(25, false, true)
        assertNull(m.observe(20, false, false)) // in picture-in-picture: noted, not shown
        assertNull(m.observe(19, false, true))
        m.pause()
        assertNull(m.observe(14, false, true)) // 15 passed while hidden
        assertEquals(10, m.observe(10, false, true))
    }

    @Test
    fun jumpShowsOnlyTheLowestMark() {
        val m = YtBatteryMarks()
        m.observe(22, false, true)
        assertEquals(10, m.observe(9, false, true))
        assertNull(m.observe(8, false, true))
        assertEquals(5, m.observe(5, false, true))
    }

    @Test
    fun cueTables() {
        val marks = YtBatteryCues.MARKS
        assertArrayEquals(intArrayOf(20, 15, 10, 5), marks)
        for (mark in marks) {
            val cue = YtBatteryCues.of(mark)!!
            assertEquals(cue.timings.size, cue.amplitudes.size)
            assertTrue(cue.amplitudes.all { it in 0..255 })
            assertTrue(cue.timings.all { it > 0 })
            assertEquals(0L, cue.plainTimings[0]) // starts at once
            assertTrue(cue.plainTimings.size % 2 == 0) // ends on a pulse
        }
        val c20 = YtBatteryCues.of(20)!!
        val c15 = YtBatteryCues.of(15)!!
        val c10 = YtBatteryCues.of(10)!!
        val c5 = YtBatteryCues.of(5)!!
        // 20: one pulse longer than touch feedback (~10-30 ms)
        assertEquals(1, c20.timings.size)
        assertTrue(c20.timings[0] >= 100)
        // 15: two pulses, each as long as the one of 20
        assertEquals(listOf(c20.timings[0], c20.timings[0]), c15.timings.filterIndexed { i, _ -> c15.amplitudes[i] > 0 })
        // 10: short, short, long
        val on10 = c10.timings.filterIndexed { i, _ -> c10.amplitudes[i] > 0 }
        assertEquals(3, on10.size)
        assertEquals(on10[0], on10[1])
        assertTrue(on10[2] > 3 * on10[0])
        // 5: a swell up and down, symmetric, peak in the middle
        val a = c5.amplitudes
        assertArrayEquals(a, a.reversedArray())
        for (i in 1 until a.size / 2) assertTrue(a[i] >= a[i - 1])
        assertEquals(255, a.max())
        // 5 without amplitude control: gaps shrink, then grow
        val gaps = c5.plainTimings.filterIndexed { i, _ -> i % 2 == 0 && i > 0 }
        assertEquals(gaps, gaps.reversed())
        for (i in 1..gaps.size / 2) assertTrue(gaps[i] <= gaps[i - 1])
    }

    @Test
    fun colourChain() {
        assertEquals(YtBatteryCues.COLOR_FULL, YtBatteryCues.of(20)!!.startColor)
        assertEquals(YtBatteryCues.COLOR_20, YtBatteryCues.of(20)!!.endColor)
        assertEquals(YtBatteryCues.COLOR_20, YtBatteryCues.of(15)!!.startColor)
        assertEquals(YtBatteryCues.COLOR_15, YtBatteryCues.of(15)!!.endColor)
        assertEquals(YtBatteryCues.COLOR_15, YtBatteryCues.of(10)!!.startColor)
        assertEquals(YtBatteryCues.COLOR_10, YtBatteryCues.of(10)!!.endColor)
        assertEquals(YtBatteryCues.COLOR_10, YtBatteryCues.of(5)!!.startColor)
        assertEquals(YtBatteryCues.COLOR_5, YtBatteryCues.of(5)!!.endColor)
        // each cue starts in the colour the one before ends in
        for (i in 1 until YtBatteryCues.MARKS.size) {
            assertEquals(
                YtBatteryCues.of(YtBatteryCues.MARKS[i - 1])!!.endColor,
                YtBatteryCues.of(YtBatteryCues.MARKS[i])!!.startColor,
            )
        }
    }

    @Test
    fun blendEndsAndHuePath() {
        for (c in listOf(YtBatteryCues.COLOR_FULL, YtBatteryCues.COLOR_20, YtBatteryCues.COLOR_10, YtBatteryCues.COLOR_5)) {
            val back = YtBatteryCues.blend(c, c, 0.5f)
            for (shift in listOf(0, 8, 16)) {
                assertTrue(kotlin.math.abs((back shr shift and 0xFF) - (c shr shift and 0xFF)) <= 1)
            }
        }
        val start = YtBatteryCues.blend(YtBatteryCues.COLOR_10, YtBatteryCues.COLOR_5, 0f)
        val end = YtBatteryCues.blend(YtBatteryCues.COLOR_10, YtBatteryCues.COLOR_5, 1f)
        assertTrue(close(start, YtBatteryCues.COLOR_10))
        assertTrue(close(end, YtBatteryCues.COLOR_5))
        // green to yellow-green passes hues in between (not through blue)
        val mid = YtBatteryCues.hsv(YtBatteryCues.blend(YtBatteryCues.COLOR_FULL, YtBatteryCues.COLOR_20, 0.5f))[0]
        val h0 = YtBatteryCues.hsv(YtBatteryCues.COLOR_FULL)[0]
        val h1 = YtBatteryCues.hsv(YtBatteryCues.COLOR_20)[0]
        assertTrue(mid < h0 && mid > h1)
    }

    @Test
    fun insetTimeline() {
        assertEquals(0f, YtBatteryCues.inset(0), 0f)
        assertEquals(1f, YtBatteryCues.inset(YtBatteryCues.IN_MS), 0f)
        assertEquals(1f, YtBatteryCues.inset(YtBatteryCues.IN_MS + YtBatteryCues.HOLD_MS - 1), 0f)
        assertEquals(0f, YtBatteryCues.inset(YtBatteryCues.TOTAL_MS), 0f)
        assertTrue(YtBatteryCues.inset(YtBatteryCues.IN_MS / 2) in 0.4f..0.6f)
        assertEquals(1f, YtBatteryCues.colorFraction(YtBatteryCues.COLOR_FADE_MS), 0f)
        assertEquals(0f, YtBatteryCues.colorFraction(0), 0f)
    }

    private fun close(a: Int, b: Int): Boolean = listOf(0, 8, 16).all {
        kotlin.math.abs((a shr it and 0xFF) - (b shr it and 0xFF)) <= 1
    }
}
