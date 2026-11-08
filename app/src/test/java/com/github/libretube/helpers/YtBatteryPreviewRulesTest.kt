package com.github.libretube.helpers

import com.github.libretube.helpers.YtBatteryPreviewRules.Note
import com.github.libretube.helpers.YtBatteryVibeRules.Block as B
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtBatteryPreviewRulesTest {
    private val pause = YtBatteryPreviewRules.PAUSE_MS

    @Test
    fun sequenceRunsTheMarksDownWithAPauseBetween() {
        val steps = YtBatteryPreviewRules.sequence(motion = true, vibrates = false, amplitude = true)
        assertEquals(listOf(20, 15, 10, 5), steps.map { it.mark })
        assertEquals(0L, steps[0].atMs)
        for (i in 1 until steps.size) {
            assertEquals(steps[i - 1].atMs + steps[i - 1].lengthMs + pause, steps[i].atMs)
        }
        // without vibration every level lasts the (strong, longer) picture part
        val strong = YtBatteryCues.totalMs(strong = true)
        assertTrue(steps.all { it.lengthMs == strong })
        assertEquals(4 * strong + 3 * pause, YtBatteryPreviewRules.endMs(steps))
    }

    @Test
    fun motionOffIsTheColourFadeOnly() {
        val steps = YtBatteryPreviewRules.sequence(motion = false, vibrates = false, amplitude = true)
        val fade = YtBatteryCues.colorFadeMs(strong = true)
        assertTrue(steps.all { it.lengthMs == fade })
        assertEquals(fade + pause, steps[1].atMs)
    }

    @Test
    fun theSwellOutlastsThePictureAndDelaysNothingBeforeIt() {
        val five = YtBatteryCues.of(5)!!
        assertEquals(1200L, YtBatteryPreviewRules.vibrationMs(five, amplitude = true))
        assertEquals(1220L, YtBatteryPreviewRules.vibrationMs(five, amplitude = false))
        val steps = YtBatteryPreviewRules.sequence(motion = true, vibrates = true, amplitude = true)
        assertEquals(1200L, steps.last().lengthMs)
        // the short pulses of 20/15/10 % end inside the picture part
        assertTrue(steps.dropLast(1).all { it.lengthMs == YtBatteryCues.TOTAL_MS })
        val plain = YtBatteryPreviewRules.sequence(motion = false, vibrates = true, amplitude = false)
        assertEquals(1220L, plain.last().lengthMs)
    }

    @Test
    fun emptyRunEndsAtOnce() {
        assertEquals(0L, YtBatteryPreviewRules.endMs(emptyList()))
        assertTrue(YtBatteryPreviewRules.sequence(true, true, true, marks = intArrayOf(42)).isEmpty())
    }

    @Test
    fun notesFollowSettingsAndDevice() {

        fun n(motion: Boolean, blocked: YtBatteryVibeRules.Block?, amplitude: Boolean) =
            YtBatteryPreviewRules.notes(motion, blocked, amplitude)
        assertEquals(emptyList<Note>(), n(true, null, true))
        assertEquals(listOf(Note.MOTION_OFF), n(false, null, true))
        assertEquals(listOf(Note.HAPTICS_OFF), n(true, B.HAPTICS_OFF, false))
        assertEquals(listOf(Note.NO_VIBRATOR), n(true, B.NO_VIBRATOR, false))
        assertEquals(listOf(Note.SWELL_AS_PULSES), n(true, null, false))
        assertEquals(listOf(Note.POWER_SAVE), n(true, B.POWER_SAVE, false))
        assertEquals(listOf(Note.SILENT), n(true, B.SILENT, true))
        assertEquals(listOf(Note.SYSTEM_OFF), n(true, B.SYSTEM_OFF, true))
        assertEquals(listOf(Note.MOTION_OFF, Note.HAPTICS_OFF), n(false, B.HAPTICS_OFF, true))
    }

    @Test
    fun vibratesOnlyWhenNothingBlocksIt() {
        assertTrue(YtBatteryPreviewRules.vibrates(null))
        for (block in YtBatteryVibeRules.Block.entries) assertFalse(YtBatteryPreviewRules.vibrates(block))
    }

    @Test
    fun strongPictureWhenItCannotVibrate() {
        val ten = YtBatteryCues.of(10)!!
        assertEquals(YtBatteryCues.totalMs(true), YtBatteryPreviewRules.lengthMs(ten, true, vibrates = false, amplitude = true))
        assertEquals(YtBatteryCues.TOTAL_MS, YtBatteryPreviewRules.lengthMs(ten, true, vibrates = true, amplitude = true))
    }
}
