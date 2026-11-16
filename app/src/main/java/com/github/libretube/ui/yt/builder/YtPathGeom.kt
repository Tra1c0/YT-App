package com.github.libretube.ui.yt.builder

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * YT: geometry of vector paths (pure, JVM-tested): segments as cubics, adaptive flattening, signed
 * area, winding numbers, bounds, hit tests and the draw cache the renderer turns into a native path.
 */

/** A flattened subpath: [n] vertices; [seg]/[t] say which segment of the subpath and where on it each vertex lies. */
class YtPoly(
    val xs: DoubleArray,
    val ys: DoubleArray,
    val n: Int,
    val closed: Boolean,
    val seg: IntArray,
    val t: DoubleArray,
) {
    /** Signed area (shoelace; y down, so a clockwise loop on screen is positive). */
    fun area(): Double {
        if (n < 3) return 0.0
        var a = 0.0
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            a += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return a / 2.0
    }

    fun length(): Double {
        var l = 0.0
        for (i in 1 until n) l += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if (closed && n > 1) l += hypot(xs[0] - xs[n - 1], ys[0] - ys[n - 1])
        return l
    }
}

object YtPathGeom {
    /** The cubic of segment [i] of [sub] into [out] (x0, y0, c0x, c0y, c1x, c1y, x1, y1), scaled by [sx], [sy]. */
    fun segment(sub: YtSubpath, i: Int, out: DoubleArray, sx: Double = 1.0, sy: Double = 1.0) {
        val a = sub.points[i]
        val b = sub.points[(i + 1) % sub.points.size]
        out[0] = a.x * sx
        out[1] = a.y * sy
        out[2] = (a.x + a.ox) * sx
        out[3] = (a.y + a.oy) * sy
        out[4] = (b.x + b.ix) * sx
        out[5] = (b.y + b.iy) * sy
        out[6] = b.x * sx
        out[7] = b.y * sy
    }

    /** Whether segment [i] of [sub] is a straight line (no handles on its side of either end). */
    fun straight(sub: YtSubpath, i: Int): Boolean {
        val a = sub.points[i]
        val b = sub.points[(i + 1) % sub.points.size]
        return !a.hasOut && !b.hasIn
    }

    fun pointAt(c: DoubleArray, t: Double, out: DoubleArray) {
        val u = 1 - t
        val a = u * u * u
        val b = 3 * u * u * t
        val d = 3 * u * t * t
        val e = t * t * t
        out[0] = a * c[0] + b * c[2] + d * c[4] + e * c[6]
        out[1] = a * c[1] + b * c[3] + d * c[5] + e * c[7]
    }

    /** The part of cubic [c] from [t0] to [t1] (t0 > t1 gives it reversed) into [out]. */
    fun subCubic(c: DoubleArray, t0: Double, t1: Double, out: DoubleArray) {
        if (t0 > t1) {
            subCubic(c, t1, t0, out)
            // reverse in place
            var tmp = out[0]; out[0] = out[6]; out[6] = tmp
            tmp = out[1]; out[1] = out[7]; out[7] = tmp
            tmp = out[2]; out[2] = out[4]; out[4] = tmp
            tmp = out[3]; out[3] = out[5]; out[5] = tmp
            return
        }
        // split at t1, keep the first part; then split that at t0 / t1
        val first = DoubleArray(8)
        splitAt(c, t1, first, null)
        val rel = if (t1 <= 1e-12) 0.0 else t0 / t1
        splitAt(first, rel, null, out)
    }

    /** de Casteljau split of [c] at [t] into [left] and [right] (either may be null). */
    fun splitAt(c: DoubleArray, t: Double, left: DoubleArray?, right: DoubleArray?) {
        val x01 = c[0] + (c[2] - c[0]) * t
        val y01 = c[1] + (c[3] - c[1]) * t
        val x12 = c[2] + (c[4] - c[2]) * t
        val y12 = c[3] + (c[5] - c[3]) * t
        val x23 = c[4] + (c[6] - c[4]) * t
        val y23 = c[5] + (c[7] - c[5]) * t
        val x012 = x01 + (x12 - x01) * t
        val y012 = y01 + (y12 - y01) * t
        val x123 = x12 + (x23 - x12) * t
        val y123 = y12 + (y23 - y12) * t
        val x = x012 + (x123 - x012) * t
        val y = y012 + (y123 - y012) * t
        if (left != null) {
            left[0] = c[0]; left[1] = c[1]; left[2] = x01; left[3] = y01
            left[4] = x012; left[5] = y012; left[6] = x; left[7] = y
        }
        if (right != null) {
            right[0] = x; right[1] = y; right[2] = x123; right[3] = y123
            right[4] = x23; right[5] = y23; right[6] = c[6]; right[7] = c[7]
        }
    }

