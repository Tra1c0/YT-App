package com.github.libretube.ui.yt.builder

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * YT: edits of vector paths (pure, JVM-tested). Every function returns a new path and leaves the
 * input alone, so the editor's undo keeps each step. Points are addressed by (subpath, index).
 */
object YtPathOps {
    private fun YtPath.withSub(i: Int, change: (YtSubpath) -> YtSubpath): YtPath =
        copy(subpaths = subpaths.mapIndexed { k, s -> if (k == i) change(s) else s })

    private fun YtSubpath.withPoint(i: Int, change: (YtPoint) -> YtPoint): YtSubpath =
        copy(points = points.mapIndexed { k, p -> if (k == i) change(p) else p })

    // ---- points ----

    /** Appends an anchor to subpath [sub] (a new open subpath when [sub] is past the end). */
    fun append(path: YtPath, sub: Int, p: YtPoint): YtPath =
        if (sub >= path.subpaths.size) path.copy(subpaths = path.subpaths + YtSubpath(listOf(p), closed = false))
        else path.withSub(sub) { it.copy(points = it.points + p) }

    /** Inserts an anchor on segment [segment] of [sub] at [t] without changing the curve (de Casteljau). */
    fun insert(path: YtPath, sub: Int, segment: Int, t: Double): YtPath {
        val s = path.subpaths.getOrNull(sub) ?: return path
        if (segment !in 0 until s.segments) return path
        val c = DoubleArray(8)
        YtPathGeom.segment(s, segment, c)
        val l = DoubleArray(8)
        val r = DoubleArray(8)
        YtPathGeom.splitAt(c, t.coerceIn(0.0, 1.0), l, r)
        val straight = YtPathGeom.straight(s, segment)
        val next = (segment + 1) % s.points.size
        val pts = s.points.toMutableList()
        val a = pts[segment]
        val b = pts[next]
        val x = l[6].toFloat()
        val y = l[7].toFloat()
        val mid = if (straight) {
            YtPoint(x, y)
        } else {
            YtPoint(x, y, (l[4] - l[6]).toFloat(), (l[5] - l[7]).toFloat(), (r[2] - r[0]).toFloat(), (r[3] - r[1]).toFloat(), YtPointKind.SMOOTH)
        }
        if (!straight) {
            pts[segment] = a.copy(ox = (l[2] - l[0]).toFloat(), oy = (l[3] - l[1]).toFloat())
            pts[next] = b.copy(ix = (r[4] - r[6]).toFloat(), iy = (r[5] - r[7]).toFloat())
        }
        pts.add(segment + 1, mid)
        return path.withSub(sub) { it.copy(points = pts) }
    }

    /** Removes the anchors [refs]; a subpath left with fewer than two points goes, an open one stays open. */
    fun remove(path: YtPath, refs: Set<YtPathGeom.Ref>): YtPath {
        val subs = path.subpaths.mapIndexedNotNull { si, s ->
            val pts = s.points.filterIndexed { pi, _ -> YtPathGeom.Ref(si, pi) !in refs }
            when {
                pts.size == s.points.size -> s
                pts.size < 2 -> null
                else -> YtSubpath(pts, s.closed && pts.size >= 3)
            }
        }
        return path.copy(subpaths = subs)
    }

    /** Moves the anchors [refs] (with their handles) by ([dx], [dy]). */
    fun move(path: YtPath, refs: Set<YtPathGeom.Ref>, dx: Float, dy: Float): YtPath {
        if (refs.isEmpty() || (dx == 0f && dy == 0f)) return path
        return path.copy(
            subpaths = path.subpaths.mapIndexed { si, s ->
                if (refs.none { it.sub == si }) s else s.copy(points = s.points.mapIndexed { pi, p -> if (YtPathGeom.Ref(si, pi) in refs) p.copy(x = p.x + dx, y = p.y + dy) else p })
            },
        )
    }

