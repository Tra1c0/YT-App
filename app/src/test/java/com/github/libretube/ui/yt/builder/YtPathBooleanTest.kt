package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Boolean results checked by area and by points inside/outside, on sample pairs. */
class YtPathBooleanTest {
    private fun rect(l: Float, t: Float, r: Float, b: Float) = YtShapeGeom.rectPath(r - l, b - t, YtCorners()).let { YtPathOps.scale(it, 1f, 1f, l, t) }

    private fun circle(cx: Float, cy: Float, r: Float) =
        YtShapeGeom.rectPath(2 * r, 2 * r, YtCorners.all(r)).let { YtPathOps.scale(it, 1f, 1f, cx - r, cy - r) }

    /** The covered area of [p]: the loops of a boolean result never overlap, so their signed areas add up. */
    private fun area(p: YtPath) = abs(YtPathGeom.signedArea(p, 0.01))

    private fun inside(p: YtPath, x: Double, y: Double) = YtPathGeom.inside(YtPathGeom.flatten(p, 0.01), p.fillRule, x, y)

    private fun near(expected: Double, actual: Double, share: Double = 0.005) =
        assertTrue("expected $expected, got $actual", abs(expected - actual) <= abs(expected) * share + 1e-6)

    private val a = rect(0f, 0f, 100f, 100f)
    private val b = rect(50f, 50f, 150f, 150f)

    @Test
    fun `union of two overlapping squares`() {
        val u = YtPathBoolean.op(YtPathBoolean.Op.UNION, listOf(a, b))
        near(17_500.0, area(u))
        assertEquals(1, u.subpaths.size)
        // the outline of an L-and-L union has 8 corners
        assertEquals(8, u.subpaths[0].points.size)
        assertTrue(inside(u, 25.0, 25.0))
        assertTrue(inside(u, 125.0, 125.0))
        assertTrue(inside(u, 75.0, 75.0))
        assertFalse(inside(u, 125.0, 25.0))
        assertFalse(inside(u, 25.0, 125.0))
    }

    @Test
    fun `subtract the second from the first`() {
        val s = YtPathBoolean.op(YtPathBoolean.Op.SUBTRACT, listOf(a, b))
        near(7_500.0, area(s))
        assertTrue(inside(s, 25.0, 25.0))
        assertFalse(inside(s, 75.0, 75.0))
        assertFalse(inside(s, 125.0, 125.0))
        assertEquals(6, s.subpaths.single().points.size)
    }

    @Test
    fun `intersect keeps the overlap`() {
        val i = YtPathBoolean.op(YtPathBoolean.Op.INTERSECT, listOf(a, b))
        near(2_500.0, area(i))
        assertTrue(inside(i, 75.0, 75.0))
        assertFalse(inside(i, 25.0, 25.0))
        assertFalse(inside(i, 125.0, 125.0))
        assertEquals(4, i.subpaths.single().points.size)
    }

    @Test
    fun `exclude keeps what only one covers`() {
        val x = YtPathBoolean.op(YtPathBoolean.Op.EXCLUDE, listOf(a, b))
        near(15_000.0, area(x))
        assertTrue(inside(x, 25.0, 25.0))
        assertTrue(inside(x, 125.0, 125.0))
        assertFalse(inside(x, 75.0, 75.0))
        assertFalse(inside(x, 125.0, 25.0))
    }

    @Test
    fun `circles stay curves and match the analytic areas`() {
        val r = 50.0
        val d = 50.0
        val lens = 2 * r * r * acos(d / (2 * r)) - d / 2 * sqrt(4 * r * r - d * d)
        val c1 = circle(50f, 50f, 50f)
        val c2 = circle(100f, 50f, 50f)
        val u = YtPathBoolean.op(YtPathBoolean.Op.UNION, listOf(c1, c2))
        near(2 * PI * r * r - lens, area(u))
        val i = YtPathBoolean.op(YtPathBoolean.Op.INTERSECT, listOf(c1, c2))
        near(lens, area(i))
        val s = YtPathBoolean.op(YtPathBoolean.Op.SUBTRACT, listOf(c1, c2))
        near(PI * r * r - lens, area(s))
        val x = YtPathBoolean.op(YtPathBoolean.Op.EXCLUDE, listOf(c1, c2))
        near(2 * (PI * r * r - lens), area(x))
        // a curve result, not hundreds of corners
        assertTrue(u.pointCount.toString(), u.pointCount <= 12)
        assertTrue(u.subpaths[0].points.any { it.hasIn || it.hasOut })
        assertTrue(i.pointCount.toString(), i.pointCount <= 8)
    }

