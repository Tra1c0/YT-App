package com.github.libretube.ui.yt.builder

import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.MutableCubic
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sign

/*
 * YT: shape morphing (pure, JVM-tested). Two parametric shapes morph with androidx.graphics.shapes
 * (Morph of two RoundedPolygons: exact corners all the way). Any other pair is turned into paths,
 * each subpath resampled by arc length to the same number of points, the second aligned to the
 * first (same direction, the start point that moves least), then interpolated point by point.
 * Pairs that do not match (another number of subpaths, a hole on one side, open against closed)
 * still morph without a jump (left-over subpaths grow from or shrink to their centre), but are
 * marked "angenähert" in the editor, since no exact morph exists.
 */
object YtMorph {
    enum class Kind {
        /** Both parametric: androidx.graphics.shapes. */
        SHAPES,

        /** Matching paths, resampled. */
        PATHS,

        /** No exact match: resampled with left-overs growing/shrinking. */
        APPROX,
    }

    /** A pair of resampled subpaths: [a] and [b] hold x, y of [n] points (dp of the box). */
    class Pair(val a: FloatArray, val b: FloatArray, val n: Int, val closed: Boolean)

    class Plan(val kind: Kind, val w: Float, val h: Float, val shapes: Morph?, val pairs: List<Pair>, val rule: YtFillRule) {
        val approximated: Boolean get() = kind == Kind.APPROX
        private val cubic = MutableCubic()

        // the cubic callback's inputs as fields, so a frame allocates nothing
        private var out: YtPathSink? = null
        private var fx = 1f
        private var fy = 1f
        private var started = false
        private val each: (MutableCubic) -> Unit = { c ->
            val s = out
            if (s != null) {
                if (!started) {
                    s.move(c.anchor0X * fx, c.anchor0Y * fy)
                    started = true
                }
                s.cubic(c.control0X * fx, c.control0Y * fy, c.control1X * fx, c.control1Y * fy, c.anchor1X * fx, c.anchor1Y * fy)
            }
        }

        /** The outline at [t] (0..1) scaled by [sx], [sy] into the sink (no allocation per call). */
        fun write(t: Float, sx: Float, sy: Float, sink: YtPathSink) {
            val p = t.coerceIn(0f, 1f)
            val m = shapes
            if (m != null) {
                out = sink
                fx = sx
                fy = sy
                started = false
                m.forEachCubic(p, cubic, each)
                if (started) sink.close()
                out = null
                return
            }
            for (pr in pairs) {
                val a = pr.a
                val b = pr.b
                for (i in 0 until pr.n) {
                    val x = (a[2 * i] + (b[2 * i] - a[2 * i]) * p) * sx
                    val y = (a[2 * i + 1] + (b[2 * i + 1] - a[2 * i + 1]) * p) * sy
                    if (i == 0) sink.move(x, y) else sink.line(x, y)
                }
                if (pr.closed) sink.close()
            }
        }
    }

    /** Points per dp of outline length when resampling (and the bounds of the count). */
    private const val STEP_DP = 2f
    private const val MIN_POINTS = 16
    private const val MAX_POINTS = 240

    /** How the morph of [from] into [to] in a [w] × [h] dp box works. */
    fun plan(from: YtMorphTarget, to: YtMorphTarget, w: Float, h: Float): Plan {
        if (YtShapeGeom.polygonal(from) && YtShapeGeom.polygonal(to)) {
            val a = YtShapeGeom.polygonOf(from, w, h)
            val b = YtShapeGeom.polygonOf(to, w, h)
            if (a != null && b != null) {
                val m = runCatching { Morph(a, b) }.getOrNull()
                if (m != null) return Plan(Kind.SHAPES, w, h, m, emptyList(), YtFillRule.NON_ZERO)
            }
        }
        val pa = YtShapeGeom.pathOf(from, w, h)
        val pb = YtShapeGeom.pathOf(to, w, h)
        return planPaths(pa, pb, w, h)
    }

    /** Whether two paths morph exactly: as many subpaths of at least two points, open and closed alike. */
    fun compatible(a: YtPath, b: YtPath): Boolean {
        val sa = a.subpaths.filter { it.points.size >= 2 }
        val sb = b.subpaths.filter { it.points.size >= 2 }
        if (sa.size != sb.size || sa.isEmpty()) return false
        return sa.count { it.closed } == sb.count { it.closed }
    }

    fun planPaths(pa: YtPath, pb: YtPath, w: Float, h: Float): Plan {
        val tol = 0.05
        val polysA = YtPathGeom.flatten(pa, tol).filter { it.n >= 2 }
        val polysB = YtPathGeom.flatten(pb, tol).filter { it.n >= 2 }
        val exact = compatible(pa, pb)
        // biggest first, so outlines pair with outlines and holes with holes
        val ra = polysA.sortedByDescending { abs(it.area()) + it.length() * 1e-6 }
        val rb = polysB.sortedByDescending { abs(it.area()) + it.length() * 1e-6 }
        val pairs = ArrayList<Pair>()
        var approx = !exact
        val common = minOf(ra.size, rb.size)
        for (i in 0 until common) {
            val a = ra[i]
            val b = rb[i]
            val closed = a.closed && b.closed
            if (a.closed != b.closed) approx = true
            val n = count(a, b)
            val sa = resample(a, n, closed)
            var sb = resample(b, n, closed)
            sb = align(sa, sb, n, closed, a.area(), b.area())
            pairs += Pair(sa, sb, n, closed)
        }
        // left-overs grow from or shrink to their centre
        for (i in common until ra.size) {
            val a = ra[i]
            val n = count(a, a)
            val sa = resample(a, n, a.closed)
            pairs += Pair(sa, centre(sa, n), n, a.closed)
        }
        for (i in common until rb.size) {
            val b = rb[i]
            val n = count(b, b)
            val sb = resample(b, n, b.closed)
            pairs += Pair(centre(sb, n), sb, n, b.closed)
        }
        return Plan(if (approx) Kind.APPROX else Kind.PATHS, w, h, null, pairs, pa.fillRule)
    }