    /** Sets anchor [ref] to ([x], [y]). */
    fun moveTo(path: YtPath, ref: YtPathGeom.Ref, x: Float, y: Float): YtPath =
        path.withSub(ref.sub) { s -> s.withPoint(ref.index) { it.copy(x = x, y = y) } }

    /**
     * Sets handle [side] of [ref] to the offset ([hx], [hy]); the other handle follows the point's
     * kind: CORNER leaves it, SMOOTH turns it opposite with its own length, SYMMETRIC mirrors it.
     * [independent] (e.g. a modifier) treats the point as CORNER for this move.
     */
    fun setHandle(path: YtPath, ref: YtPathGeom.Ref, side: YtPathGeom.HandleSide, hx: Float, hy: Float, independent: Boolean = false): YtPath =
        path.withSub(ref.sub) { s ->
            s.withPoint(ref.index) { p ->
                val kind = if (independent) YtPointKind.CORNER else p.kind
                val q = if (side == YtPathGeom.HandleSide.IN) p.copy(ix = hx, iy = hy) else p.copy(ox = hx, oy = hy)
                val len = hypot(hx, hy)
                when (kind) {
                    YtPointKind.CORNER -> q
                    YtPointKind.SYMMETRIC -> if (side == YtPathGeom.HandleSide.IN) q.copy(ox = -hx, oy = -hy) else q.copy(ix = -hx, iy = -hy)
                    YtPointKind.SMOOTH -> {
                        if (len < 1e-6f) return@withPoint q
                        val other = if (side == YtPathGeom.HandleSide.IN) hypot(p.ox, p.oy) else hypot(p.ix, p.iy)
                        val ux = -hx / len * other
                        val uy = -hy / len * other
                        if (side == YtPathGeom.HandleSide.IN) q.copy(ox = ux, oy = uy) else q.copy(ix = ux, iy = uy)
                    }
                }.let { if (independent) it.copy(kind = YtPointKind.CORNER) else it }
            }
        }

    /**
     * Changes the kind of [ref]. To SMOOTH/SYMMETRIC: handles along the line of its neighbours (a
     third of the distance to each), the existing ones aligned where present. To CORNER: handles kept.
     */
    fun setKind(path: YtPath, ref: YtPathGeom.Ref, kind: YtPointKind): YtPath {
        val s = path.subpaths.getOrNull(ref.sub) ?: return path
        val p = s.points.getOrNull(ref.index) ?: return path
        if (kind == YtPointKind.CORNER) return path.withSub(ref.sub) { it.withPoint(ref.index) { q -> q.copy(kind = kind) } }
        val (ix, iy, ox, oy) = smoothHandles(s, ref.index, 1f)
        val next = if (p.hasIn || p.hasOut) {
            // keep the user's handle lengths, align them on the neighbours' tangent
            val dl = hypot(ox, oy).coerceAtLeast(1e-6f)
            val ux = ox / dl
            val uy = oy / dl
            val li = if (p.hasIn) hypot(p.ix, p.iy) else hypot(ix, iy)
            val lo = if (p.hasOut) hypot(p.ox, p.oy) else dl
            if (kind == YtPointKind.SYMMETRIC) {
                val l = (li + lo) / 2f
                p.copy(ix = -ux * l, iy = -uy * l, ox = ux * l, oy = uy * l, kind = kind)
            } else {
                p.copy(ix = -ux * li, iy = -uy * li, ox = ux * lo, oy = uy * lo, kind = kind)
            }
        } else {
            p.copy(ix = ix, iy = iy, ox = ox, oy = oy, kind = kind)
        }
        return path.withSub(ref.sub) { it.withPoint(ref.index) { next } }
    }

    /** Removes both handles of [ref] (a sharp corner). */
    fun sharpen(path: YtPath, ref: YtPathGeom.Ref): YtPath =
        path.withSub(ref.sub) { it.withPoint(ref.index) { p -> p.copy(ix = 0f, iy = 0f, ox = 0f, oy = 0f, kind = YtPointKind.CORNER) } }

