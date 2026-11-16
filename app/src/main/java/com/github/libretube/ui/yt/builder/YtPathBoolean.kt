package com.github.libretube.ui.yt.builder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/*
 * YT: boolean operations on paths (pure, JVM-tested; the editor uses the same code, so what the
 * tests check is what the user gets).
 *
 * Method (a planar overlay): every operand is flattened within a tolerance into straight edges
 * that remember which curve of the input and which stretch of it they came from. All edges are
 * cut where they cross or touch each other, so no two edges cross any more. For every remaining
 * edge, a point just left and just right of its middle is tested against each operand (its own
 * fill rule, by winding number); where the operation's result differs between the two sides, the
 * edge is part of the result's outline, turned so the result lies on its left. Those edges are
 * chained into loops. Runs of edges that came from one input curve become that curve's piece
 * again (de Casteljau), so circles stay curves instead of turning into many corners.
 *
 * The same overlay with one operand and its own winding turns an outline of overlapping pieces
 * into one clean shape: that is how a stroke becomes a shape ([outline]).
 */
object YtPathBoolean {
    enum class Op { UNION, SUBTRACT, INTERSECT, EXCLUDE }

    /** The result of [op] on [paths] (all in one coordinate space; the first is the base of SUBTRACT). */
    fun op(op: Op, paths: List<YtPath>, tolerance: Double? = null): YtPath {
        if (paths.isEmpty()) return YtPath()
        val test: (BooleanArray) -> Boolean = when (op) {
            Op.UNION -> { a -> a.any { it } }
            Op.INTERSECT -> { a -> a.all { it } }
            Op.SUBTRACT -> { a -> a[0] && (1 until a.size).none { a[it] } }
            Op.EXCLUDE -> { a -> a.count { it } % 2 == 1 }
        }
        return Overlay(paths.map { it to it.fillRule }, tolerance).run(test)
    }

    /** The area [paths] cover together under the non-zero rule (pieces may overlap and turn either way). */
    fun merge(paths: List<YtPath>, tolerance: Double? = null): YtPath =
        Overlay(listOf(YtPathOps.combine(paths) to YtFillRule.NON_ZERO), tolerance).run { it[0] }

    /**
     * The area a stroke of [width] along [path] covers, as a filled shape (round joins and caps,
     * as the renderer strokes paths).
     */
    fun outline(path: YtPath, width: Float, tolerance: Double = 0.05): YtPath {
        val r = width / 2.0
        if (r <= 0.0) return YtPath(w = path.w, h = path.h)
        val polys = YtPathGeom.flatten(path, tolerance)
        val pieces = ArrayList<YtSubpath>()
        val arcStep = 2 * acos((1 - tolerance / r).coerceIn(-1.0, 1.0)).coerceIn(PI / 48, PI / 6)
        fun loop(xs: DoubleArray, ys: DoubleArray, n: Int) {
            // every piece runs the same way round, so overlaps add up under non-zero
            var a = 0.0
            for (i in 0 until n) {
                val j = (i + 1) % n
                a += xs[i] * ys[j] - xs[j] * ys[i]
            }
            val pts = (0 until n).map { YtPoint(xs[it].toFloat(), ys[it].toFloat()) }
            pieces += YtSubpath(if (a < 0) pts.reversed() else pts, closed = true)
        }
        fun disc(cx: Double, cy: Double, from: Double, to: Double) {
            // a fan: the centre and an arc from angle [from] to [to]
            val span = to - from
            val steps = ceil(abs(span) / arcStep).toInt().coerceIn(2, 96)
            val xs = DoubleArray(steps + 2)
            val ys = DoubleArray(steps + 2)
            xs[0] = cx; ys[0] = cy
            for (k in 0..steps) {
                val ang = from + span * k / steps
                xs[k + 1] = cx + cos(ang) * r
                ys[k + 1] = cy + sin(ang) * r
            }
            loop(xs, ys, steps + 2)
        }
        for (p in polys) {
            val n = p.n
            if (n < 2) continue
            val segs = if (p.closed) n else n - 1
            val angles = DoubleArray(segs)
            for (i in 0 until segs) {
                val j = (i + 1) % n
                val dx = p.xs[j] - p.xs[i]
                val dy = p.ys[j] - p.ys[i]
                val len = hypot(dx, dy)
                angles[i] = atan2(dy, dx)
                if (len <= 1e-12) continue
                val nx = -dy / len * r
                val ny = dx / len * r
                loop(
                    doubleArrayOf(p.xs[i] + nx, p.xs[j] + nx, p.xs[j] - nx, p.xs[i] - nx),
                    doubleArrayOf(p.ys[i] + ny, p.ys[j] + ny, p.ys[j] - ny, p.ys[i] - ny),
                    4,
                )
            }
            // joins: the outer side of each turn as an arc fan
            val joins = if (p.closed) 0 until n else 1 until n - 1
            for (v in joins) {
                val inSeg = (v - 1 + segs) % segs
                val outSeg = v % segs
                if (!p.closed && (v == 0 || v == n - 1)) continue
                var turn = angles[outSeg] - angles[inSeg]
                while (turn > PI) turn -= 2 * PI
                while (turn < -PI) turn += 2 * PI
                if (abs(turn) < 0.02) continue
                // the outer side is to the right of a left turn and to the left of a right turn
                val side = if (turn > 0) -PI / 2 else PI / 2
                disc(p.xs[v], p.ys[v], angles[inSeg] + side, angles[inSeg] + side + turn)
            }
            if (!p.closed) {
                // round caps: half discs at both ends
                val a0 = angles[0]
                disc(p.xs[0], p.ys[0], a0 + PI / 2, a0 + 3 * PI / 2)
                val a1 = angles[segs - 1]
                disc(p.xs[n - 1], p.ys[n - 1], a1 - PI / 2, a1 + PI / 2)
            }
        }
        if (pieces.isEmpty()) return YtPath(w = path.w, h = path.h)
        val merged = Overlay(listOf(YtPath(pieces) to YtFillRule.NON_ZERO), tolerance).run { it[0] }
        return YtPathOps.simplify(merged, (tolerance / 2).toFloat()).copy(w = path.w, h = path.h)
    }

