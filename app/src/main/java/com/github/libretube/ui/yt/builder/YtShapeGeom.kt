package com.github.libretube.ui.yt.builder

import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * YT: the outline of a SHAPE node as data (pure, JVM-tested): parametric shapes as paths (what
 * "In Pfad umwandeln" makes, and what booleans and masks start from) and as RoundedPolygons of
 * androidx.graphics.shapes (what the morph of two parametric shapes uses). Coordinates are dp of
 * the node's box (left to right, top to bottom; start/end as in a left-to-right layout).
 */
object YtShapeGeom {
    /** The circle constant of a cubic quarter arc. */
    private const val ARC = 0.5522848f

    /** The geometry a node or morph target describes. */
    fun targetOf(node: YtNode): YtMorphTarget = YtMorphTarget(node.shape, node.shapeSpec, node.style.corners, node.path)

    /** [t]'s outline in a box of [w] × [h] dp, as a path whose own box is that box. */
    fun pathOf(t: YtMorphTarget, w: Float, h: Float): YtPath {
        val bw = max(w, YtVecLimits.MIN_BOX)
        val bh = max(h, YtVecLimits.MIN_BOX)
        return when (t.shape) {
            YtShapeKind.PATH -> t.path?.let { YtPathOps.scale(it, bw / it.w, bh / it.h).copy(w = bw, h = bh) } ?: rectPath(bw, bh, YtCorners())
            YtShapeKind.RECT, YtShapeKind.ELLIPSE, YtShapeKind.PILL, YtShapeKind.LINE -> rectPath(bw, bh, t.corners)
            else -> polygonOf(t, bw, bh)?.let { fromPolygon(it, bw, bh) } ?: rectPath(bw, bh, t.corners)
        }
    }

    fun pathOf(node: YtNode, w: Float, h: Float): YtPath = pathOf(targetOf(node), w, h)

    /**
     * The rounded rectangle YtCornerShape draws: radii scaled down together where they overlap,
     * circular corners, or with smoothing the continuous corners of YtCornerShape.smoothPath.
     */
    fun rectPath(w: Float, h: Float, c: YtCorners): YtPath {
        var tl = c.topStart.coerceAtLeast(0f)
        var tr = c.topEnd.coerceAtLeast(0f)
        var br = c.bottomEnd.coerceAtLeast(0f)
        var bl = c.bottomStart.coerceAtLeast(0f)
        val f = min(1f, min(min(w / max(tl + tr, 1e-3f), w / max(bl + br, 1e-3f)), min(h / max(tl + bl, 1e-3f), h / max(tr + br, 1e-3f))))
        tl *= f; tr *= f; br *= f; bl *= f
        val s = c.smoothing.coerceIn(0f, 1f)
        val pts = ArrayList<YtPoint>()
        // a corner: from the point on the incoming side to the point on the outgoing one
        fun corner(r: Float, sx: Float, sy: Float, ex: Float, ey: Float, cx: Float, cy: Float) {
            if (r <= 0.001f) {
                pts += YtPoint(cx, cy)
                return
            }
            val reach = if (s <= 0.001f) r else min(r * (1f + s), min(w, h) / 2f)
            val k = if (s <= 0.001f) ARC else 0.55f + 0.3f * s
            // start point: from the corner back along the incoming side by reach; end: along the outgoing one
            val ax = cx + sx * reach
            val ay = cy + sy * reach
            val bx = cx + ex * reach
            val by = cy + ey * reach
            pts += YtPoint(ax, ay, ox = -sx * reach * k, oy = -sy * reach * k, kind = YtPointKind.SMOOTH)
            pts += YtPoint(bx, by, ix = -ex * reach * k, iy = -ey * reach * k, kind = YtPointKind.SMOOTH)
        }
        // clockwise from the top left: (side direction back to the incoming side, outgoing side)
        corner(tl, 0f, 1f, 1f, 0f, 0f, 0f)
        corner(tr, -1f, 0f, 0f, 1f, w, 0f)
        corner(br, 0f, -1f, -1f, 0f, w, h)
        corner(bl, 1f, 0f, 0f, -1f, 0f, h)
        return YtPath(listOf(YtSubpath(pts, closed = true)), YtFillRule.NON_ZERO, w, h)
    }

    /** Whether [t] can be a RoundedPolygon exactly as it is drawn (the smoothed corners of phase 1 cannot). */
    fun polygonal(t: YtMorphTarget): Boolean = when (t.shape) {
        YtShapeKind.PATH -> false
        YtShapeKind.RECT, YtShapeKind.ELLIPSE, YtShapeKind.PILL, YtShapeKind.LINE -> t.corners.smoothing <= 0.001f
        else -> true
    }

