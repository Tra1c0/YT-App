package com.github.libretube.ui.yt.builder

import kotlinx.serialization.Serializable

/*
 * YT: vector paths of the theme builder (phase 3 "Vektor", schema 3). Pure data.
 *
 * A path is a list of subpaths of anchor points; each anchor carries its two Bézier handles as
 * offsets from the anchor (in: towards the previous point, out: towards the next one). A segment
 * from anchor A to anchor B is the cubic A, A + A.out, B + B.in, B; two zero handles make it
 * straight. A path lives in its own box [YtPath.w] × [YtPath.h] (dp when it was made) and is drawn
 * stretched to the node's box, so the size rules of the node keep working.
 *
 * Parametric shapes (rectangle, polygon, star, …) stay parametric (YtShapeSpec) until the user turns
 * them into a path; boolean operations, the pen tool and masks produce paths.
 */

/** How an anchor's handles move together: CORNER independent, SMOOTH aligned (own lengths), SYMMETRIC mirrored. */
@Serializable
enum class YtPointKind { CORNER, SMOOTH, SYMMETRIC }

/** An anchor at ([x], [y]) with the in-handle ([ix], [iy]) and out-handle ([ox], [oy]) as offsets from it. */
@Serializable
data class YtPoint(
    val x: Float,
    val y: Float,
    val ix: Float = 0f,
    val iy: Float = 0f,
    val ox: Float = 0f,
    val oy: Float = 0f,
    val kind: YtPointKind = YtPointKind.CORNER,
) {
    val hasIn: Boolean get() = ix != 0f || iy != 0f
    val hasOut: Boolean get() = ox != 0f || oy != 0f
}

@Serializable
data class YtSubpath(val points: List<YtPoint> = emptyList(), val closed: Boolean = true) {
    /** Segments: one per pair of neighbours, plus the closing one. */
    val segments: Int get() = if (points.size < 2) 0 else if (closed) points.size else points.size - 1
}

/** Which areas of overlapping subpaths are filled (as Android's fill types). */
@Serializable
enum class YtFillRule { NON_ZERO, EVEN_ODD }

@Serializable
data class YtPath(
    val subpaths: List<YtSubpath> = emptyList(),
    val fillRule: YtFillRule = YtFillRule.NON_ZERO,
    /** The box the coordinates are in; drawn stretched to the node's box. */
    val w: Float = 100f,
    val h: Float = 100f,
) {
    val pointCount: Int get() = subpaths.sumOf { it.points.size }
    val isEmpty: Boolean get() = subpaths.none { it.points.size >= 2 }
}

/**
 * The parameters of the parametric shapes: [sides] of a polygon or the points of a star, the
 * star's [inner] radius (share of the outer one), the [rounding] of the corners (dp) and its
 * [smoothing] (0 circular, 1 fully continuous).
 */
@Serializable
data class YtShapeSpec(
    val sides: Int = 5,
    val inner: Float = 0.5f,
    val rounding: Float = 0f,
    val smoothing: Float = 0f,
)

/** A mask on a node: only what lies inside [path] (in the node's own box) is drawn; [invert] keeps the outside. */
@Serializable
data class YtMask(val path: YtPath, val invert: Boolean = false, val enabled: Boolean = true)

/**
 * A motion path of an animation (its PATH track runs 0..1 along it). The points are dp offsets
 * in the parent; the node is moved by point(progress) − point(0), so it starts where it rests.
 * [orient]: the node turns with the path's direction (plus [angle]); otherwise it keeps [angle].
 */
@Serializable
data class YtMotionPath(val path: YtPath = YtPath(), val orient: Boolean = false, val angle: Float = 0f)

/** The shape a MORPH track changes the node into: a parametric shape or a path (in the node's box). */
@Serializable
data class YtMorphTarget(
    val shape: YtShapeKind = YtShapeKind.ELLIPSE,
    val spec: YtShapeSpec = YtShapeSpec(),
    val corners: YtCorners = YtCorners(),
    val path: YtPath? = null,
)

/** Limits of the vector data (validation clamps to them). */
object YtVecLimits {
    const val MAX_SUBPATHS = 64
    const val MAX_POINTS = 2000
    const val MAX_COORD = 20_000f
    const val MIN_BOX = 0.01f
    const val MAX_SIDES = 24
}

/** YT: vector data made safe to draw (pure); used by YtThemeValidate and YtAnimValidate. */
object YtVectorValidate {
    private fun fin(v: Float, fallback: Float = 0f) = if (v.isNaN() || v.isInfinite()) fallback else v

    private fun coord(v: Float) = fin(v).coerceIn(-YtVecLimits.MAX_COORD, YtVecLimits.MAX_COORD)

    fun point(p: YtPoint) = p.copy(x = coord(p.x), y = coord(p.y), ix = coord(p.ix), iy = coord(p.iy), ox = coord(p.ox), oy = coord(p.oy))

    /** [p] clamped: finite coordinates, capped counts, empty subpaths dropped; [issue] gets a reason when something changed. */
    fun path(p: YtPath, issue: (String) -> Unit = {}): YtPath {
        var budget = YtVecLimits.MAX_POINTS
        val subs = ArrayList<YtSubpath>()
        for (s in p.subpaths) {
            if (subs.size >= YtVecLimits.MAX_SUBPATHS || budget <= 0) {
                issue("too many subpaths or points, the rest dropped")
                break
            }
            if (s.points.isEmpty()) continue
            val pts = s.points.take(budget).map(::point)
            if (pts.size < s.points.size) issue("too many points, the rest dropped")
            budget -= pts.size
            subs += YtSubpath(pts, s.closed && pts.size >= 2)
        }
        val w = fin(p.w, 100f).coerceIn(YtVecLimits.MIN_BOX, YtVecLimits.MAX_COORD)
        val h = fin(p.h, 100f).coerceIn(YtVecLimits.MIN_BOX, YtVecLimits.MAX_COORD)
        if (w != p.w || h != p.h) issue("path box clamped")
        return p.copy(subpaths = subs, w = w, h = h)
    }

    fun spec(s: YtShapeSpec) = YtShapeSpec(
        sides = s.sides.coerceIn(3, YtVecLimits.MAX_SIDES),
        inner = fin(s.inner, 0.5f).coerceIn(0.05f, 1f),
        rounding = fin(s.rounding).coerceIn(0f, YtThemeValidate.MAX_RADIUS),
        smoothing = fin(s.smoothing).coerceIn(0f, 1f),
    )

    fun mask(m: YtMask, issue: (String) -> Unit = {}): YtMask? {
        val p = path(m.path, issue)
        return if (p.isEmpty) null.also { issue("empty mask dropped") } else m.copy(path = p)
    }

    fun motion(m: YtMotionPath, issue: (String) -> Unit = {}): YtMotionPath =
        m.copy(path = path(m.path, issue), angle = fin(m.angle).coerceIn(-3600f, 3600f))

    fun morph(t: YtMorphTarget, issue: (String) -> Unit = {}): YtMorphTarget =
        t.copy(spec = spec(t.spec), path = t.path?.let { path(it, issue) }?.takeIf { !it.isEmpty }, shape = if (t.shape == YtShapeKind.PATH && (t.path == null || t.path.isEmpty)) YtShapeKind.RECT else t.shape)
}