    /** One run of the overlay. */
    private class Overlay(private val operands: List<Pair<YtPath, YtFillRule>>, tolerance: Double?) {
        // input curves: 8 doubles each, and whether they are straight
        private val curves = ArrayList<DoubleArray>()
        private val curveLine = ArrayList<Boolean>()

        // edges (growable columns)
        private var n = 0
        private var ex0 = DoubleArray(256)
        private var ey0 = DoubleArray(256)
        private var ex1 = DoubleArray(256)
        private var ey1 = DoubleArray(256)
        private var eCurve = IntArray(256)
        private var eT0 = DoubleArray(256)
        private var eT1 = DoubleArray(256)
        private val opStart = IntArray(operands.size + 1)

        private var scale = 1.0
        private var minX = 0.0
        private var minY = 0.0
        private val tol: Double

        init {
            // bounds first (control points included), for the tolerance and the snapping grid
            var l = Double.MAX_VALUE; var t = Double.MAX_VALUE; var r = -Double.MAX_VALUE; var b = -Double.MAX_VALUE
            for ((p, _) in operands) for (s in p.subpaths) for (q in s.points) {
                l = min(l, q.x.toDouble()); r = max(r, q.x.toDouble()); t = min(t, q.y.toDouble()); b = max(b, q.y.toDouble())
            }
            if (l <= r) {
                scale = max(max(r - l, b - t), 1e-6)
                minX = l - scale
                minY = t - scale
            }
            tol = tolerance ?: max(scale * 1e-3, 1e-4)
            operands.forEachIndexed { k, (p, _) ->
                opStart[k] = n
                addOperand(p)
            }
            opStart[operands.size] = n
        }

        private fun grow() {
            val cap = ex0.size * 2
            ex0 = ex0.copyOf(cap); ey0 = ey0.copyOf(cap); ex1 = ex1.copyOf(cap); ey1 = ey1.copyOf(cap)
            eCurve = eCurve.copyOf(cap); eT0 = eT0.copyOf(cap); eT1 = eT1.copyOf(cap)
        }

        private fun edge(x0: Double, y0: Double, x1: Double, y1: Double, curve: Int, t0: Double, t1: Double) {
            if (x0 == x1 && y0 == y1) return
            if (n == ex0.size) grow()
            ex0[n] = x0; ey0[n] = y0; ex1[n] = x1; ey1[n] = y1
            eCurve[n] = curve; eT0[n] = t0; eT1[n] = t1
            n++
        }