    /** The vertices of a regular polygon (or star with [inner] < 1) stretched to the box. */
    private fun vertices(n: Int, inner: Float?, w: Float, h: Float): FloatArray {
        val count = if (inner != null) n * 2 else n
        val raw = DoubleArray(count * 2)
        for (i in 0 until count) {
            val a = -PI / 2 + 2 * PI * i / count
            val r = if (inner != null && i % 2 == 1) inner.toDouble() else 1.0
            raw[2 * i] = cos(a) * r
            raw[2 * i + 1] = sin(a) * r
        }
        var l = Double.MAX_VALUE; var t = Double.MAX_VALUE; var r = -Double.MAX_VALUE; var b = -Double.MAX_VALUE
        for (i in 0 until count) {
            l = min(l, raw[2 * i]); r = max(r, raw[2 * i]); t = min(t, raw[2 * i + 1]); b = max(b, raw[2 * i + 1])
        }
        val out = FloatArray(count * 2)
        for (i in 0 until count) {
            out[2 * i] = ((raw[2 * i] - l) / (r - l) * w).toFloat()
            out[2 * i + 1] = ((raw[2 * i + 1] - t) / (b - t) * h).toFloat()
        }
        return out
    }

    /** [t] as a RoundedPolygon in the dp box [w] × [h], or null when it is a path or has smoothed rectangle corners. */
    fun polygonOf(t: YtMorphTarget, w: Float, h: Float): RoundedPolygon? = runCatching {
        val spec = YtVectorValidate.spec(t.spec)
        val round = CornerRounding(spec.rounding, spec.smoothing)
        when (t.shape) {
            YtShapeKind.PATH -> null
            YtShapeKind.RECT, YtShapeKind.ELLIPSE, YtShapeKind.PILL, YtShapeKind.LINE -> {
                if (t.corners.smoothing > 0.001f) return@runCatching null
                val c = t.corners
                var tl = c.topStart; var tr = c.topEnd; var br = c.bottomEnd; var bl = c.bottomStart
                val f = min(1f, min(min(w / max(tl + tr, 1e-3f), w / max(bl + br, 1e-3f)), min(h / max(tl + bl, 1e-3f), h / max(tr + br, 1e-3f))))
                tl *= f; tr *= f; br *= f; bl *= f
                RoundedPolygon(
                    floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h),
                    CornerRounding.Unrounded,
                    listOf(CornerRounding(tl), CornerRounding(tr), CornerRounding(br), CornerRounding(bl)),
                    w / 2f, h / 2f,
                )
            }
            YtShapeKind.TRIANGLE -> RoundedPolygon(vertices(3, null, w, h), round, null, w / 2f, h / 2f)
            YtShapeKind.POLYGON -> RoundedPolygon(vertices(spec.sides, null, w, h), round, null, w / 2f, h / 2f)
            YtShapeKind.STAR -> RoundedPolygon(vertices(spec.sides, spec.inner, w, h), round, null, w / 2f, h / 2f)
            YtShapeKind.SQUIRCLE -> {
                val r = if (spec.rounding > 0f) spec.rounding else min(w, h) * 0.46f
                RoundedPolygon(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), CornerRounding(r, if (spec.smoothing > 0f) spec.smoothing else 1f), null, w / 2f, h / 2f)
            }
        }
    }.getOrNull()

    /** A RoundedPolygon's cubics as one closed subpath (zero-length pieces dropped, smooth joins marked). */
    fun fromPolygon(p: RoundedPolygon, w: Float, h: Float): YtPath {
        val cubics = p.cubics.filter { c ->
            !(c.anchor0X == c.anchor1X && c.anchor0Y == c.anchor1Y && c.control0X == c.anchor0X && c.control0Y == c.anchor0Y && c.control1X == c.anchor1X && c.control1Y == c.anchor1Y)
        }
        if (cubics.isEmpty()) return rectPath(w, h, YtCorners())
        val n = cubics.size
        val pts = ArrayList<YtPoint>(n)
        for (i in 0 until n) {
            val c = cubics[i]
            val prev = cubics[(i - 1 + n) % n]
            val straightOut = isLine(c)
            val straightIn = isLine(prev)
            pts += YtPoint(
                c.anchor0X, c.anchor0Y,
                ix = if (straightIn) 0f else prev.control1X - c.anchor0X, iy = if (straightIn) 0f else prev.control1Y - c.anchor0Y,
                ox = if (straightOut) 0f else c.control0X - c.anchor0X, oy = if (straightOut) 0f else c.control0Y - c.anchor0Y,
            )
        }
        val marked = pts.map { if (YtPathOps.aligned(it)) it.copy(kind = YtPointKind.SMOOTH) else it }
        return YtPath(listOf(YtPathOps.dropCollinear(YtSubpath(marked, closed = true))), YtFillRule.NON_ZERO, w, h)
    }

    /** A cubic whose controls lie on its chord (RoundedPolygon writes straight sides like that). */
    private fun isLine(c: androidx.graphics.shapes.Cubic): Boolean {
        val dx = c.anchor1X - c.anchor0X
        val dy = c.anchor1Y - c.anchor0Y
        val len = kotlin.math.hypot(dx, dy)
        if (len < 1e-6f) return true
        fun off(x: Float, y: Float) = kotlin.math.abs((x - c.anchor0X) * dy - (y - c.anchor0Y) * dx) / len
        return off(c.control0X, c.control0Y) < 1e-3f && off(c.control1X, c.control1Y) < 1e-3f
    }
}