    /** How far the control points stray from the chord (an upper bound of the curve's distance to it). */
    private fun flatness(c: DoubleArray): Double {
        val ux = 3 * c[2] - 2 * c[0] - c[6]
        val uy = 3 * c[3] - 2 * c[1] - c[7]
        val vx = 3 * c[4] - c[0] - 2 * c[6]
        val vy = 3 * c[5] - c[1] - 2 * c[7]
        return sqrt(max(ux * ux, vx * vx) + max(uy * uy, vy * vy)) / 4.0
    }

    private class Builder {
        var xs = DoubleArray(64)
        var ys = DoubleArray(64)
        var seg = IntArray(64)
        var t = DoubleArray(64)
        var n = 0

        fun add(x: Double, y: Double, s: Int, tt: Double) {
            if (n == xs.size) {
                val cap = n * 2
                xs = xs.copyOf(cap); ys = ys.copyOf(cap); seg = seg.copyOf(cap); t = t.copyOf(cap)
            }
            xs[n] = x; ys[n] = y; seg[n] = s; t[n] = tt
            n++
        }
    }

    private fun flattenCubic(c: DoubleArray, t0: Double, t1: Double, tol: Double, depth: Int, s: Int, b: Builder) {
        if (depth >= 16 || flatness(c) <= tol) {
            b.add(c[6], c[7], s, t1)
            return
        }
        val l = DoubleArray(8)
        val r = DoubleArray(8)
        splitAt(c, 0.5, l, r)
        val tm = (t0 + t1) / 2
        flattenCubic(l, t0, tm, tol, depth + 1, s, b)
        flattenCubic(r, tm, t1, tol, depth + 1, s, b)
    }

    /**
     * [path]'s subpaths as polylines within [tol] of the curves, scaled by [sx], [sy]. A closed
     * subpath does not repeat its first vertex; subpaths with fewer than two points are left out.
     */
    fun flatten(path: YtPath, tol: Double, sx: Double = 1.0, sy: Double = 1.0): List<YtPoly> = path.subpaths.mapNotNull { flatten(it, tol, sx, sy) }

    fun flatten(sub: YtSubpath, tol: Double, sx: Double = 1.0, sy: Double = 1.0): YtPoly? {
        if (sub.points.size < 2) return null
        val b = Builder()
        val c = DoubleArray(8)
        val first = sub.points[0]
        b.add(first.x * sx, first.y * sy, 0, 0.0)
        val segs = sub.segments
        for (i in 0 until segs) {
            segment(sub, i, c, sx, sy)
            if (straight(sub, i)) b.add(c[6], c[7], i, 1.0) else flattenCubic(c, 0.0, 1.0, max(tol, 1e-9), 0, i, b)
        }
        // a closed loop ends where it started: drop the repeated vertex
        if (sub.closed && b.n > 1) b.n--
        return YtPoly(b.xs, b.ys, b.n, sub.closed, b.seg, b.t)
    }

    /** Winding number of [polys] (each taken as closed) around (x, y). */
    fun winding(polys: List<YtPoly>, x: Double, y: Double): Int {
        var w = 0
        for (p in polys) {
            val n = p.n
            if (n < 2) continue
            for (i in 0 until n) {
                val j = if (i + 1 == n) 0 else i + 1
                val y0 = p.ys[i]
                val y1 = p.ys[j]
                if (y0 <= y) {
                    if (y1 > y && cross(p.xs[i], y0, p.xs[j], y1, x, y) > 0) w++
                } else if (y1 <= y && cross(p.xs[i], y0, p.xs[j], y1, x, y) < 0) {
                    w--
                }
            }
        }
        return w
    }

    private fun cross(x0: Double, y0: Double, x1: Double, y1: Double, x: Double, y: Double) = (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0)

    fun inside(polys: List<YtPoly>, rule: YtFillRule, x: Double, y: Double): Boolean {
        val w = winding(polys, x, y)
        return if (rule == YtFillRule.EVEN_ODD) w and 1 != 0 else w != 0
    }

    /** The filled area of [path] (its fill rule), sampled on a grid of [cells]² inside its bounds: for tests and checks. */
    fun filledArea(path: YtPath, tol: Double = 0.01, cells: Int = 400): Double {
        val polys = flatten(path, tol)
        val b = bounds(polys) ?: return 0.0
        val w = b[2] - b[0]
        val h = b[3] - b[1]
        if (w <= 0 || h <= 0) return 0.0
        var hits = 0
        for (i in 0 until cells) {
            val y = b[1] + (i + 0.5) * h / cells
            for (j in 0 until cells) {
                val x = b[0] + (j + 0.5) * w / cells
                if (inside(polys, path.fillRule, x, y)) hits++
            }
        }
        return hits * (w * h) / (cells.toDouble() * cells)
    }