    @Test
    fun `a hole comes out as its own subpath`() {
        val big = rect(0f, 0f, 100f, 100f)
        val small = rect(40f, 40f, 60f, 60f)
        val s = YtPathBoolean.op(YtPathBoolean.Op.SUBTRACT, listOf(big, small))
        assertEquals(2, s.subpaths.size)
        near(9_600.0, area(s))
        assertFalse(inside(s, 50.0, 50.0))
        assertTrue(inside(s, 10.0, 50.0))
        // and an even-odd ring as an operand keeps its hole
        val ring = YtPathOps.combine(listOf(big, small), YtFillRule.EVEN_ODD)
        val cut = YtPathBoolean.op(YtPathBoolean.Op.INTERSECT, listOf(ring, rect(0f, 0f, 50f, 100f)))
        near(5_000.0 - 200.0, area(cut))
    }

    @Test
    fun `shapes that share an edge merge into one outline`() {
        val left = rect(0f, 0f, 50f, 50f)
        val right = rect(50f, 0f, 100f, 50f)
        val u = YtPathBoolean.op(YtPathBoolean.Op.UNION, listOf(left, right))
        near(5_000.0, area(u))
        assertEquals(4, u.subpaths.single().points.size)
    }

    @Test
    fun `more than two operands`() {
        val c = rect(100f, 0f, 200f, 100f)
        val u = YtPathBoolean.op(YtPathBoolean.Op.UNION, listOf(a, b, c))
        // a ∪ b = 17500, c adds 10000 less its overlap with b (2500)
        near(25_000.0, area(u))
        val s = YtPathBoolean.op(YtPathBoolean.Op.SUBTRACT, listOf(rect(0f, 0f, 200f, 100f), a, c))
        near(0.0, area(s), 0.0)
    }

    @Test
    fun `disjoint shapes`() {
        val far = rect(300f, 300f, 400f, 400f)
        near(20_000.0, area(YtPathBoolean.op(YtPathBoolean.Op.UNION, listOf(a, far))))
        assertTrue(YtPathBoolean.op(YtPathBoolean.Op.INTERSECT, listOf(a, far)).subpaths.isEmpty())
    }

    @Test
    fun `random curved and turned shapes keep the area identities`() {
        val rnd = java.util.Random(37)
        val kinds = listOf(YtShapeKind.STAR, YtShapeKind.POLYGON, YtShapeKind.TRIANGLE, YtShapeKind.SQUIRCLE, YtShapeKind.ELLIPSE, YtShapeKind.RECT)
        fun shape(): YtPath {
            val k = kinds[rnd.nextInt(kinds.size)]
            val w = 40f + rnd.nextFloat() * 80f
            val h = 40f + rnd.nextFloat() * 80f
            val t = YtMorphTarget(k, YtShapeSpec(sides = 3 + rnd.nextInt(6), inner = 0.3f + rnd.nextFloat() * 0.5f, rounding = rnd.nextFloat() * 6f), YtCorners.all(rnd.nextFloat() * 20f))
            return YtPathOps.place(YtShapeGeom.pathOf(t, w, h), 60f + rnd.nextFloat() * 40f, 60f + rnd.nextFloat() * 40f, w, h, rnd.nextFloat() * 360f)
        }
        repeat(12) { round ->
            val p = shape()
            val q = shape()
            val ap = area(p)
            val aq = area(q)
            val u = area(YtPathBoolean.op(YtPathBoolean.Op.UNION, listOf(p, q)))
            val i = area(YtPathBoolean.op(YtPathBoolean.Op.INTERSECT, listOf(p, q)))
            val d = area(YtPathBoolean.op(YtPathBoolean.Op.SUBTRACT, listOf(p, q)))
            val x = area(YtPathBoolean.op(YtPathBoolean.Op.EXCLUDE, listOf(p, q)))
            val tol = (ap + aq) * 0.004
            assertEquals("round $round: union + intersect", ap + aq, u + i, tol)
            assertEquals("round $round: subtract + intersect", ap, d + i, tol)
            assertEquals("round $round: exclude", u - i, x, tol)
            // the analytic check against an independent grid sample of the union
            val grid = YtPathGeom.filledArea(YtPathOps.combine(listOf(p, q)), cells = 300)
            assertEquals("round $round: union by sampling", grid, u, (ap + aq) * 0.02)
        }
    }

    @Test
    fun `a stroke outlined to a shape covers the stroke`() {
        val line = YtPath(listOf(YtSubpath(listOf(YtPoint(0f, 0f), YtPoint(100f, 0f)), closed = false)))
        val o = YtPathBoolean.outline(line, 10f)
        // 100 × 10 plus two half discs of radius 5
        near(1_000.0 + PI * 25, area(o), 0.02)
        assertTrue(inside(o, 50.0, 4.0))
        assertFalse(inside(o, 50.0, 6.0))
        val square = rect(0f, 0f, 100f, 100f)
        val frame = YtPathBoolean.outline(square, 4f)
        // a frame: the outside 104² with round corners minus the inside 96²
        near(104.0 * 104 - (4 - PI) * 4 - 96.0 * 96, area(frame), 0.02)
        assertFalse(inside(frame, 50.0, 50.0))
        assertTrue(inside(frame, 50.0, 1.0))
    }
}
