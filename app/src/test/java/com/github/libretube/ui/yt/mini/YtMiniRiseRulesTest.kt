package com.github.libretube.ui.yt.mini

import androidx.constraintlayout.motion.widget.MotionScene
import com.github.libretube.ui.yt.mini.YtMiniRiseRules.Curve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMiniRiseRulesTest {
    @Test
    fun risesOnlyWhenRestoredWithMotion() {
        assertTrue(YtMiniRiseRules.rises(restored = true, motionEnabled = true))
        assertFalse(YtMiniRiseRules.rises(restored = true, motionEnabled = false))
        assertFalse(YtMiniRiseRules.rises(restored = false, motionEnabled = true))
    }

    @Test
    fun startsBelowTheWholeBox() {
        assertEquals(200f + 8f * 2.5f, YtMiniRiseRules.from(200, 2.5f), 0.001f)
        assertEquals(0f, YtMiniRiseRules.from(0, 2.5f), 0f)
    }

    @Test
    fun slabGrowsWithTheRise() {
        val from = 100f
        assertEquals(0f, YtMiniRiseRules.slab(100f, from, 40f), 0.001f)
        assertEquals(20f, YtMiniRiseRules.slab(50f, from, 40f), 0.001f)
        assertEquals(40f, YtMiniRiseRules.slab(0f, from, 40f), 0.001f)
        // an overshoot above the place keeps the full slab, a start below the hidden line none
        assertEquals(40f, YtMiniRiseRules.slab(-10f, from, 40f), 0.001f)
        assertEquals(0f, YtMiniRiseRules.slab(150f, from, 40f), 0.001f)
        assertEquals(1f, YtMiniRiseRules.share(30f, 0f), 0f)
    }

    @Test
    fun curvesFollowMotionScene() {
        assertEquals(Curve.EASE_IN_OUT, YtMiniRiseRules.curve(MotionScene.Transition.INTERPOLATE_EASE_IN_OUT))
        assertEquals(Curve.EASE_IN, YtMiniRiseRules.curve(MotionScene.Transition.INTERPOLATE_EASE_IN))
        assertEquals(Curve.EASE_OUT, YtMiniRiseRules.curve(MotionScene.Transition.INTERPOLATE_EASE_OUT))
        assertEquals(Curve.LINEAR, YtMiniRiseRules.curve(MotionScene.Transition.INTERPOLATE_LINEAR))
        assertEquals(Curve.BOUNCE, YtMiniRiseRules.curve(MotionScene.Transition.INTERPOLATE_BOUNCE))
        assertEquals(Curve.OVERSHOOT, YtMiniRiseRules.curve(MotionScene.Transition.INTERPOLATE_OVERSHOOT))
        assertEquals(Curve.ANTICIPATE, YtMiniRiseRules.curve(MotionScene.Transition.INTERPOLATE_ANTICIPATE))
        assertEquals(Curve.EASE_OUT, YtMiniRiseRules.curve(MotionScene.Transition.INTERPOLATE_SPLINE_STRING))
    }
}
