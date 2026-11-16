package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class YtPathOpsTest {
    private fun open(vararg xy: Float) = YtSubpath(xy.toList().chunked(2).map { YtPoint(it[0], it[1]) }, closed = false)
    private fun ref(s: Int, i: Int) = YtPathGeom.Ref(s, i)
    private val curve = YtPath(
        listOf(
            YtSubpath(
                listOf(
                    YtPoint(0f, 0f, ox = 30f, oy = -40f),
                    YtPoint(100f, 0f, ix = -30f, iy = -40f, ox = 20f, oy = 40f, kind = YtPointKind.SMOOTH),
                    YtPoint(50f, 100f, ix = 40f, iy = 10f),
                ),
                closed = true,
            ),
        ),
    )

    @Test
    fun `inserting a point keeps the curve`() {
        val before = YtPathGeom.flatten(curve, 0.01)
        val after = YtPathOps.insert(curve, 0, 0, 0.3)
        assertEquals(4, after.subpaths[0].points.size)
        // the new anchor lies on the old curve at t = 0.3
        val c = DoubleArray(8)
        YtPathGeom.segment(curve.subpaths[0], 0, c)
        val p = DoubleArray(2)
        YtPathGeom.pointAt(c, 0.3, p)
        val mid = after.subpaths[0].points[1]
        assertEquals(p[0], mid.x.toDouble(), 1e-3)
        assertEquals(p[1], mid.y.toDouble(), 1e-3)
        // and the area is unchanged
        assertEquals(before.sumOf { it.area() }, YtPathGeom.flatten(after, 0.01).sumOf { it.area() }, 1.0)
    }

    @Test
    fun `reverse twice is the same and keeps the area size`() {
        val r = YtPathOps.reverse(curve)
        assertEquals(curve, YtPathOps.reverse(r))
        val a = YtPathGeom.signedArea(curve)
        assertEquals(-a, YtPathGeom.signedArea(r), 1.0)
    }

    @Test
    fun `handles follow the point kind`() {
        val smooth = YtPathOps.setHandle(curve, ref(0, 1), YtPathGeom.HandleSide.OUT, 0f, 30f)
        val p = smooth.subpaths[0].points[1]
        // the in-handle turns opposite and keeps its length (50)
        assertEquals(hypot(-30f, -40f), hypot(p.ix, p.iy), 1e-3f)
        assertEquals(0f, p.ix, 1e-3f)
        assertTrue(p.iy < 0f)
        val sym = YtPathOps.setHandle(YtPathOps.setKind(curve, ref(0, 1), YtPointKind.SYMMETRIC), ref(0, 1), YtPathGeom.HandleSide.IN, 10f, 5f)
        assertEquals(-10f, sym.subpaths[0].points[1].ox, 1e-4f)
        assertEquals(-5f, sym.subpaths[0].points[1].oy, 1e-4f)
        val free = YtPathOps.setHandle(curve, ref(0, 1), YtPathGeom.HandleSide.OUT, 7f, 7f, independent = true)
        assertEquals(-30f, free.subpaths[0].points[1].ix, 1e-4f)
        assertEquals(YtPointKind.CORNER, free.subpaths[0].points[1].kind)
    }

    @Test
    fun `add, move and remove points`() {
        var p = YtPath()
        p = YtPathOps.append(p, 0, YtPoint(0f, 0f))
        p = YtPathOps.append(p, 0, YtPoint(10f, 0f))
        p = YtPathOps.append(p, 0, YtPoint(10f, 10f))
        assertEquals(3, p.subpaths[0].points.size)
        p = YtPathOps.move(p, setOf(ref(0, 1), ref(0, 2)), 5f, 1f)
        assertEquals(15f, p.subpaths[0].points[1].x)
        assertEquals(11f, p.subpaths[0].points[2].y)
        p = YtPathOps.remove(p, setOf(ref(0, 1)))
        assertEquals(2, p.subpaths[0].points.size)
        assertTrue(YtPathOps.remove(p, setOf(ref(0, 0))).subpaths.isEmpty())
    }

    @Test
    fun `split and join`() {
        val closed = YtPath(listOf(YtSubpath(listOf(YtPoint(0f, 0f), YtPoint(10f, 0f), YtPoint(10f, 10f), YtPoint(0f, 10f)))))
        val opened = YtPathOps.split(closed, ref(0, 2))
        val s = opened.subpaths.single()
        assertFalse(s.closed)
        assertEquals(5, s.points.size)
        assertEquals(s.points.first().x, s.points.last().x)
        assertEquals(10f, s.points.first().x)
        // joining its ends closes it again into the same four corners
        val again = YtPathOps.join(opened, ref(0, 0), ref(0, 4))
        assertTrue(again.subpaths.single().closed)
        assertEquals(4, again.subpaths.single().points.size)
        assertEquals(abs(YtPathGeom.signedArea(closed)), abs(YtPathGeom.signedArea(again)), 1e-3)

        val two = YtPath(listOf(open(0f, 0f, 10f, 0f), open(30f, 0f, 20f, 0f)))
        val joined = YtPathOps.join(two, ref(0, 1), ref(1, 1))
        assertEquals(1, joined.subpaths.size)
        assertEquals(listOf(0f, 10f, 20f, 30f), joined.subpaths[0].points.map { it.x })
        val parts = YtPathOps.split(joined, ref(0, 2))
        assertEquals(2, parts.subpaths.size)
        assertEquals(listOf(0f, 10f, 20f), parts.subpaths[0].points.map { it.x })
        assertEquals(listOf(20f, 30f), parts.subpaths[1].points.map { it.x })
    }

    @Test
    fun `RDP drops what lies on the line and keeps corners`() {
        val line = open(0f, 0f, 10f, 0.1f, 20f, -0.1f, 30f, 0f, 30f, 30f)
        val s = YtPathOps.simplify(line, 0.5f)
        assertEquals(listOf(0f to 0f, 30f to 0f, 30f to 30f), s.points.map { it.x to it.y })
        // a tight tolerance keeps the bumps
        assertEquals(5, YtPathOps.simplify(line, 0.01f).points.size)
        // a closed loop keeps at least a triangle
        val loop = YtSubpath((0 until 36).map { val a = it * Math.PI / 18; YtPoint((50 * Math.cos(a)).toFloat(), (50 * Math.sin(a)).toFloat()) })
        val few = YtPathOps.simplify(loop, 5f)
        assertTrue(few.points.size in 3 until 36)
        assertTrue(YtPathOps.simplify(loop, 1000f).points.size >= 3)
    }

    @Test
    fun `smooth gives tangent handles and relax evens out jitter`() {
        val zig = open(0f, 0f, 10f, 10f, 20f, 0f, 30f, 10f)
        val sm = YtPathOps.smooth(YtPath(listOf(zig)), 1f).subpaths[0]
        val mid = sm.points[1]
        assertEquals(YtPointKind.SMOOTH, mid.kind)
        assertTrue(YtPathOps.aligned(mid))
        // open ends start straight
        assertFalse(sm.points[0].hasIn)
        val relaxed = YtPathOps.relax(zig, 0.5f, 2)
        assertTrue(relaxed.points[1].y < 10f)
        assertEquals(0f, relaxed.points[0].y)
        assertEquals(10f, relaxed.points[3].y)
    }

    @Test
    fun `place and unplace are inverse`() {
        val placed = YtPathOps.place(curve, 200f, 100f, 50f, 80f, 30f)
        val back = YtPathOps.unplace(placed, 200f, 100f, 50f, 80f, 30f)
        val scaled = YtPathOps.scale(curve, 0.5f, 0.8f)
        for ((a, b) in back.subpaths[0].points.zip(scaled.subpaths[0].points)) {
            assertEquals(b.x, a.x, 1e-3f)
            assertEquals(b.y, a.y, 1e-3f)
            assertEquals(b.ox, a.ox, 1e-3f)
        }
        assertEquals(50f, back.w)
    }

    @Test
    fun `hit tests find points, handles and segments`() {
        assertEquals(ref(0, 1), YtPathGeom.hitPoint(curve, 101.0, 1.0, 5.0))
        assertNull(YtPathGeom.hitPoint(curve, 60.0, 60.0, 5.0))
        val h = YtPathGeom.hitHandle(curve, setOf(ref(0, 1)), 120.0, 40.0, 5.0)
        assertEquals(YtPathGeom.HandleSide.OUT, h?.side)
        val c = DoubleArray(8)
        YtPathGeom.segment(curve.subpaths[0], 2, c)
        val p = DoubleArray(2)
        YtPathGeom.pointAt(c, 0.5, p)
        val seg = YtPathGeom.hitSegment(curve, p[0] + 1, p[1], 4.0)
        assertNotNull(seg)
        assertEquals(2, seg!!.segment)
        assertEquals(0.5, seg.t, 0.05)
    }

    @Test
    fun `parametric shapes become paths that fill their box`() {
        for (kind in listOf(YtShapeKind.TRIANGLE, YtShapeKind.POLYGON, YtShapeKind.STAR, YtShapeKind.SQUIRCLE, YtShapeKind.RECT)) {
            val p = YtShapeGeom.pathOf(YtMorphTarget(kind, YtShapeSpec(sides = 6, inner = 0.45f)), 120f, 80f)
            val b = YtPathGeom.bounds(p)!!
            assertEquals("$kind left", 0.0, b[0], 1.5)
            assertEquals("$kind right", 120.0, b[2], 1.5)
            assertEquals("$kind bottom", 80.0, b[3], 1.5)
            assertTrue(kind.name, YtPathGeom.filledArea(p, cells = 120) > 0.0)
        }
        // rounded polygon corners are curves
        assertTrue(YtShapeGeom.pathOf(YtMorphTarget(YtShapeKind.STAR, YtShapeSpec(sides = 5, rounding = 4f)), 100f, 100f).subpaths[0].points.any { it.hasOut })
        // a plain rectangle is four corners, a rounded one has curves
        assertEquals(4, YtShapeGeom.rectPath(10f, 10f, YtCorners()).pointCount)
        val rounded = YtShapeGeom.rectPath(100f, 50f, YtCorners(40f, 8f, 24f, 0f))
        assertTrue(rounded.subpaths[0].points.any { it.hasOut })
        // corners of 500 on a pill are scaled to half the height
        val pill = YtShapeGeom.rectPath(100f, 40f, YtCorners.all(500f))
        assertEquals(100.0 * 40 - (4 - Math.PI) * 400, abs(YtPathGeom.signedArea(pill)), 2.0)
    }

    @Test
    fun `pen taps add corners, a drag pulls handles, the first point closes`() {
        var path = YtPath()
        var step = YtPenModel.touch(path, 0, 0f, 0f, 6f)
        path = step.path
        step = YtPenModel.touch(path, step.sub, 100f, 0f, 6f)
        path = YtPenModel.pull(step.path, step.sub, false, 130f, 20f)
        val p = path.subpaths[0].points[1]
        assertEquals(30f, p.ox)
        assertEquals(-30f, p.ix)
        assertEquals(YtPointKind.SYMMETRIC, p.kind)
        step = YtPenModel.touch(path, step.sub, 50f, 80f, 6f)
        step = YtPenModel.touch(step.path, step.sub, 2f, 1f, 6f)
        assertEquals(YtPenModel.Did.CLOSED, step.did)
        assertTrue(step.path.subpaths[0].closed)
        assertEquals(3, step.path.subpaths[0].points.size)
        // the next touch starts a new subpath
        val next = YtPenModel.touch(step.path, step.sub, 300f, 300f, 6f)
        assertEquals(1, next.sub)
    }

    @Test
    fun `points snap to guides, other points and the grid`() {
        val r = YtPointSnap.snap(103f, 47f, 10f, listOf(100f), emptyList(), emptyList(), listOf(49f), 4f)
        assertEquals(100f, r.x)
        assertEquals(49f, r.y)
        val g = YtPointSnap.snap(21f, 37f, 10f, emptyList(), emptyList(), emptyList(), emptyList(), 2.5f)
        assertEquals(20f, g.x)
        assertEquals(37f, g.y)
        assertNull(g.lineY)
    }
}