    private fun count(a: YtPoly, b: YtPoly): Int =
        ceil(maxOf(a.length(), b.length()) / STEP_DP).toInt().coerceIn(MIN_POINTS, MAX_POINTS)

    private fun centre(s: FloatArray, n: Int): FloatArray {
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += s[2 * i]
            cy += s[2 * i + 1]
        }
        cx /= n
        cy /= n
        return FloatArray(2 * n) { if (it % 2 == 0) cx else cy }
    }

    /**
     * [n] points spread evenly by arc length along [p], starting at its first vertex. Closed: the
     * loop without repeating the start; open (or a closed loop treated as open): both ends included.
     */
    fun resample(p: YtPoly, n: Int, closed: Boolean): FloatArray {
        val out = FloatArray(2 * n)
        val m = p.n
        // the polyline's vertices, closed loops with their start repeated at the end
        val count = if (p.closed) m + 1 else m
        val cum = DoubleArray(count)
        for (i in 1 until count) {
            val a = (i - 1) % m
            val b = i % m
            cum[i] = cum[i - 1] + hypot(p.xs[b] - p.xs[a], p.ys[b] - p.ys[a])
        }
        val total = cum[count - 1]
        if (total <= 0.0) {
            for (i in 0 until n) {
                out[2 * i] = p.xs[0].toFloat()
                out[2 * i + 1] = p.ys[0].toFloat()
            }
            return out
        }
        var seg = 1
        for (k in 0 until n) {
            val s = if (closed) total * k / n else total * k / (n - 1).coerceAtLeast(1)
            while (seg < count - 1 && cum[seg] < s) seg++
            val a = (seg - 1) % m
            val b = seg % m
            val span = cum[seg] - cum[seg - 1]
            val f = if (span <= 0.0) 0.0 else ((s - cum[seg - 1]) / span).coerceIn(0.0, 1.0)
            out[2 * k] = (p.xs[a] + (p.xs[b] - p.xs[a]) * f).toFloat()
            out[2 * k + 1] = (p.ys[a] + (p.ys[b] - p.ys[a]) * f).toFloat()
        }
        return out
    }

    /** Sum of squared distances between [a] and [b] shifted by [shift] (b read backwards when [rev]). */
    private fun travel(a: FloatArray, b: FloatArray, n: Int, shift: Int, rev: Boolean, closed: Boolean): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val j = if (closed) {
                val k = if (rev) (shift - i) else (shift + i)
                ((k % n) + n) % n
            } else {
                if (rev) n - 1 - i else i
            }
            val dx = (a[2 * i] - b[2 * j]).toDouble()
            val dy = (a[2 * i + 1] - b[2 * j + 1]).toDouble()
            sum += dx * dx + dy * dy
        }
        return sum
    }

    /**
     * [b] reordered to follow [a]: closed loops turn the same way (by the sign of their areas) and
     * start at the point that makes the points travel least; open ones run the shorter way.
     */
    fun align(a: FloatArray, b: FloatArray, n: Int, closed: Boolean, areaA: Double, areaB: Double): FloatArray {
        val out = FloatArray(2 * n)
        if (!closed) {
            val rev = travel(a, b, n, 0, true, false) < travel(a, b, n, 0, false, false)
            for (i in 0 until n) {
                val j = if (rev) n - 1 - i else i
                out[2 * i] = b[2 * j]
                out[2 * i + 1] = b[2 * j + 1]
            }
            return out
        }
        val rev = areaA != 0.0 && areaB != 0.0 && sign(areaA) != sign(areaB)
        var best = 0
        var bestD = Double.MAX_VALUE
        for (s in 0 until n) {
            val d = travel(a, b, n, s, rev, true)
            if (d < bestD) {
                bestD = d
                best = s
            }
        }
        for (i in 0 until n) {
            val k = if (rev) best - i else best + i
            val j = ((k % n) + n) % n
            out[2 * i] = b[2 * j]
            out[2 * i + 1] = b[2 * j + 1]
        }
        return out
    }

    /** The mean travel (dp) of the points from [p]'s start to its end shape: how far the morph moves things. */
    fun meanTravel(p: Plan): Double {
        var sum = 0.0
        var count = 0
        for (pr in p.pairs) for (i in 0 until pr.n) {
            sum += hypot((pr.a[2 * i] - pr.b[2 * i]).toDouble(), (pr.a[2 * i + 1] - pr.b[2 * i + 1]).toDouble())
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }
}

/** Where outlines are written: a native path on Android, a list in tests. */
interface YtPathSink {
    fun move(x: Float, y: Float)
    fun line(x: Float, y: Float)
    fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float)
    fun close()
}
