package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class YtAnimEngineTest {
    private val kind = YtComponentKind.CARD

    private fun fade(id: String = "f", ms: Int = 300, from: Float = 0f, to: Float = 1f, curve: YtCurve = YtCurve.LINEAR, delay: Int = 0) =
        YtAnim(id, component = kind, node = "n", durationMs = ms, delayMs = delay, tracks = listOf(YtTrack(YtAnimProp.OPACITY, listOf(YtKeyframe(0f, from, curve = curve), YtKeyframe(1f, to)))))

    private fun spring(id: String, from: Float, to: Float, velocity: Float = 0f, k: Float = 300f, z: Float = 0.4f) =
        YtAnim(id, component = kind, node = "n", kind = YtAnimKind.SPRING, spring = YtSpringSpec(k, z), tracks = listOf(YtTrack(YtAnimProp.X, listOf(YtKeyframe(0f, from), YtKeyframe(1f, to)), velocity = velocity)))

    /** Runs frames at [hz] from frame time 0 until the engine stops; returns the ms it ran. */
    private fun runAt(e: YtAnimEngine, hz: Double, maxMs: Double = 10_000.0): Double {
        val step = 1_000_000_000.0 / hz
        var t = 0.0
        e.tick(0L)
        while (e.running && t / 1e6 < maxMs) {
            t += step
            e.tick(t.toLong())
        }
        return t / 1e6
    }

    @Test
    fun `the same duration at 60 and 120 Hz`() {
        for (a in listOf(fade(ms = 300), fade(ms = 1000, delay = 250), fade(ms = 500).copy(repeat = 2, direction = YtAnimDirection.ALTERNATE))) {
            val e60 = YtAnimEngine().apply { start(a) }
            val e120 = YtAnimEngine().apply { start(a) }
            val t60 = runAt(e60, 60.0)
            val t120 = runAt(e120, 120.0)
            val expected = YtAnimEval.totalMs(a)!!.toDouble()
            // ends within one frame of its length either way
            assertTrue("60 Hz $t60 vs $expected", t60 >= expected && t60 < expected + 1000.0 / 60 + 0.01)
            assertTrue("120 Hz $t120 vs $expected", t120 >= expected && t120 < expected + 1000.0 / 120 + 0.01)
            assertEquals(e60.node("n").float(YtAnimProp.OPACITY), e120.node("n").float(YtAnimProp.OPACITY), 1e-6f)
        }
    }

    @Test
    fun `values at the same time match at 60 and 120 Hz`() {
        val a = fade(ms = 600, curve = YtCurve.EASE_IN_OUT)
        val e60 = YtAnimEngine().apply { start(a); tick(0) }
        val e120 = YtAnimEngine().apply { start(a); tick(0) }
        // 250 ms: 15 frames at 60 Hz, 30 at 120 Hz
        repeat(15) { e60.advance(1000.0 / 60) }
        repeat(30) { e120.advance(1000.0 / 120) }
        assertEquals(e60.node("n").float(YtAnimProp.OPACITY), e120.node("n").float(YtAnimProp.OPACITY), 1e-4f)
        val s60 = YtAnimEngine().apply { start(spring("s", -40f, 0f)) }
        val s120 = YtAnimEngine().apply { start(spring("s", -40f, 0f)) }
        repeat(15) { s60.advance(1000.0 / 60) }
        repeat(30) { s120.advance(1000.0 / 120) }
        assertEquals(s60.node("n").float(YtAnimProp.X), s120.node("n").float(YtAnimProp.X), 1e-3f)
    }

    @Test
    fun `a time-based interruption starts from the current value`() {
        val e = YtAnimEngine()
        e.start(fade("in", 400, 0f, 1f))
        repeat(10) { e.advance(16.0) }
        val before = e.node("n").float(YtAnimProp.OPACITY)
        assertTrue(before in 0.2f..0.6f)
        // the fade out begins at 1 in its keyframes; it must start where the fade in is
        e.start(fade("out", 400, 1f, 0f))
        assertEquals(before, e.node("n").float(YtAnimProp.OPACITY), 1e-5f)
        e.advance(16.0)
        val after = e.node("n").float(YtAnimProp.OPACITY)
        assertTrue("no jump: $before → $after", abs(after - before) < 0.05f)
        assertTrue(after < before)
    }

    @Test
    fun `a spring interruption keeps position and velocity`() {
        val e = YtAnimEngine()
        e.start(spring("a", 0f, 100f))
        repeat(6) { e.advance(16.0) }
        val ch = e.node("n").channel(YtAnimProp.X)!!
        val x = ch.float()
        val v = ch.velocity()
        assertTrue(v > 0f)
        e.start(spring("b", 100f, 0f))
        assertEquals(x, ch.float(), 1e-4f)
        assertEquals(v, ch.velocity(), 1e-3f)
        // the next frame goes on in the same direction (momentum), no jump
        e.advance(1.0)
        assertTrue(ch.float() > x)
        assertTrue(abs(ch.float() - x) < 2f)
        runAt(e, 120.0)
        assertEquals(0f, ch.float(), 0.02f)
    }

    @Test
    fun `a spring taking over a time-based run keeps its speed`() {
        val e = YtAnimEngine()
        e.start(YtAnim("t", component = kind, node = "n", durationMs = 500, tracks = listOf(YtTrack(YtAnimProp.X, listOf(YtKeyframe(0f, 0f), YtKeyframe(1f, 100f))))))
        repeat(10) { e.advance(10.0) }
        val ch = e.node("n").channel(YtAnimProp.X)!!
        // 100 dp in 500 ms: 200 dp per second
        assertEquals(200f, ch.velocity(), 1f)
        e.start(spring("s", 0f, 50f))
        assertEquals(200f, ch.velocity(), 1f)
    }

    @Test
    fun `the last started wins`() {
        val e = YtAnimEngine()
        e.start(fade("a", 1000, 0f, 1f))
        e.advance(100.0)
        e.start(YtAnim("b", component = kind, node = "n", durationMs = 200, tracks = listOf(YtTrack(YtAnimProp.OPACITY, listOf(YtKeyframe(0f, 1f), YtKeyframe(1f, 0.5f))))))
        runAt(e, 60.0)
        assertEquals(0.5f, e.node("n").float(YtAnimProp.OPACITY), 1e-5f)
    }

    @Test
    fun `motion off snaps to the end`() {
        val e = YtAnimEngine().apply { snap = true }
        e.start(fade(ms = 5000, from = 1f, to = 0f))
        assertFalse(e.running)
        assertEquals(0f, e.node("n").float(YtAnimProp.OPACITY), 0f)
        // forever repeating: the end of one play
        e.start(YtAnimPresets.build(YtPreset.PULSE, kind, "n", newId = { "p" }).anims.single())
        assertFalse(e.running)
        assertEquals(1f, e.node("n").float(YtAnimProp.SCALE_X), 1e-5f)
        e.start(spring("s", 0f, 30f))
        assertEquals(30f, e.node("n").float(YtAnimProp.X), 0f)
    }

    @Test
    fun `repeat and direction`() {
        val a = fade(ms = 100).copy(repeat = 1, direction = YtAnimDirection.ALTERNATE)
        val e = YtAnimEngine().apply { start(a) }
        e.advance(50.0)
        assertEquals(0.5f, e.node("n").float(YtAnimProp.OPACITY), 1e-4f)
        e.advance(100.0)
        // second play runs back: at 150 ms it is half way down again
        assertEquals(0.5f, e.node("n").float(YtAnimProp.OPACITY), 1e-4f)
        e.advance(100.0)
        assertFalse(e.running)
        assertEquals(0f, e.node("n").float(YtAnimProp.OPACITY), 1e-5f)
        val r = YtAnimEngine().apply { start(fade(ms = 100).copy(direction = YtAnimDirection.REVERSE)) }
        assertEquals(1f, r.node("n").float(YtAnimProp.OPACITY), 1e-5f)
        val forever = YtAnimEngine().apply { start(fade(ms = 100).copy(repeat = -1)) }
        forever.advance(10_000.0)
        assertTrue(forever.running)
    }

    @Test
    fun `the delay shows the first keyframe and the clock pause skips the gap`() {
        val e = YtAnimEngine()
        e.start(fade(ms = 100, delay = 200))
        e.tick(0L)
        e.tick(150_000_000L)
        assertEquals(0f, e.node("n").float(YtAnimProp.OPACITY), 0f)
        e.resetClock()
        // the app was away for an hour: the next frame counts as no time
        e.tick(3_600_000_000_000L)
        assertEquals(0f, e.node("n").float(YtAnimProp.OPACITY), 0f)
        e.tick(3_600_000_000_000L + 100_000_000L)
        assertEquals(0.5f, e.node("n").float(YtAnimProp.OPACITY), 1e-3f)
    }

    @Test
    fun `colour tracks run in OKLab and take the own colour`() {
        val own = 0xFF2040FF.toInt()
        val e = YtAnimEngine { _, _ -> own }
        val a = YtAnim("c", component = kind, node = "n", durationMs = 100, tracks = listOf(YtTrack(YtAnimProp.FILL_COLOR, listOf(YtKeyframe(0f, color = null), YtKeyframe(1f, color = 0xFFFF0000.toInt())))))
        e.start(a)
        near(own, e.node("n").argb(YtAnimProp.FILL_COLOR, 0))
        e.advance(50.0)
        near(YtOklab.lerp(own, 0xFFFF0000.toInt(), 0.5f), e.node("n").argb(YtAnimProp.FILL_COLOR, 0))
        e.advance(60.0)
        near(0xFFFF0000.toInt(), e.node("n").argb(YtAnimProp.FILL_COLOR, 0))
    }

    private fun near(a: Int, b: Int) {
        for (shift in listOf(24, 16, 8, 0)) assertTrue("${YtColor.hex(a)} vs ${YtColor.hex(b)}", abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)) <= 1)
    }

    @Test
    fun `keyframes interpolate radii with their curves`() {
        val t = YtTrack(YtAnimProp.CORNERS, listOf(YtKeyframe(0f, 0f, curve = YtCurve.EASE_IN), YtKeyframe(0.5f, 20f), YtKeyframe(1f, 4f)))
        assertEquals(0f, YtAnimEval.sample(t, 0f), 0f)
        assertEquals(20f * YtCurves.eval(YtCurve.EASE_IN, 0.5f), YtAnimEval.sample(t, 0.25f), 1e-4f)
        assertEquals(20f, YtAnimEval.sample(t, 0.5f), 0f)
        assertEquals(12f, YtAnimEval.sample(t, 0.75f), 1e-4f)
        assertEquals(4f, YtAnimEval.sample(t, 1f), 0f)
    }

    @Test
    fun `muted and solo tracks`() {
        val a = YtAnim(
            "m", component = kind, node = "n", durationMs = 100,
            tracks = listOf(
                YtTrack(YtAnimProp.X, listOf(YtKeyframe(0f, 0f), YtKeyframe(1f, 10f))),
                YtTrack(YtAnimProp.Y, listOf(YtKeyframe(0f, 0f), YtKeyframe(1f, 10f)), muted = true),
                YtTrack(YtAnimProp.ROTATION, listOf(YtKeyframe(0f, 0f), YtKeyframe(1f, 10f))),
            ),
        )
        assertEquals(listOf(YtAnimProp.X, YtAnimProp.ROTATION), a.playing().map { it.prop })
        val solo = a.copy(tracks = a.tracks.mapIndexed { i, t -> if (i == 2) t.copy(solo = true) else t })
        assertEquals(listOf(YtAnimProp.ROTATION), solo.playing().map { it.prop })
    }

    @Test
    fun `preview evaluates like the live run`() {
        val a = fade(ms = 400, curve = YtCurve.EASE_OUT, delay = 100)
        val live = YtAnimEngine().apply { start(a) }
        repeat(20) { live.advance(10.0) }
        val pre = YtAnimEngine().apply { preview(listOf(a to 0), 200.0) }
        assertEquals(live.node("n").float(YtAnimProp.OPACITY), pre.node("n").float(YtAnimProp.OPACITY), 1e-4f)
        val s = spring("s", -20f, 0f)
        val sl = YtAnimEngine().apply { start(s) }
        repeat(25) { sl.advance(10.0) }
        val sp = YtAnimEngine().apply { preview(listOf(s to 0), 250.0) }
        assertEquals(sl.node("n").float(YtAnimProp.X), sp.node("n").float(YtAnimProp.X), 1e-3f)
    }

    @Test
    fun `scroll drives its animation along the range`() {
        val a = YtAnim("s", component = kind, node = "n", trigger = YtTrigger(YtTriggerKind.SCROLL, scrollRange = 200f), durationMs = 1000, tracks = listOf(YtTrack(YtAnimProp.Y, listOf(YtKeyframe(0f, 0f), YtKeyframe(1f, -50f)))))
        val c = YtAnimController(YtAnimIndex(YtAnimations(listOf(a)), kind), YtAnimEngine())
        c.scroll(100f)
        assertEquals(-25f, c.engine.node("n").float(YtAnimProp.Y), 1e-4f)
        c.scroll(400f)
        assertEquals(-50f, c.engine.node("n").float(YtAnimProp.Y), 1e-4f)
        assertFalse(c.engine.running)
    }
}
