package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class YtMorphMotionTest {
    /** Collects what a morph writes. */
    private class Points : YtPathSink {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        var moves = 0
        var closes = 0
        override fun move(x: Float, y: Float) { moves++; xs += x; ys += y }
        override fun line(x: Float, y: Float) { xs += x; ys += y }
        override fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) { xs += x; ys += y }
        override fun close() { closes++ }
        fun box() = floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private val square = YtShapeGeom.rectPath(100f, 100f, YtCorners())
    private val star = YtMorphTarget(YtShapeKind.STAR, YtShapeSpec(sides = 5, inner = 0.5f))
    private val circle = YtMorphTarget(YtShapeKind.ELLIPSE, corners = YtCorners.all(500f))

    @Test
    fun `resampling spreads points evenly by length`() {
        val poly = YtPathGeom.flatten(square, 0.01)[0]
        val s = YtMorph.resample(poly, 40, closed = true)
        for (i in 0 until 40) {
            val j = (i + 1) % 40
            assertEquals(10.0, hypot((s[2 * j] - s[2 * i]).toDouble(), (s[2 * j + 1] - s[2 * i + 1]).toDouble()), 1e-3)
        }
        val open = YtMorph.resample(poly, 41, closed = false)
        assertEquals(open[0], open[80], 1e-3f)
    }

    @Test
    fun `alignment finds the shifted and reversed start`() {
        val poly = YtPathGeom.flatten(square, 0.01)[0]
        val a = YtMorph.resample(poly, 32, true)
        // b: the same points from another start, running the other way
        val b = FloatArray(64) { k -> val i = k / 2; val j = ((7 - i) % 32 + 32) % 32; a[2 * j + k % 2] }
        val aligned = YtMorph.align(a, b, 32, true, 1.0, -1.0)
        for (k in 0 until 64) assertEquals(a[k], aligned[k], 1e-4f)
    }

    @Test
    fun `compatibility is detected and incompatible pairs are approximated`() {
        val other = YtShapeGeom.pathOf(star, 100f, 100f)
        assertTrue(YtMorph.compatible(square, other))
        val ring = YtPathOps.combine(listOf(square, YtPathOps.scale(YtShapeGeom.rectPath(40f, 40f, YtCorners()), 1f, 1f, 30f, 30f)), YtFillRule.EVEN_ODD)
        assertFalse(YtMorph.compatible(square, ring))
        val plan = YtMorph.planPaths(square, ring, 100f, 100f)
        assertEquals(YtMorph.Kind.APPROX, plan.kind)
        assertTrue(plan.approximated)
        // the extra hole grows from its centre: at the start all its points sit there
        val hole = plan.pairs.last()
        assertEquals(hole.a[0], hole.a[2], 1e-4f)
        assertEquals(50f, hole.a[0], 1e-3f)
        val line = YtPath(listOf(YtSubpath(listOf(YtPoint(0f, 50f), YtPoint(100f, 50f)), closed = false)))
        assertEquals(YtMorph.Kind.APPROX, YtMorph.planPaths(square, line, 100f, 100f).kind)
        assertEquals(YtMorph.Kind.PATHS, YtMorph.planPaths(square, other, 100f, 100f).kind)
    }

    @Test
    fun `parametric shapes morph with graphics shapes and start and end at the shapes`() {
        val plan = YtMorph.plan(star, circle, 100f, 100f)
        assertEquals(YtMorph.Kind.SHAPES, plan.kind)
        assertFalse(plan.approximated)
        val start = Points().also { plan.write(0f, 1f, 1f, it) }
        val end = Points().also { plan.write(1f, 2f, 2f, it) }
        assertEquals(1, start.moves)
        assertEquals(1, start.closes)
        val b = end.box()
        // the circle at twice the scale fills 0..200
        assertEquals(0f, b[0], 1f)
        assertEquals(200f, b[2], 1f)
        // the star's inner points are well inside the circle
        val inner = start.xs.indices.minOf { hypot(start.xs[it] - 50f, start.ys[it] - 50f) }
        assertTrue(inner < 40f)
        // halfway it is neither
        val mid = Points().also { plan.write(0.5f, 1f, 1f, it) }
        assertTrue(mid.xs.indices.minOf { hypot(mid.xs[it] - 50f, mid.ys[it] - 50f) } > inner)
        // a path on one side: resampled
        val p = YtMorph.plan(YtMorphTarget(YtShapeKind.PATH, path = square), circle, 100f, 100f)
        assertEquals(YtMorph.Kind.PATHS, p.kind)
        assertTrue(YtMorph.meanTravel(p) < 20.0)
    }

    @Test
    fun `motion paths run by arc length and turn with the path`() {
        val path = YtPath(listOf(YtSubpath(listOf(YtPoint(0f, 0f), YtPoint(10f, 0f), YtPoint(100f, 0f), YtPoint(100f, 100f)), closed = false)), w = 1f, h = 1f)
        val e = YtMotionPathEval(YtMotionPath(path, orient = true))
        assertEquals(200.0, e.length, 1e-6)
        val out = FloatArray(3)
        e.at(0.25f, out)
        assertEquals(50f, out[0], 1e-3f)
        assertEquals(0f, out[1], 1e-3f)
        assertEquals(0f, out[2], 1e-3f)
        e.at(0.75f, out)
        assertEquals(100f, out[0], 1e-3f)
        assertEquals(50f, out[1], 1e-3f)
        assertEquals(90f, out[2], 1e-3f)
        // through the bend the turn is blended, never a jump
        e.at(0.5f, out)
        assertEquals(45f, out[2], 1e-3f)
        val fixed = YtMotionPathEval(YtMotionPath(path, orient = false, angle = 12f))
        fixed.at(0.9f, out)
        assertEquals(12f, out[2])
        // offsets count from the path's start, so a node starts where it rests
        val shifted = YtMotionPathEval(YtMotionPath(YtPathOps.scale(path, 1f, 1f, 30f, 40f)))
        shifted.at(0f, out)
        assertEquals(0f, out[0])
        assertEquals(0f, out[1])
    }

    @Test
    fun `a recorded drag keeps its timing and can be simplified and smoothed`() {
        // slow for the first half of the time (20 dp), fast for the second (80 dp), with jitter
        val samples = (0..100).map { i ->
            val t = i * 10L
            val x = if (i <= 50) i * 0.4f else 20f + (i - 50) * 1.6f
            YtMotionRecord.Sample(t, x, if (i % 2 == 0) 0.3f else -0.3f)
        }
        val raw = YtMotionRecord.build(samples, simplify = 0.05f, smooth = 0f)
        val simple = YtMotionRecord.build(samples, simplify = 2f, smooth = 0.5f)
        assertNotNull(raw)
        assertNotNull(simple)
        raw!!
        simple!!
        assertEquals(1000, simple.durationMs)
        assertTrue(simple.motion.path.pointCount < raw.motion.path.pointCount)
        assertTrue(simple.motion.path.pointCount <= 6)
        val keys = simple.track.keys
        assertEquals(0f, keys.first().at)
        assertEquals(0f, keys.first().value)
        assertEquals(1f, keys.last().at)
        assertEquals(1f, keys.last().value)
        for (i in 1 until keys.size) {
            assertTrue(keys[i].at > keys[i - 1].at)
            assertTrue(keys[i].value >= keys[i - 1].value)
        }
        // the finger's speed: half the time covers about a fifth of the way
        val half = YtAnimEval.sample(simple.track, 0.5f)
        assertTrue("half $half", abs(half - 0.2f) < 0.08f)
        assertTrue(raw.track.keys.size <= YtAnimValidate.MAX_KEYS)
        // even speed replaces the timing
        assertEquals(0.5f, YtAnimEval.sample(YtMotionRecord.evenSpeed(YtCurve.LINEAR), 0.5f), 1e-6f)
        // a finger that never moved records nothing
        assertEquals(null, YtMotionRecord.build(listOf(YtMotionRecord.Sample(0, 0f, 0f), YtMotionRecord.Sample(500, 0f, 0f)), 1f, 0f))
    }
}