    /** Catmull-Rom handles of point [i] of [s] (in, out) with [amount] 0..1 of the usual tension. */
    fun smoothHandles(s: YtSubpath, i: Int, amount: Float): FloatArray {
        val n = s.points.size
        val p = s.points[i]
        val prev = if (i > 0) s.points[i - 1] else if (s.closed) s.points[n - 1] else null
        val next = if (i < n - 1) s.points[i + 1] else if (s.closed) s.points[0] else null
        if (prev == null && next == null) return floatArrayOf(0f, 0f, 0f, 0f)
        val ax = (prev ?: p).x
        val ay = (prev ?: p).y
        val bx = (next ?: p).x
        val by = (next ?: p).y
        var tx = bx - ax
        var ty = by - ay
        val tl = hypot(tx, ty)
        if (tl < 1e-6f) return floatArrayOf(0f, 0f, 0f, 0f)
        tx /= tl
        ty /= tl
        val dIn = if (prev != null) hypot(p.x - prev.x, p.y - prev.y) else 0f
        val dOut = if (next != null) hypot(next.x - p.x, next.y - p.y) else 0f
        val k = amount / 3f
        return floatArrayOf(-tx * dIn * k, -ty * dIn * k, tx * dOut * k, ty * dOut * k)
    }

    // ---- subpaths ----

    /** Reverses every subpath of [path] (or only [only]): order of points and in/out handles swapped. */
    fun reverse(path: YtPath, only: Int? = null): YtPath =
        path.copy(subpaths = path.subpaths.mapIndexed { i, s -> if (only == null || only == i) reverse(s) else s })

    fun reverse(s: YtSubpath): YtSubpath {
        val pts = s.points.reversed().map { it.copy(ix = it.ox, iy = it.oy, ox = it.ix, oy = it.iy) }
        // a closed loop keeps its first point first
        val out = if (s.closed && pts.isNotEmpty()) listOf(pts.last()) + pts.dropLast(1) else pts
        return s.copy(points = out)
    }

    /** Closes or opens subpath [sub]. */
    fun setClosed(path: YtPath, sub: Int, closed: Boolean): YtPath = path.withSub(sub) { it.copy(closed = closed && it.points.size >= 2) }

    /**
     * Splits at anchor [ref]: a closed subpath opens there (it starts and ends at the point); an
     * open one becomes two, both holding the point. An end point of an open subpath stays as it is.
     */
    fun split(path: YtPath, ref: YtPathGeom.Ref): YtPath {
        val s = path.subpaths.getOrNull(ref.sub) ?: return path
        val i = ref.index
        if (i !in s.points.indices) return path
        if (s.closed) {
            val rot = s.points.drop(i) + s.points.take(i)
            val start = rot.first()
            val pts = rot + start.copy(ox = 0f, oy = 0f)
            return path.withSub(ref.sub) { YtSubpath(listOf(start.copy(ix = 0f, iy = 0f)) + pts.drop(1), closed = false) }
        }
        if (i == 0 || i == s.points.lastIndex) return path
        val a = YtSubpath(s.points.take(i) + s.points[i].copy(ox = 0f, oy = 0f), closed = false)
        val b = YtSubpath(listOf(s.points[i].copy(ix = 0f, iy = 0f)) + s.points.drop(i + 1), closed = false)
        val subs = path.subpaths.toMutableList()
        subs[ref.sub] = a
        subs.add(ref.sub + 1, b)
        return path.copy(subpaths = subs)
    }