        private fun addOperand(p: YtPath) {
            for (s in p.subpaths) {
                if (s.points.size < 2) continue
                for (i in 0 until s.segments) {
                    val c = DoubleArray(8)
                    YtPathGeom.segment(s, i, c)
                    val id = curves.size
                    val line = YtPathGeom.straight(s, i)
                    curves += c
                    curveLine += line
                    if (line) {
                        edge(c[0], c[1], c[6], c[7], id, 0.0, 1.0)
                    } else {
                        val one = YtSubpath(listOf(YtPoint(c[0].toFloat(), c[1].toFloat(), ox = (c[2] - c[0]).toFloat(), oy = (c[3] - c[1]).toFloat()), YtPoint(c[6].toFloat(), c[7].toFloat(), ix = (c[4] - c[6]).toFloat(), iy = (c[5] - c[7]).toFloat())), closed = false)
                        val poly = YtPathGeom.flatten(one, tol) ?: continue
                        var px = c[0]
                        var py = c[1]
                        var pt = 0.0
                        for (k in 1 until poly.n) {
                            val x = if (k == poly.n - 1) c[6] else poly.xs[k]
                            val y = if (k == poly.n - 1) c[7] else poly.ys[k]
                            edge(px, py, x, y, id, pt, poly.t[k])
                            px = x; py = y; pt = poly.t[k]
                        }
                    }
                }
                if (!s.closed) {
                    // filled as if closed: the closing line is part of the outline
                    val a = s.points.last()
                    val b = s.points.first()
                    val id = curves.size
                    curves += doubleArrayOf(a.x.toDouble(), a.y.toDouble(), a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble(), b.x.toDouble(), b.y.toDouble())
                    curveLine += true
                    edge(a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble(), id, 0.0, 1.0)
                }
            }
        }

        // ---- cutting ----

        private class Cut(val t: Double, val x: Double, val y: Double)

        private val cuts = HashMap<Int, ArrayList<Cut>>()

        private fun cut(e: Int, t: Double, x: Double, y: Double) {
            if (t <= 0.0 || t >= 1.0) return
            cuts.getOrPut(e) { ArrayList(2) } += Cut(t, x, y)
        }

        private fun intersectAll() {
            val eps = scale * 1e-9
            val order = (0 until n).sortedBy { min(ex0[it], ex1[it]) }
            val mnx = DoubleArray(n) { min(ex0[it], ex1[it]) }
            val mxx = DoubleArray(n) { max(ex0[it], ex1[it]) }
            val mny = DoubleArray(n) { min(ey0[it], ey1[it]) }
            val mxy = DoubleArray(n) { max(ey0[it], ey1[it]) }
            for (ii in order.indices) {
                val i = order[ii]
                for (jj in ii + 1 until order.size) {
                    val j = order[jj]
                    if (mnx[j] > mxx[i] + eps) break
                    if (mny[j] > mxy[i] + eps || mxy[j] < mny[i] - eps) continue
                    intersect(i, j, eps)
                }
            }
        }

        private fun intersect(i: Int, j: Int, eps: Double) {
            val ax = ex0[i]; val ay = ey0[i]; val bx = ex1[i]; val by = ey1[i]
            val cx = ex0[j]; val cy = ey0[j]; val dx = ex1[j]; val dy = ey1[j]
            val rx = bx - ax; val ry = by - ay
            val sx = dx - cx; val sy = dy - cy
            val lenR = hypot(rx, ry)
            val lenS = hypot(sx, sy)
            if (lenR <= 0.0 || lenS <= 0.0) return
            val den = rx * sy - ry * sx
            val qx = cx - ax
            val qy = cy - ay
            val et = eps / lenR
            val eu = eps / lenS
            if (abs(den) > 1e-12 * lenR * lenS) {
                var t = (qx * sy - qy * sx) / den
                var u = (qx * ry - qy * rx) / den
                if (t < -et || t > 1 + et || u < -eu || u > 1 + eu) return
                val tEnd = t <= et || t >= 1 - et
                val uEnd = u <= eu || u >= 1 - eu
                val px: Double
                val py: Double
                when {
                    tEnd -> {
                        t = if (t <= et) 0.0 else 1.0
                        px = if (t == 0.0) ax else bx
                        py = if (t == 0.0) ay else by
                        if (uEnd) u = if (u <= eu) 0.0 else 1.0
                    }
                    uEnd -> {
                        u = if (u <= eu) 0.0 else 1.0
                        px = if (u == 0.0) cx else dx
                        py = if (u == 0.0) cy else dy
                    }
                    else -> {
                        px = ax + t * rx
                        py = ay + t * ry
                    }
                }
                cut(i, t, px, py)
                cut(j, u, px, py)
                return
            }
            // parallel: only collinear overlaps matter; each cuts the other at its ends
            if (abs(qx * ry - qy * rx) / lenR > eps) return
            val l2r = lenR * lenR
            val l2s = lenS * lenS
            val tc = ((cx - ax) * rx + (cy - ay) * ry) / l2r
            val td = ((dx - ax) * rx + (dy - ay) * ry) / l2r
            if (tc > et && tc < 1 - et) cut(i, tc, cx, cy)
            if (td > et && td < 1 - et) cut(i, td, dx, dy)
            val ua = ((ax - cx) * sx + (ay - cy) * sy) / l2s
            val ub = ((bx - cx) * sx + (by - cy) * sy) / l2s
            if (ua > eu && ua < 1 - eu) cut(j, ua, ax, ay)
            if (ub > eu && ub < 1 - eu) cut(j, ub, bx, by)
        }

