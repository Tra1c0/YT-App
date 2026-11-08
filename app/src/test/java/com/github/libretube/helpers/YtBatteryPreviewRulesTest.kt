package com.github.libretube.helpers

import com.github.libretube.helpers.YtBatteryPreviewRules.Note
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
        // without vibration every level lasts the picture part
        assertTrue(steps.all { it.lengthMs == YtBatteryCues.TOTAL_MS })
        assertEquals(4 * YtBatteryCues.TOTAL_MS + 3 * pause, YtBatteryPreviewRules.endMs(steps))
    }

    @Test
    fun motionOffIsTheColourFadeOnly() {
        val steps = YtBatteryPreviewRules.sequence(motion = false, vibrates = false, amplitude = true)
        assertTrue(steps.all { it.lengthMs == YtBatteryCues.COLOR_FADE_MS })
        assertEquals(YtBatteryCues.COLOR_FADE_MS + pause, steps[1].atMs)
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
        assertEquals(emptyList<Note>(), YtBatteryPreviewRules.notes(true, true, true, true))
        assertEquals(listOf(Note.MOTION_OFF), YtBatteryPreviewRules.notes(false, true, true, true))
        assertEquals(listOf(Note.HAPTICS_OFF), YtBatteryPreviewRules.notes(true, false, false, false))
        assertEquals(listOf(Note.NO_VIBRATOR), YtBatteryPreviewRules.notes(true, true, false, false))
        assertEquals(listOf(Note.SWELL_AS_PULSES), YtBatteryPreviewRules.notes(true, true, true, false))
        assertEquals(
            listOf(Note.MOTION_OFF, Note.HAPTICS_OFF),
            YtBatteryPreviewRules.notes(false, false, true, true),
        )
    }

    @Test
    fun vibratesOnlyWithHapticsAndAVibrator() {
        assertTrue(YtBatteryPreviewRules.vibrates(true, true))
        assertFalse(YtBatteryPreviewRules.vibrates(false, true))
        assertFalse(YtBatteryPreviewRules.vibrates(true, false))
    }
}
