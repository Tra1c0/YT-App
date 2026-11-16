package com.github.libretube.ui.yt.builder

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * YT: the pen tool's decisions (pure, JVM-tested); the canvas only feeds it positions in path
 * units. A tap sets a corner point, a drag right after the touch pulls the new point's handles out
 * (a smooth point, symmetric while drawing), a tap on the first point of the open subpath closes it.
 */
object YtPenModel {
    /** What a touch while drawing did. */
    enum class Did { ADDED, CLOSED }

    class Step(val path: YtPath, val sub: Int, val did: Did)

    /**
     * A touch at ([x], [y]) while drawing subpath [sub] ([sub] past the end starts a new one): on the
     * first point of an open subpath of at least two points (within [closeSlop]) it closes it,
     * otherwise a corner point is added.
     */
    fun touch(path: YtPath, sub: Int, x: Float, y: Float, closeSlop: Float): Step {
        val s = path.subpaths.getOrNull(sub)
        if (s != null && !s.closed && s.points.size >= 2) {
            val first = s.points.first()
            if (hypot(first.x - x, first.y - y) <= closeSlop) {
                return Step(YtPathOps.setClosed(path, sub, true), sub, Did.CLOSED)
            }
        }
        val at = if (s == null || s.closed) path.subpaths.size else sub
        return Step(YtPathOps.append(path, at, YtPoint(x, y)), at, Did.ADDED)
    }

    /**
     * The finger pulled from the point just set (or the first point when the subpath was closed on
     * it) to ([x], [y]): its out-handle follows the finger, the in-handle mirrors it.
     */
    fun pull(path: YtPath, sub: Int, closed: Boolean, x: Float, y: Float): YtPath {
        val s = path.subpaths.getOrNull(sub) ?: return path
        if (s.points.isEmpty()) return path
        val index = if (closed) 0 else s.points.lastIndex
        val p = s.points[index]
        val hx = x - p.x
        val hy = y - p.y
        return path.copy(
            subpaths = path.subpaths.mapIndexed { i, q ->
                if (i != sub) q else q.copy(points = q.points.mapIndexed { k, pt -> if (k == index) pt.copy(ox = hx, oy = hy, ix = -hx, iy = -hy, kind = YtPointKind.SYMMETRIC) else pt })
            },
        )
    }

    /** A drag from a fresh point counts as pulling handles beyond this many path units. */
    fun pulls(dx: Float, dy: Float, slop: Float): Boolean = hypot(dx, dy) > slop
}

/**
 * YT: snapping a dragged point (pure): to the grid, the guides and the x or y of other points,
 * within [tolerance]; the nearest wins per axis. All values in one unit (frame px).
 */
object YtPointSnap {
    data class Result(val x: Float, val y: Float, val lineX: Float?, val lineY: Float?)

    fun snap(x: Float, y: Float, grid: Float, guidesX: List<Float>, guidesY: List<Float>, otherX: List<Float>, otherY: List<Float>, tolerance: Float, gridOriginX: Float = 0f, gridOriginY: Float = 0f): Result {
        fun axis(v: Float, guides: List<Float>, others: List<Float>, origin: Float): Pair<Float, Float?> {
            var best = v
            var bestD = tolerance
            var line: Float? = null
            for (g in guides + others) {
                val d = abs(g - v)
                if (d <= bestD) {
                    bestD = d
                    best = g
                    line = g
                }
            }
            if (line == null && grid > 0f) {
                val g = origin + ((v - origin) / grid).roundToInt() * grid
                if (abs(g - v) <= tolerance) {
                    best = g
                    line = g
                }
            }
            return best to line
        }
        val (sx, lx) = axis(x, guidesX, otherX, gridOriginX)
        val (sy, ly) = axis(y, guidesY, otherY, gridOriginY)
        return Result(sx, sy, lx, ly)
    }
}
