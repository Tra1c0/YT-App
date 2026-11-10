package com.github.libretube.ui.yt

import com.github.libretube.ui.yt.FrequencyPulseMath as M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** YT: Frequency's mapping from the sound to motion: rests in silence, clamped, rate-safe. */
class FrequencyPulseMathTest {
    private val frame = 1f / 60f

    @Test
    fun `silence is the rest state`() {
        assertEquals(0f, M.follow(0f, 0f, frame), 0f)
        assertEquals(0f, M.energyTarget(0f, 0f), 0f)
        assertEquals(0f, M.glowTarget(0f), 0f)
        assertEquals(1f, M.breathScale(0f), 0f)
        assertEquals(0f, M.pulseLift(0f), 0f)
        assertEquals(0f, M.shimmerAlpha(0f), 0f)
        assertEquals(M.GLOW_REST, M.glowAlpha(0f), 0f)
        assertEquals(M.AURA_REST, M.auraAlpha(0f), 0f)
        assertEquals(0f, M.auraDx(0f, 0f), 0f)
        assertEquals(0f, M.auraDy(0f), 0f)
        assertEquals(1f, M.auraGrow(0f), 0f)
        assertEquals(0f, M.turnStep(0f, frame), 0f)
        assertEquals(0.07f, M.cookieDepth(0.07f, 0f), 1e-6f)
        assertTrue(M.atRest(0f, 0.001f))
    }

    @Test
    fun `every mapping is clamped, whatever comes in`() {
        val wild = listOf(Float.NaN, -5f, -0.1f, 0f, 0.5f, 1f, 1.7f, 100f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (x in wild) {
            for (y in wild) {
                val f = M.follow(x, y, frame)
                assertTrue("follow $x $y = $f", f in 0f..1f)
                assertTrue(M.energyTarget(x, y) in 0f..1f)
                assertTrue(M.breathScale(x, y) in 1f..(1f + M.BREATH_MAX))
                assertTrue(abs(M.auraDx(x, y)) <= M.AURA_DRIFT + 1e-6f)
            }
            assertTrue(M.glowTarget(x) in 0f..1f)
            assertTrue(M.pulseLift(x) in 0f..M.PULSE_LIFT_MAX)
            assertTrue(M.shimmerAlpha(x) in 0f..M.SHIMMER_MAX)
            assertTrue(M.glowAlpha(x) in M.GLOW_REST..M.GLOW_PEAK)
            assertTrue(M.auraAlpha(x) in M.AURA_REST..M.AURA_PEAK)
            assertTrue(abs(M.auraDy(x)) <= M.AURA_DRIFT + 1e-6f)
            assertTrue(M.turnStep(x, frame) in 0f..(M.TURN_MAX * frame + 1e-6f))
            assertTrue(M.cookieDepth(0.07f, x) <= 0.07f + M.DEPTH_MAX + 1e-6f)
        }
        // a hitch of 10 s counts as a short frame, a NaN time as none
        assertTrue(M.follow(0f, 1f, 10f) <= M.MAX_SLEW * M.MAX_DT + 1e-6f)
        assertEquals(0.3f, M.follow(0.3f, 1f, Float.NaN), 0f)
        assertEquals(0.3f, M.follow(0.3f, 1f, -1f), 0f)
    }

    @Test
    fun `no channel changes faster than the slew limit`() {
        var v = 0f
        val inputs = floatArrayOf(1f, 0f, 1f, 1f, 0f, 0.2f, 1f, 0f)
        repeat(600) { i ->
            val next = M.follow(v, inputs[i % inputs.size], frame)
            assertTrue("step ${abs(next - v)}", abs(next - v) <= M.MAX_SLEW * frame + 1e-5f)
            v = next
        }
    }

    @Test
    fun `a full swing takes long enough for at most three a second`() {
        // from silence to the loudest: the rise alone takes at least 1/6 s
        var v = 0f
        var t = 0f
        while (v < 0.99f && t < 2f) {
            v = M.follow(v, 1f, frame)
            t += frame
        }
        assertTrue("rise took $t s", t >= 1f / M.MAX_SLEW - 1e-4f)
        // and back down: up and down together at least 1/3 s, so at most 3 swings a second
        var down = 0f
        while (v > 0.01f && down < 3f) {
            v = M.follow(v, 0f, frame)
            down += frame
        }
        assertTrue("swing took ${t + down} s", t + down >= 1f / 3f)
    }

    @Test
    fun `a fast flicker in the sound becomes a small ripple, not a strobe`() {
        // hits at 10 Hz (on and off every 50 ms) for 3 s
        var v = 0f
        var lo = 1f
        var hi = 0f
        var time = 0f
        while (time < 3f) {
            val on = ((time * 20f).toInt() % 2) == 0
            v = M.follow(v, if (on) 1f else 0f, frame)
            if (time > 1f) {
                lo = minOf(lo, v)
                hi = maxOf(hi, v)
            }
            time += frame
        }
        // 50 ms at 6/s is 0.3 at most each way
        assertTrue("ripple ${hi - lo}", hi - lo <= 0.35f)
        // so the nav's veil changes by at most a third of its small range
        assertTrue(M.pulseLift(hi) - M.pulseLift(lo) <= M.PULSE_LIFT_MAX * 0.35f + 1e-6f)
    }

    @Test
    fun `a slow beat still comes through`() {
        // 1 Hz: half a second on, half off
        var v = 0f
        var hi = 0f
        var time = 0f
        while (time < 3f) {
            val on = ((time * 2f).toInt() % 2) == 0
            v = M.follow(v, if (on) 1f else 0f, frame)
            hi = maxOf(hi, v)
            time += frame
        }
        assertTrue("peak $hi", hi >= 0.9f)
    }

    @Test
    fun `attack is quicker than release`() {
        val up = M.follow(0.5f, 1f, frame) - 0.5f
        val down = 0.5f - M.follow(0.5f, 0f, frame)
        assertTrue(up > down)
    }

    @Test
    fun `louder means more, never less`() {
        var last = -1f
        for (i in 0..20) {
            val l = i / 20f
            val g = M.glowTarget(l)
            assertTrue(g >= last)
            last = g
            assertTrue(M.breathScale(l) >= M.breathScale(l - 0.05f))
        }
        assertEquals(1f, M.glowTarget(1f), 1e-6f)
    }

    @Test
    fun `the cookie stays between its valleys and tips`() {
        for (depth in listOf(0f, 0.07f, 0.12f, 0.9f)) {
            for (i in 0..360) {
                val r = M.cookieRadius((i * PI / 180).toFloat(), 9, depth, 0.3f)
                assertTrue(r <= 1f + 1e-6f)
                assertTrue(r >= 1f - depth.coerceAtMost(0.5f) - 1e-6f)
            }
        }
        // a tip where the turn puts it
        assertEquals(1f, M.cookieRadius(0.3f, 9, 0.1f, 0.3f), 1e-6f)
    }

    @Test
    fun `the shimmer runs along the element and wraps`() {
        for (turn in listOf(-720f, -10f, 0f, 45f, 89.9f, 90f, 1000f)) {
            assertTrue(M.shimmerAt(turn) in 0f..1f)
        }
        assertEquals(0.5f, M.shimmerAt(45f), 1e-6f)
    }
}
