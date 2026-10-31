package com.github.libretube.ui.yt.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSpringTest {
    private fun YtSpring.run(seconds: Float, frame: Float = 1f / 60f): List<Float> {
        val values = mutableListOf<Float>()
        var t = 0f
        while (t < seconds) {
            step(frame)
            values += value
            t += frame
        }
        return values
    }

    @Test
    fun itComesToRestAtTheTarget() {
        val s = YtSpring(0f, stiffness = 600f, dampingRatio = 0.85f)
        s.animateTo(1f)
        s.run(2f)
        assertTrue(s.atRest)
        assertEquals(1f, s.value, 0f)
        assertFalse(s.step(1f / 60f))
    }

    @Test
    fun criticalDampingDoesNotOvershoot() {
        val s = YtSpring(0f, stiffness = 1500f, dampingRatio = 1f)
        s.animateTo(1f)
        assertTrue(s.run(1f).all { it <= 1.0005f })
    }

    @Test
    fun aSofterSpringOvershootsALittle() {
        val s = YtSpring(0f, stiffness = 380f, dampingRatio = 0.6f)
        s.animateTo(1f)
        val peak = s.run(1.5f).max()
        assertTrue(peak > 1.02f)
        assertTrue(peak < 1.2f)
    }

    @Test
    fun aNewTargetKeepsTheVelocity() {
        val s = YtSpring(0f, stiffness = 600f, dampingRatio = 1f)
        s.animateTo(1f)
        s.run(0.1f)
        val v = s.velocity
        assertTrue(v > 0f)
        s.animateTo(0f)
        assertEquals(v, s.velocity, 0f)
        // it still moves on a little before it turns
        val before = s.value
        s.step(1f / 120f)
        assertTrue(s.value > before)
    }

    @Test
    fun aKickCarriesTheReleaseSpeed() {
        val s = YtSpring(0f, stiffness = 600f, dampingRatio = 1f)
        s.kick(5f)
        val peak = s.run(1f).max()
        assertTrue(peak > 0.05f)
        assertTrue(s.atRest)
        assertEquals(0f, s.value, 0f)
    }

    @Test
    fun snappingStopsItAndAPausedFrameDoesNotThrowIt() {
        val s = YtSpring(0f, stiffness = 10_000f, dampingRatio = 0.9f)
        s.animateTo(1f)
        // a whole second in one step is taken as one long frame, not integrated blindly
        s.step(1f)
        assertTrue(s.value.isFinite())
        assertTrue(s.value in -1f..3f)
        s.snapTo(0.5f)
        assertTrue(s.atRest)
        assertEquals(0.5f, s.value, 0f)
    }
}