    /** The exact filled area when the subpaths do not overlap each other: |sum of signed areas| per loop with holes subtracted. */
    fun signedArea(path: YtPath, tol: Double = 0.01): Double = flatten(path, tol).sumOf { it.area() }

    /** (left, top, right, bottom) of the flattened [polys], or null when empty. */
    fun bounds(polys: List<YtPoly>): DoubleArray? {
        var l = Double.MAX_VALUE
        var t = Double.MAX_VALUE
        var r = -Double.MAX_VALUE
        var b = -Double.MAX_VALUE
        var any = false
        for (p in polys) for (i in 0 until p.n) {
            any = true
            l = min(l, p.xs[i]); r = max(r, p.xs[i]); t = min(t, p.ys[i]); b = max(b, p.ys[i])
        }
        return if (any) doubleArrayOf(l, t, r, b) else null
    }

    /** Bounds of [path]'s curves (flattened finely), or null when it has no segment. */
    fun bounds(path: YtPath): DoubleArray? = bounds(flatten(path, 0.05))

    // ---- hit tests (path units) ----

    /** A point of the path: subpath [sub], index [index]. */
    data class Ref(val sub: Int, val index: Int)

    enum class HandleSide { IN, OUT }

    data class HandleRef(val sub: Int, val index: Int, val side: HandleSide)

    data class SegmentHit(val sub: Int, val segment: Int, val t: Double, val distance: Double)

    /** The anchor nearest to (x, y) within [slop], scaled by [sx], [sy] (frame px per unit). */
    fun hitPoint(path: YtPath, x: Double, y: Double, slop: Double, sx: Double = 1.0, sy: Double = 1.0): Ref? {
        var best: Ref? = null
        var bestD = slop
        path.subpaths.forEachIndexed { si, s ->
            s.points.forEachIndexed { pi, p ->
                val d = hypot(p.x * sx - x, p.y * sy - y)
                if (d <= bestD) {
                    bestD = d
                    best = Ref(si, pi)
                }
            }
        }
        return best
    }

    /** The handle end nearest to (x, y) within [slop], among the handles of [shown] points only. */
    fun hitHandle(path: YtPath, shown: Set<Ref>, x: Double, y: Double, slop: Double, sx: Double = 1.0, sy: Double = 1.0): HandleRef? {
        var best: HandleRef? = null
        var bestD = slop
        for (r in shown) {
            val p = path.subpaths.getOrNull(r.sub)?.points?.getOrNull(r.index) ?: continue
            if (p.hasIn) {
                val d = hypot((p.x + p.ix) * sx - x, (p.y + p.iy) * sy - y)
                if (d <= bestD) { bestD = d; best = HandleRef(r.sub, r.index, HandleSide.IN) }
            }
            if (p.hasOut) {
                val d = hypot((p.x + p.ox) * sx - x, (p.y + p.oy) * sy - y)
                if (d <= bestD) { bestD = d; best = HandleRef(r.sub, r.index, HandleSide.OUT) }
            }
        }
        return best
    }

    /** The segment passing nearest to (x, y) within [slop], and where on it. */
    fun hitSegment(path: YtPath, x: Double, y: Double, slop: Double, sx: Double = 1.0, sy: Double = 1.0): SegmentHit? {
        var best: SegmentHit? = null
        val c = DoubleArray(8)
        val p = DoubleArray(2)
        path.subpaths.forEachIndexed { si, s ->
            for (i in 0 until s.segments) {
                segment(s, i, c, sx, sy)
                // a coarse scan, then a refinement around the best sample
                var bt = 0.0
                var bd = Double.MAX_VALUE
                val steps = 24
                for (k in 0..steps) {
                    val t = k.toDouble() / steps
                    pointAt(c, t, p)
                    val d = hypot(p[0] - x, p[1] - y)
                    if (d < bd) { bd = d; bt = t }
                }
                var lo = max(0.0, bt - 1.0 / steps)
                var hi = min(1.0, bt + 1.0 / steps)
                repeat(20) {
                    val m1 = lo + (hi - lo) / 3
                    val m2 = hi - (hi - lo) / 3
                    pointAt(c, m1, p)
                    val d1 = hypot(p[0] - x, p[1] - y)
                    pointAt(c, m2, p)
                    val d2 = hypot(p[0] - x, p[1] - y)
                    if (d1 < d2) hi = m2 else lo = m1
                }
                val t = (lo + hi) / 2
                pointAt(c, t, p)
                val d = hypot(p[0] - x, p[1] - y)
                if (d <= slop && (best == null || d < best!!.distance)) best = SegmentHit(si, i, t, d)
            }
        }
        return best
    }