    /**
     * Joins the end point [a] with the end point [b] (both ends of open subpaths). The same subpath:
     * it closes (a coinciding pair merges into one point). Two subpaths: one open subpath through both.
     */
    fun join(path: YtPath, a: YtPathGeom.Ref, b: YtPathGeom.Ref): YtPath {
        val sa = path.subpaths.getOrNull(a.sub) ?: return path
        val sb = path.subpaths.getOrNull(b.sub) ?: return path
        if (sa.closed || sb.closed) return path
        fun isEnd(s: YtSubpath, i: Int) = i == 0 || i == s.points.lastIndex
        if (!isEnd(sa, a.index) || !isEnd(sb, b.index)) return path
        if (a.sub == b.sub) {
            if (a.index == b.index || sa.points.size < 3) return path
            val first = sa.points.first()
            val last = sa.points.last()
            val pts = if (hypot(first.x - last.x, first.y - last.y) < 0.01f) {
                // the ends lie on each other: one point with the last one's in-handle
                listOf(first.copy(ix = last.ix, iy = last.iy)) + sa.points.drop(1).dropLast(1)
            } else {
                sa.points
            }
            return path.withSub(a.sub) { YtSubpath(pts, closed = true) }
        }
        // a's subpath runs towards its end at a, b's runs away from b
        val left = if (a.index == sa.points.lastIndex) sa else reverse(sa)
        val right = if (b.index == 0) sb else reverse(sb)
        val lp = left.points.last()
        val rp = right.points.first()
        val pts = if (hypot(lp.x - rp.x, lp.y - rp.y) < 0.01f) {
            // the ends lie on each other: one point with the left one's in-handle
            left.points.dropLast(1) + rp.copy(ix = lp.ix, iy = lp.iy) + right.points.drop(1)
        } else {
            left.points + right.points
        }
        val merged = YtSubpath(pts, closed = false)
        val subs = path.subpaths.toMutableList()
        val lo = min(a.sub, b.sub)
        val hi = max(a.sub, b.sub)
        subs.removeAt(hi)
        subs[lo] = merged
        return path.copy(subpaths = subs)
    }

    /** All subpaths of [paths] (already in one coordinate space) as one compound path. */
    fun combine(paths: List<YtPath>, rule: YtFillRule = YtFillRule.NON_ZERO): YtPath =
        YtPath(paths.flatMap { it.subpaths }, rule, paths.firstOrNull()?.w ?: 100f, paths.firstOrNull()?.h ?: 100f)

    // ---- simplify and smooth ----

    /**
     * Ramer–Douglas–Peucker on the anchors of each subpath: anchors closer than [tolerance] to the
     * line of the kept ones go (their handles with them); kept anchors keep their handles. Closed
     * subpaths keep at least three anchors, open ones their two ends.
     */
    fun simplify(path: YtPath, tolerance: Float): YtPath = path.copy(subpaths = path.subpaths.map { simplify(it, tolerance) })

    fun simplify(s: YtSubpath, tolerance: Float): YtSubpath {
        val n = s.points.size
        if (n < 3 || tolerance <= 0f) return s
        val xs = DoubleArray(n) { s.points[it].x.toDouble() }
        val ys = DoubleArray(n) { s.points[it].y.toDouble() }
        val keep = if (s.closed) rdpClosed(xs, ys, tolerance.toDouble()) else rdp(xs, ys, 0, n - 1, tolerance.toDouble())
        if (keep.size == n) return s
        return s.copy(points = keep.map { s.points[it] })
    }