        // ---- vertices and unique pieces ----

        private val vx = ArrayList<Double>()
        private val vy = ArrayList<Double>()
        private val vIndex = HashMap<Long, Int>()

        private fun vertex(x: Double, y: Double): Int {
            val g = scale * 1e-7
            val kx = ((x - minX) / g).roundToLong()
            val ky = ((y - minY) / g).roundToLong()
            val key = (kx shl 32) xor (ky and 0xffffffffL)
            return vIndex.getOrPut(key) {
                vx += x
                vy += y
                vx.size - 1
            }
        }

        private class Piece(val a: Int, val b: Int, val curve: Int, val t0: Double, val t1: Double)

        private fun pieces(): List<Piece> {
            val out = ArrayList<Piece>()
            val seen = HashMap<Long, Int>()
            for (e in 0 until n) {
                val list = ArrayList<Cut>()
                list += Cut(0.0, ex0[e], ey0[e])
                cuts[e]?.let { list.addAll(it) }
                list += Cut(1.0, ex1[e], ey1[e])
                list.sortBy { it.t }
                var prev = vertex(list[0].x, list[0].y)
                var prevT = 0.0
                for (k in 1 until list.size) {
                    val c = list[k]
                    val v = vertex(c.x, c.y)
                    if (v == prev) continue
                    val key = if (prev < v) (prev.toLong() shl 32) or v.toLong() else (v.toLong() shl 32) or prev.toLong()
                    if (key !in seen) {
                        seen[key] = out.size
                        val t0 = eT0[e] + (eT1[e] - eT0[e]) * prevT
                        val t1 = eT0[e] + (eT1[e] - eT0[e]) * c.t
                        out += Piece(prev, v, eCurve[e], t0, t1)
                    }
                    prev = v
                    prevT = c.t
                }
            }
            return out
        }

        /** Winding number of operand [k] around (x, y). */
        private fun winding(k: Int, x: Double, y: Double): Int {
            var w = 0
            for (e in opStart[k] until opStart[k + 1]) {
                val y0 = ey0[e]
                val y1 = ey1[e]
                if (y0 <= y) {
                    if (y1 > y && (ex1[e] - ex0[e]) * (y - y0) - (x - ex0[e]) * (y1 - y0) > 0) w++
                } else if (y1 <= y && (ex1[e] - ex0[e]) * (y - y0) - (x - ex0[e]) * (y1 - y0) < 0) {
                    w--
                }
            }
            return w
        }

        private fun inside(k: Int, x: Double, y: Double): Boolean {
            val w = winding(k, x, y)
            return if (operands[k].second == YtFillRule.EVEN_ODD) w and 1 != 0 else w != 0
        }

        /** A directed piece of the result: [a] → [b], the result on its left. */
        private class Kept(val a: Int, val b: Int, val curve: Int, val t0: Double, val t1: Double) {
            var used = false
        }