    /** Direction angle (degrees) of the polyline from (x0, y0) to (x1, y1). */
    fun angleDeg(x0: Double, y0: Double, x1: Double, y1: Double): Double = Math.toDegrees(atan2(y1 - y0, x1 - x0))

    /** Distance from (px, py) to the segment (ax, ay)–(bx, by). */
    fun distToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 <= 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    fun nearlyZero(v: Double) = abs(v) < 1e-9
}

/**
 * The draw commands of a path, rebuilt only when the path or the box changes (the renderer keeps
 * one per node and turns it into a native path without allocating): per subpath MOVE x y, then per
 * segment LINE x y or CUBIC c0x c0y c1x c1y x y, then CLOSE for closed ones.
 */
class YtPathCache {
    var cmds = FloatArray(256)
        private set
    var size = 0
        private set
    private var lastPath: YtPath? = null
    private var lastW = -1f
    private var lastH = -1f

    /** Bounds of the anchors and handles (box units), valid after a rebuild. */
    val bounds = FloatArray(4)

    /** Rebuilds for [path] stretched to [w] × [h]; returns false when nothing changed. */
    fun rebuild(path: YtPath, w: Float, h: Float): Boolean {
        if (path === lastPath && w == lastW && h == lastH) return false
        lastPath = path
        lastW = w
        lastH = h
        val sx = if (path.w > 0f) w / path.w else 1f
        val sy = if (path.h > 0f) h / path.h else 1f
        size = 0
        bounds[0] = Float.MAX_VALUE; bounds[1] = Float.MAX_VALUE; bounds[2] = -Float.MAX_VALUE; bounds[3] = -Float.MAX_VALUE
        for (s in path.subpaths) {
            val pts = s.points
            if (pts.size < 2) continue
            ensure(3 + pts.size * 7 + 1)
            val a = pts[0]
            put(MOVE); put(a.x * sx); put(a.y * sy)
            grow(a.x * sx, a.y * sy)
            val segs = s.segments
            for (i in 0 until segs) {
                val p = pts[i]
                val q = pts[(i + 1) % pts.size]
                if (!p.hasOut && !q.hasIn) {
                    put(LINE); put(q.x * sx); put(q.y * sy)
                } else {
                    put(CUBIC)
                    put((p.x + p.ox) * sx); put((p.y + p.oy) * sy)
                    put((q.x + q.ix) * sx); put((q.y + q.iy) * sy)
                    put(q.x * sx); put(q.y * sy)
                    grow((p.x + p.ox) * sx, (p.y + p.oy) * sy)
                    grow((q.x + q.ix) * sx, (q.y + q.iy) * sy)
                }
                grow(q.x * sx, q.y * sy)
            }
            if (s.closed) put(CLOSE)
        }
        return true
    }

    /** Forgets the last path, so the next rebuild runs (another box or a new path object with equal content). */
    fun invalidate() {
        lastPath = null
    }

    private fun ensure(extra: Int) {
        if (size + extra > cmds.size) cmds = cmds.copyOf(max(cmds.size * 2, size + extra))
    }

    private fun put(v: Float) {
        cmds[size++] = v
    }

    private fun grow(x: Float, y: Float) {
        if (x < bounds[0]) bounds[0] = x
        if (y < bounds[1]) bounds[1] = y
        if (x > bounds[2]) bounds[2] = x
        if (y > bounds[3]) bounds[3] = y
    }

    /** Replays the commands into a path builder (no allocation). */
    inline fun replay(move: (Float, Float) -> Unit, line: (Float, Float) -> Unit, cubic: (Float, Float, Float, Float, Float, Float) -> Unit, close: () -> Unit) {
        var i = 0
        val c = cmds
        while (i < size) {
            when (c[i]) {
                MOVE -> { move(c[i + 1], c[i + 2]); i += 3 }
                LINE -> { line(c[i + 1], c[i + 2]); i += 3 }
                CUBIC -> { cubic(c[i + 1], c[i + 2], c[i + 3], c[i + 4], c[i + 5], c[i + 6]); i += 7 }
                else -> { close(); i += 1 }
            }
        }
    }

    companion object {
        const val MOVE = 0f
        const val LINE = 1f
        const val CUBIC = 2f
        const val CLOSE = 3f
    }
}
