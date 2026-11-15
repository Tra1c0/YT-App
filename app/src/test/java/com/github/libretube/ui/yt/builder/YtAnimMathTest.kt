package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class YtAnimMathTest {
    @Test
    fun `curves start at 0 and end at 1`() {
        for (c in listOf(YtCurve.LINEAR, YtCurve.EASE_IN, YtCurve.EASE_OUT, YtCurve.EASE_IN_OUT, YtCurve.STRONG_OUT, YtCurve.bezier(0.3f, -0.5f, 0.7f, 1.6f))) {
            assertEquals(0f, YtCurves.eval(c, 0f), 1e-5f)
            assertEquals(1f, YtCurves.eval(c, 1f), 1e-5f)
        }
        assertEquals(0.37f, YtCurves.eval(YtCurve.LINEAR, 0.37f), 1e-6f)
    }

    @Test
    fun `bezier matches the known CSS values`() {
        // CSS "ease" (0.25, 0.1, 0.25, 1) at x = 0.5 is about 0.8024
        assertEquals(0.8024f, YtCurves.bezier(0.25f, 0.1f, 0.25f, 1f, 0.5f), 2e-3f)
        // ease-in-out is symmetric
        val a = YtCurves.eval(YtCurve.EASE_IN_OUT, 0.3f)
        val b = YtCurves.eval(YtCurve.EASE_IN_OUT, 0.7f)
        assertEquals(1f, a + b, 1e-4f)
        assertEquals(0.5f, YtCurves.eval(YtCurve.EASE_IN_OUT, 0.5f), 1e-4f)
        // ease in starts slow, ease out starts fast
        assertTrue(YtCurves.eval(YtCurve.EASE_IN, 0.2f) < 0.2f)
        assertTrue(YtCurves.eval(YtCurve.EASE_OUT, 0.2f) > 0.2f)
    }

    @Test
    fun `bezier is monotonic in x and may overshoot in y`() {
        val c = YtCurve.bezier(0.34f, 1.56f, 0.64f, 1f)
        var max = 0f
        var x = 0f
        while (x <= 1f) {
            max = maxOf(max, YtCurves.eval(c, x))
            x += 0.01f
        }
        assertTrue("overshoot $max", max > 1.05f)
        // control points with x outside 0..1 are clamped, the solve still works
        val wild = YtCurve.bezier(-3f, 0f, 9f, 1f)
        assertEquals(1f, YtCurves.eval(wild, 1f), 1e-5f)
        assertTrue(YtCurves.eval(wild, 0.5f).isFinite())
    }

    @Test
    fun `slope of linear is one and hold is a step`() {
        assertEquals(1f, YtCurves.slope(YtCurve.LINEAR, 0.4f), 1e-6f)
        assertEquals(0f, YtCurves.eval(YtCurve.HOLD, 0.99f), 0f)
        assertEquals(1f, YtCurves.eval(YtCurve.HOLD, 1f), 0f)
        // ease out is fast at the start, slow at the end
        assertTrue(YtCurves.slope(YtCurve.EASE_OUT, 0.05f) > YtCurves.slope(YtCurve.EASE_OUT, 0.95f))
    }

    @Test
    fun `oklab round trips and blends perceptually`() {
        val buf = FloatArray(4)
        for (c in listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0x800000FF.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
            YtOklab.fromArgb(c, buf)
            val back = YtOklab.toArgb(buf)
            for (shift in listOf(24, 16, 8, 0)) assertTrue(abs(((c ushr shift) and 0xFF) - ((back ushr shift) and 0xFF)) <= 1)
        }
        // OKLab: the middle of red and green is a bright yellow-ish, not the dark sRGB mix
        val mid = YtOklab.lerp(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0.5f)
        val srgb = YtColor.lerp(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0.5f)
        assertTrue(YtColor.luminance(mid) > YtColor.luminance(srgb))
        assertEquals(0xFFFF0000.toInt(), YtOklab.lerp(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0f))
    }

    private fun simulate(x0: Double, v0: Double, k: Double, z: Double, dtMs: Double, totalMs: Double): DoubleArray {
        val out = DoubleArray(2)
        var x = x0
        var v = v0
        var t = 0.0
        while (t < totalMs - 1e-9) {
            val dt = minOf(dtMs, totalMs - t)
            YtSpring.step(x, v, dt / 1000.0, k, z, out)
            x = out[0]
            v = out[1]
            t += dt
        }
        return doubleArrayOf(x, v)
    }

    @Test
    fun `spring is frame rate independent for every damping`() {
        for (z in listOf(0.2, 1.0, 2.5)) {
            val a = simulate(1.0, 0.0, 400.0, z, 1000.0 / 60, 300.0)
            val b = simulate(1.0, 0.0, 400.0, z, 1000.0 / 120, 300.0)
            val c = simulate(1.0, 0.0, 400.0, z, 300.0, 300.0)
            assertEquals(a[0], b[0], 1e-9)
            assertEquals(a[0], c[0], 1e-9)
            assertEquals(a[1], b[1], 1e-7)
        }
    }

    @Test
    fun `spring is stable with stiff springs and long frames`() {
        val r = simulate(1.0, 50.0, 10_000.0, 0.05, 250.0, 5_000.0)
        assertTrue(r[0].isFinite() && abs(r[0]) < 1.0)
        val settle = simulate(1.0, 0.0, 400.0, 0.6, 1000.0 / 60, 3000.0)
        assertTrue(abs(settle[0]) < 1e-3)
    }

    @Test
    fun `spring velocity is the derivative of the position`() {
        val out = DoubleArray(2)
        val h = 1e-6
        YtSpring.step(1.0, 2.0, 0.05, 300.0, 0.4, out)
        val v = out[1]
        YtSpring.step(1.0, 2.0, 0.05 + h, 300.0, 0.4, out)
        val x1 = out[0]
        YtSpring.step(1.0, 2.0, 0.05 - h, 300.0, 0.4, out)
        assertEquals(v, (x1 - out[0]) / (2 * h), 1e-3)
    }

    @Test
    fun `settle time grows with weaker damping`() {
        val stiff = YtSpring.settleMs(1.0, 0.0, 400.0, 1.0, 0.01)
        val wobbly = YtSpring.settleMs(1.0, 0.0, 400.0, 0.2, 0.01)
        assertTrue("$stiff < $wobbly", stiff < wobbly)
        assertEquals(0, YtSpring.settleMs(0.0, 0.0, 400.0, 0.5, 0.01))
    }
}
