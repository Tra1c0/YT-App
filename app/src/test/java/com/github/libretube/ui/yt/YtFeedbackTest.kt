package com.github.libretube.ui.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtFeedbackTest {
    @Test
    fun constantsPlayAsTheyAreWhereAndroidKnowsThem() {
        assertEquals(YtFeedback.SEGMENT_TICK, YtFeedback.compat(YtFeedback.SEGMENT_TICK, 34))
        assertEquals(YtFeedback.DRAG_START, YtFeedback.compat(YtFeedback.DRAG_START, 36))
        assertEquals(YtFeedback.CONFIRM, YtFeedback.compat(YtFeedback.CONFIRM, 30))
        assertEquals(YtFeedback.GESTURE_START, YtFeedback.compat(YtFeedback.GESTURE_START, 30))
        assertEquals(YtFeedback.CLOCK_TICK, YtFeedback.compat(YtFeedback.CLOCK_TICK, 26))
    }

    @Test
    fun newerConstantsFallBackToOlderOnes() {
        // Android 11 to 13 know CONFIRM, REJECT and GESTURE_START/END, not the Android 14 ones
        assertEquals(YtFeedback.GESTURE_START, YtFeedback.compat(YtFeedback.DRAG_START, 33))
        assertEquals(YtFeedback.CONTEXT_CLICK, YtFeedback.compat(YtFeedback.GESTURE_THRESHOLD_ACTIVATE, 33))
        assertEquals(YtFeedback.CLOCK_TICK, YtFeedback.compat(YtFeedback.GESTURE_THRESHOLD_DEACTIVATE, 33))
        assertEquals(YtFeedback.CLOCK_TICK, YtFeedback.compat(YtFeedback.SEGMENT_TICK, 33))
        assertEquals(YtFeedback.CLOCK_TICK, YtFeedback.compat(YtFeedback.SEGMENT_FREQUENT_TICK, 30))
        // before Android 11 the confirm of today's YtHaptics: a context click
        assertEquals(YtFeedback.CONTEXT_CLICK, YtFeedback.compat(YtFeedback.CONFIRM, 29))
        assertEquals(YtFeedback.CONTEXT_CLICK, YtFeedback.compat(YtFeedback.DRAG_START, 26))
        assertEquals(YtFeedback.CLOCK_TICK, YtFeedback.compat(YtFeedback.GESTURE_END, 29))
        assertEquals(YtFeedback.LONG_PRESS, YtFeedback.compat(YtFeedback.REJECT, 26))
        assertEquals(YtFeedback.CLOCK_TICK, YtFeedback.compat(YtFeedback.TEXT_HANDLE_MOVE, 26))
        assertEquals(YtFeedback.NO_HAPTICS, YtFeedback.compat(YtFeedback.NO_HAPTICS, 26))
    }

    @Test
    fun everyFallbackOfTheVocabularyPlaysOnEveryVersion() {
        val known = setOf(
            YtFeedback.LONG_PRESS, YtFeedback.VIRTUAL_KEY, YtFeedback.KEYBOARD_TAP,
            YtFeedback.CLOCK_TICK, YtFeedback.CONTEXT_CLICK,
        )
        val textures = listOf(
            YtHapticTexture.GRAB, YtHapticTexture.DETENT, YtHapticTexture.SEGMENT,
            YtHapticTexture.THRESHOLD_ON, YtHapticTexture.THRESHOLD_OFF, YtHapticTexture.RELEASE,
            YtHapticTexture.REJECT, YtHapticTexture.land(0f), YtHapticTexture.land(1f),
        )
        for (texture in textures) {
            // on Android 8 to 10 only the oldest constants exist
            assertTrue(texture.name, YtFeedback.compat(texture.fallback, 26) in known)
        }
    }

    @Test
    fun primitivesComposeFromTheirAndroidVersion() {
        val tick = YtHapticTexture.of("t", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.TICK, 0.5f))
        val thud = YtHapticTexture.of("d", YtFeedback.CLOCK_TICK, YtHapticStep(YtPrimitive.THUD, 0.5f))
        val none = YtHapticTexture.of("n", YtFeedback.CLOCK_TICK)
        assertFalse(tick.composableOn(29))
        assertTrue(tick.composableOn(30))
        // Android 11 refuses THUD, SPIN and LOW_TICK
        assertFalse(thud.composableOn(30))
        assertTrue(thud.composableOn(31))
        assertFalse(none.composableOn(36))
        assertFalse(YtHapticTexture.GRAB.composableOn(30))
        assertTrue(YtHapticTexture.GRAB.composableOn(31))
    }

    @Test
    fun landGetsHeavierWithWeight() {
        val light = YtHapticTexture.land(0f)
        val heavy = YtHapticTexture.land(1f)
        assertTrue(heavy.steps.first().scale > light.steps.first().scale)
        assertEquals(YtFeedback.CONFIRM, heavy.fallback)
        assertEquals(YtFeedback.GESTURE_END, light.fallback)
        // out of range is clamped; every scale stays within 0..1
        for (texture in listOf(YtHapticTexture.land(-3f), YtHapticTexture.land(9f))) {
            assertTrue(texture.steps.all { it.scale in 0f..1f })
        }
    }
}