    /** Indices kept by RDP between [from] and [to] (inclusive) of a polyline. */
    fun rdp(xs: DoubleArray, ys: DoubleArray, from: Int, to: Int, tol: Double): List<Int> {
        val keep = BooleanArray(xs.size)
        keep[from] = true
        keep[to] = true
        // iterative, so long recordings cannot overflow the stack
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(from to to)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var best = -1
            var bestD = tol
            for (i in a + 1 until b) {
                val d = YtPathGeom.distToSegment(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > bestD) {
                    bestD = d
                    best = i
                }
            }
            if (best >= 0) {
                keep[best] = true
                stack.addLast(a to best)
                stack.addLast(best to b)
            }
        }
        return (from..to).filter { keep[it] }
    }

    private fun rdpClosed(xs: DoubleArray, ys: DoubleArray, tol: Double): List<Int> {
        val n = xs.size
        // split the loop at the point farthest from the first, simplify both halves
        var far = 1
        var farD = -1.0
        for (i in 1 until n) {
            val d = hypot(xs[i] - xs[0], ys[i] - ys[0])
            if (d > farD) { farD = d; far = i }
        }
        val ex = DoubleArray(n + 1) { if (it == n) xs[0] else xs[it] }
        val ey = DoubleArray(n + 1) { if (it == n) ys[0] else ys[it] }
        val a = rdp(ex, ey, 0, far, tol)
        val b = rdp(ex, ey, far, n, tol)
        val out = (a + b.drop(1)).filter { it < n }.distinct()
        if (out.size >= 3) return out
        // keep a triangle at least: the first, the farthest and the one farthest from that line
        var third = -1
        var td = -1.0
        for (i in 0 until n) {
            if (i == 0 || i == far) continue
            val d = YtPathGeom.distToSegment(xs[i], ys[i], xs[0], ys[0], xs[far], ys[far])
            if (d > td) { td = d; third = i }
        }
        return listOf(0, far, third).filter { it >= 0 }.distinct().sorted()
    }

    /** Smooth handles (Catmull-Rom, [amount] 0..1) on every anchor of [refs], or on all anchors when null. */
    fun smooth(path: YtPath, amount: Float = 1f, refs: Set<YtPathGeom.Ref>? = null): YtPath =
        path.copy(
            subpaths = path.subpaths.mapIndexed { si, s ->
                s.copy(
                    points = s.points.mapIndexed { pi, p ->
                        if (refs != null && YtPathGeom.Ref(si, pi) !in refs) return@mapIndexed p
                        val h = smoothHandles(s, pi, amount)
                        // open ends keep a straight start
                        val end = !s.closed && (pi == 0 || pi == s.points.lastIndex)
                        if (amount <= 0f) p.copy(ix = 0f, iy = 0f, ox = 0f, oy = 0f, kind = YtPointKind.CORNER)
                        else p.copy(ix = if (end && pi == 0) 0f else h[0], iy = if (end && pi == 0) 0f else h[1], ox = if (end && pi != 0) 0f else h[2], oy = if (end && pi != 0) 0f else h[3], kind = if (end) YtPointKind.CORNER else YtPointKind.SMOOTH)
                    },
                )
            },
        )

    /**
     * Evens out jitter: each anchor moves [amount] (0..1) of the way to the mean of its neighbours,
     * [passes] times; the ends of an open subpath stay.
     */
    fun relax(s: YtSubpath, amount: Float, passes: Int = 2): YtSubpath {
        if (s.points.size < 3 || amount <= 0f) return s
        var pts = s.points
        val n = pts.size
        repeat(passes.coerceIn(1, 16)) {
            pts = pts.mapIndexed { i, p ->
                val end = !s.closed && (i == 0 || i == n - 1)
                if (end) return@mapIndexed p
                val a = pts[(i - 1 + n) % n]
                val b = pts[(i + 1) % n]
                val mx = (a.x + b.x) / 2f
                val my = (a.y + b.y) / 2f
                p.copy(x = p.x + (mx - p.x) * amount, y = p.y + (my - p.y) * amount)
            }
        }
        return s.copy(points = pts)
    }

    // ---- transforms and boxes ----

    /** Every coordinate mapped by (x·[sx] + [tx], y·[sy] + [ty]); handles scaled. */
    fun scale(path: YtPath, sx: Float, sy: Float, tx: Float = 0f, ty: Float = 0f): YtPath = path.copy(
        subpaths = path.subpaths.map { s ->
            s.copy(points = s.points.map { p -> p.copy(x = p.x * sx + tx, y = p.y * sy + ty, ix = p.ix * sx, iy = p.iy * sy, ox = p.ox * sx, oy = p.oy * sy) })
        },
    )

    /** Rotated by [deg] around ([cx], [cy]). */
    fun rotate(path: YtPath, deg: Float, cx: Float, cy: Float): YtPath {
        if (deg % 360f == 0f) return path
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val sn = sin(r).toFloat()
        fun rx(x: Float, y: Float) = c * x - sn * y
        fun ry(x: Float, y: Float) = sn * x + c * y
        return path.copy(
            subpaths = path.subpaths.map { s ->
                s.copy(
                    points = s.points.map { p ->
                        val dx = p.x - cx
                        val dy = p.y - cy
                        p.copy(x = cx + rx(dx, dy), y = cy + ry(dx, dy), ix = rx(p.ix, p.iy), iy = ry(p.ix, p.iy), ox = rx(p.ox, p.oy), oy = ry(p.ox, p.oy))
                    },
                )
            },
        )
    }

    /**
     * [path] (in its box w × h) placed into a parent: stretched to [bw] × [bh] dp, turned by [deg]
     * around its centre, its centre at ([cx], [cy]). The result's own box is meaningless (parent units).
     */
    fun place(path: YtPath, cx: Float, cy: Float, bw: Float, bh: Float, deg: Float): YtPath {
        val sx = if (path.w > 0f) bw / path.w else 1f
        val sy = if (path.h > 0f) bh / path.h else 1f
        val placed = scale(path, sx, sy, cx - bw / 2f, cy - bh / 2f)
        return rotate(placed, deg, cx, cy)
    }

    /**
     * The inverse of [place]: a path in parent units into the box of a node centred at ([cx], [cy]),
     * [bw] × [bh] dp, turned by [deg]; the box becomes the path's own.
     */
    fun unplace(path: YtPath, cx: Float, cy: Float, bw: Float, bh: Float, deg: Float): YtPath {
        val back = rotate(path, -deg, cx, cy)
        return scale(back, 1f, 1f, -(cx - bw / 2f), -(cy - bh / 2f)).copy(w = bw.coerceAtLeast(YtVecLimits.MIN_BOX), h = bh.coerceAtLeast(YtVecLimits.MIN_BOX))
    }

    /** The path moved so its bounds start at 0,0, its box the bounds, and the bounds (left, top, width, height) in the old units. */
    fun normalized(path: YtPath): Pair<YtPath, FloatArray>? {
        val b = YtPathGeom.bounds(path) ?: return null
        val l = b[0].toFloat()
        val t = b[1].toFloat()
        val w = max((b[2] - b[0]).toFloat(), 1f)
        val h = max((b[3] - b[1]).toFloat(), 1f)
        return scale(path, 1f, 1f, -l, -t).copy(w = w, h = h) to floatArrayOf(l, t, w, h)
    }

    /** Drops anchors without handles that lie on the straight line of their neighbours. */
    fun dropCollinear(s: YtSubpath, tol: Double = 1e-3): YtSubpath {
        if (s.points.size < 3) return s
        val pts = s.points.toMutableList()
        var i = 0
        var guard = 0
        while (pts.size >= 3 && i < pts.size && guard++ < 100_000) {
            val n = pts.size
            val end = !s.closed && (i == 0 || i == n - 1)
            val p = pts[i]
            if (!end && !p.hasIn && !p.hasOut) {
                val a = pts[(i - 1 + n) % n]
                val b = pts[(i + 1) % n]
                if (!a.hasOut && !b.hasIn) {
                    val d = YtPathGeom.distToSegment(p.x.toDouble(), p.y.toDouble(), a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble())
                    val along = (b.x - a.x) * (p.x - a.x) + (b.y - a.y) * (p.y - a.y)
                    if (d <= tol && along >= 0f) {
                        pts.removeAt(i)
                        if (i > 0) i--
                        continue
                    }
                }
            }
            i++
        }
        return s.copy(points = pts, closed = s.closed && pts.size >= 3)
    }

    /** Whether the handles of [p] lie on one line through it (a smooth join). */
    fun aligned(p: YtPoint): Boolean {
        if (!p.hasIn || !p.hasOut) return false
        val cross = p.ix * p.oy - p.iy * p.ox
        val dot = p.ix * p.ox + p.iy * p.oy
        val li = hypot(p.ix, p.iy)
        val lo = hypot(p.ox, p.oy)
        return dot < 0f && abs(cross) <= 0.01f * li * lo
    }
}