        fun run(test: (BooleanArray) -> Boolean): YtPath {
            if (n == 0) return YtPath()
            intersectAll()
            val pieces = pieces()
            val left = BooleanArray(operands.size)
            val right = BooleanArray(operands.size)
            val kept = ArrayList<Kept>()
            for (p in pieces) {
                val ax = vx[p.a]; val ay = vy[p.a]; val bx = vx[p.b]; val by = vy[p.b]
                val dx = bx - ax
                val dy = by - ay
                val len = hypot(dx, dy)
                if (len <= 0.0) continue
                val off = min(len * 0.25, scale * 1e-6)
                val mx = (ax + bx) / 2
                val my = (ay + by) / 2
                val nx = -dy / len * off
                val ny = dx / len * off
                for (k in operands.indices) {
                    left[k] = inside(k, mx + nx, my + ny)
                    right[k] = inside(k, mx - nx, my - ny)
                }
                val l = test(left)
                val r = test(right)
                if (l == r) continue
                kept += if (l) Kept(p.a, p.b, p.curve, p.t0, p.t1) else Kept(p.b, p.a, p.curve, p.t1, p.t0)
            }
            val outs = HashMap<Int, ArrayList<Kept>>()
            for (k in kept) outs.getOrPut(k.a) { ArrayList(2) } += k
            val loops = ArrayList<List<Kept>>()
            for (start in kept) {
                if (start.used) continue
                val loop = ArrayList<Kept>()
                var cur = start
                var guard = 0
                while (guard++ < kept.size + 1) {
                    cur.used = true
                    loop += cur
                    if (cur.b == start.a) break
                    val inDx = vx[cur.b] - vx[cur.a]
                    val inDy = vy[cur.b] - vy[cur.a]
                    // the sharpest turn towards the result's side keeps touching loops apart
                    val next = outs[cur.b]?.filter { !it.used }?.maxByOrNull { o ->
                        val odx = vx[o.b] - vx[o.a]
                        val ody = vy[o.b] - vy[o.a]
                        atan2(inDx * ody - inDy * odx, inDx * odx + inDy * ody)
                    } ?: break
                    cur = next
                }
                if (loop.size >= 3 && loop.last().b == loop.first().a) loops += loop
            }
            val subs = loops.mapNotNull { toSubpath(it) }
            val b = YtPathGeom.bounds(YtPathGeom.flatten(YtPath(subs), tol))
            val w = if (b == null) 1f else max((b[2] - b[0]).toFloat(), YtVecLimits.MIN_BOX)
            val h = if (b == null) 1f else max((b[3] - b[1]).toFloat(), YtVecLimits.MIN_BOX)
            return YtPath(subs, YtFillRule.NON_ZERO, w, h)
        }

        /** A loop of pieces as a subpath: runs along one input curve become a piece of that curve again. */
        private fun toSubpath(loop: List<Kept>): YtSubpath? {
            val m = loop.size
            fun continues(a: Kept, b: Kept) = a.curve == b.curve && abs(a.t1 - b.t0) < 1e-9
            var start = -1
            for (i in 0 until m) {
                if (!continues(loop[(i - 1 + m) % m], loop[i])) {
                    start = i
                    break
                }
            }
            class Run(val from: Int, val to: Int, val curve: Int, val t0: Double, val t1: Double)
            val runs = ArrayList<Run>()
            if (start >= 0) {
                var i = start
                var count = 0
                while (count < m) {
                    val first = loop[i % m]
                    var last = first
                    var k = 1
                    while (k < m - count && continues(last, loop[(i + k) % m])) {
                        last = loop[(i + k) % m]
                        k++
                    }
                    runs += Run(first.a, last.b, first.curve, first.t0, last.t1)
                    i += k
                    count += k
                }
            }
            if (runs.size < 2) {
                // one curve all round (or nothing to merge): the vertices as they are
                val pts = loop.map { YtPoint(vx[it.a].toFloat(), vy[it.a].toFloat()) }
                return YtPathOps.dropCollinear(YtSubpath(pts, closed = true)).takeIf { it.points.size >= 3 }
            }
            val sub = DoubleArray(8)
            val outH = FloatArray(runs.size * 2)
            val inH = FloatArray(runs.size * 2)
            runs.forEachIndexed { i, r ->
                val j = (i + 1) % runs.size
                if (curveLine[r.curve] || r.t0 == r.t1) return@forEachIndexed
                YtPathGeom.subCubic(curves[r.curve], r.t0, r.t1, sub)
                val p0x = vx[r.from]; val p0y = vy[r.from]
                val p3x = vx[r.to]; val p3y = vy[r.to]
                // the piece's ends are the overlay's vertices (within the tolerance of the curve)
                outH[2 * i] = (sub[2] - sub[0]).toFloat()
                outH[2 * i + 1] = (sub[3] - sub[1]).toFloat()
                inH[2 * j] = (sub[4] - sub[6]).toFloat()
                inH[2 * j + 1] = (sub[5] - sub[7]).toFloat()
                if (hypot(p0x - sub[0], p0y - sub[1]) > tol * 4 || hypot(p3x - sub[6], p3y - sub[7]) > tol * 4) {
                    // a stretch that does not match the curve after all: keep it straight
                    outH[2 * i] = 0f; outH[2 * i + 1] = 0f; inH[2 * j] = 0f; inH[2 * j + 1] = 0f
                }
            }
            val pts = runs.mapIndexed { i, r ->
                val p = YtPoint(vx[r.from].toFloat(), vy[r.from].toFloat(), inH[2 * i], inH[2 * i + 1], outH[2 * i], outH[2 * i + 1])
                if (YtPathOps.aligned(p)) p.copy(kind = YtPointKind.SMOOTH) else p
            }
            return YtPathOps.dropCollinear(YtSubpath(pts, closed = true), tol * 0.1).takeIf { it.points.size >= 2 }
        }
    }
}
