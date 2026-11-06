package com.github.libretube.ui.yt.mini

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * YT: the Formen mini player ("Keksfenster") in numbers. Formen's home is night blue with bright
 * Material shapes, so the docked player shows its video through a cookie-shaped window: the
 * picture is cut square and the overlay over the video paints the body colour around a scalloped
 * hole (a SurfaceView can only be clipped to a rounded rectangle, not to a path). The lime wave of
 * progress runs around the cookie. The long press grows the window into a rounded 4:3 picture,
 * the scallops smoothing out on the way. Pure (px, no Android), so the View constraints, both
 * composables and the JVM tests read the same numbers.
 */

/** Formen's preview in px of the grown box: [frame] is what the host reads, [thumb] the next video's cookie. */
data class YtShapesPeek(
    val frame: YtMiniPeekGeometry,
    val thumb: YtMiniBox?,
)

/** The shape of the window at one moment, in px of the picture. */
data class YtShapesWindowShape(
    /** 0 = the rounded rectangle of the picture's outline, 1 = the cookie. */
    val cookie: Float,
    /** The corner of the rounded rectangle (the outline's). */
    val corner: Float,
    /** How deep the scallops are, a share of the cookie's radius. */
    val depth: Float,
    /** The turn of the cookie, in degrees. */
    val turn: Float,
    /** The cookie's size, a share of the picture's inscribed ellipse. */
    val scale: Float = YtShapesLayout.COOKIE_SCALE,
)

object YtShapesLayout {
    /** The preview enlarges the picture 1.8 times in height ("leicht vergrößert"). */
    const val GROWTH = 1.8f

    /** The docked player: its height above the bar, the corner of its body, the square picture. */
    const val HEIGHT = 66
    const val RADIUS = 30
    const val THUMB = 62
    const val THUMB_START = 6

    /** The pink cookie behind play. */
    const val MARK = 42

    /** The scallops of the window, how deep they are, how much of the square the cookie fills. */
    const val LOBES = 12
    const val DEPTH = 0.07f
    const val COOKIE_SCALE = 0.78f

    /** The wave of progress around the cookie: gap to the cookie, amplitude, waves per turn. */
    const val RING_GAP = 3f
    const val RING_AMPLITUDE = 1.1f
    const val RING_WAVES = 10

    /** The preview: inset, gap, the next video's row and its cookie, the play pill. */
    const val PAD = 8
    const val GAP = 12
    const val NEXT_ROW = 60
    const val NEXT_THUMB = 48
    const val PILL = 44
    const val PILL_WIDTH = 156

    /** The preview's picture is 4:3 and takes at most this share of the inner width. */
    const val PEEK_ASPECT = 4f / 3f
    const val PICTURE_SHARE = 0.56f

    /** How many points the window's outline is drawn with (a multiple of the lobes). */
    const val POINTS = 144

    /** Formen's measures on top of the shared ones: a taller body and a square picture. */
    fun spec(base: YtMiniSpec): YtMiniSpec = base.copy(
        height = HEIGHT,
        radius = RADIUS,
        thumbWidth = THUMB,
        thumbHeight = THUMB,
        thumbStart = THUMB_START,
        thumbRadius = THUMB / 2,
        peekGrowth = GROWTH,
    )

    /**
     * The preview of a box [boxWidth] px wide: the next video's row across the top ([hasNext]),
     * below it the picture grown 1.8 times at the box's start (where the finger holds it), beside
     * it the title and under that the pink play pill.
     */
    fun peek(spec: YtMiniSpec, boxWidth: Float, density: Float, hasNext: Boolean): YtShapesPeek {
        val d = density
        val pad = PAD * d
        val left = spec.body * d + pad
        val right = max(boxWidth - spec.body * d - pad, left + 1f)
        val inner = right - left
        var height = spec.thumbHeight * GROWTH * d
        var width = height * PEEK_ASPECT
        val most = inner * PICTURE_SHARE
        if (width > most) {
            width = most
            height = width / PEEK_ASPECT
        }
        // never smaller than the docked picture
        height = max(height, spec.thumbHeight * d)
        width = max(width, spec.thumbWidth * d)

        val next = if (hasNext) YtMiniBox(left, pad, right, pad + NEXT_ROW * d) else null
        val thumb = next?.let {
            val side = NEXT_THUMB * d
            val top = it.centerY - side / 2f
            YtMiniBox(it.left + 6f * d, top, it.left + 6f * d + side, top + side)
        }
        val top = (next?.bottom ?: 0f) + pad
        val player = YtMiniBox(left, top, left + width, top + height)
        val columnLeft = min(player.right + GAP * d, right)
        val columnRight = max(right, columnLeft)
        val pillWidth = min(PILL_WIDTH * d, columnRight - columnLeft)
        val pill = YtMiniBox(columnLeft, player.bottom - PILL * d, columnLeft + pillWidth, player.bottom)
        val title = YtMiniBox(columnLeft, player.top, columnRight, max(player.top, pill.top - 6f * d))
        val rest = (spec.height + spec.overlap) * d
        val box = max(rest, player.bottom + pad + spec.overlap * d)
        return YtShapesPeek(YtMiniPeekGeometry(box, player, title, pill, next), thumb)
    }

    /** [box] of a box [height] px tall seen in one [current] px tall: its foot stays on the bar. */
    fun fromFoot(box: YtMiniBox, height: Float, current: Float): YtMiniBox = box.offset(0f, current - height)
}

/** YT: the window's shape and its motion (pure). */
object YtShapesWindow {
    fun smoothstep(from: Float, to: Float, x: Float): Float {
        if (to <= from) return if (x >= to) 1f else 0f
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * The distance from the centre to the edge of a rounded rectangle with half sizes [a], [b]
     * and [corner], along the direction ([ux], [uy]) (a unit vector).
     */
    fun roundRect(ux: Float, uy: Float, a: Float, b: Float, corner: Float): Float {
        val x = kotlin.math.abs(ux)
        val y = kotlin.math.abs(uy)
        val c = corner.coerceIn(0f, min(a, b))
        val tx = if (x > 1e-6f) a / x else Float.MAX_VALUE
        val ty = if (y > 1e-6f) b / y else Float.MAX_VALUE
        val t = min(tx, ty)
        val cx = a - c
        val cy = b - c
        // the straight edge is hit beside the corner: done
        if (c <= 0f || t * x <= cx || t * y <= cy) return t
        // else the ray meets the corner's circle around (cx, cy): the far root of |t·u − C| = c
        val dot = x * cx + y * cy
        val disc = dot * dot - (cx * cx + cy * cy) + c * c
        return dot + sqrt(max(disc, 0f))
    }

    /**
     * The distance to the edge of the cookie at [angle] (radians, 0 = up, clockwise) in a
     * picture with half sizes [a], [b]: the inscribed ellipse times [scale], scalloped.
     */
    fun cookie(angle: Float, a: Float, b: Float, depth: Float, turn: Float, scale: Float, lobes: Int = YtShapesLayout.LOBES): Float {
        // the ellipse's radius in this direction (a circle in the square of the docked picture)
        val sx = sin(angle)
        val sy = cos(angle)
        val ellipse = a * b / sqrt((b * sx) * (b * sx) + (a * sy) * (a * sy)).coerceAtLeast(1e-6f)
        val d = depth.coerceIn(0f, 0.4f)
        val turnRad = turn * PI.toFloat() / 180f
        return ellipse * scale * (1f - d + d * cos(lobes * (angle - turnRad)))
    }

    /** The edge of the window [shape] at [angle], in a picture with half sizes [a], [b]. */
    fun radius(angle: Float, a: Float, b: Float, shape: YtShapesWindowShape): Float {
        val ux = sin(angle)
        val uy = -cos(angle)
        val rect = roundRect(ux, uy, a, b, shape.corner)
        if (shape.cookie <= 0f) return rect
        val c = cookie(angle, a, b, shape.depth, shape.turn, shape.scale)
        // blended along the ray: the scallops grow out of the rounded rectangle and back into it
        return lerp(rect, c, shape.cookie.coerceIn(0f, 1f))
    }

    /**
     * The outline of [shape] in a picture [width] × [height] px as x, y pairs into [out]
     * (2 × [YtShapesLayout.POINTS] floats), clockwise from the top.
     */
    fun outline(width: Float, height: Float, shape: YtShapesWindowShape, out: FloatArray) {
        val a = width / 2f
        val b = height / 2f
        val n = out.size / 2
        for (i in 0 until n) {
            val angle = 2f * PI.toFloat() * i / n
            val r = radius(angle, a, b, shape)
            out[2 * i] = a + r * sin(angle)
            out[2 * i + 1] = b - r * cos(angle)
        }
    }

    /**
     * The window at minimize [progress] and preview [peek] (0 to about 1.15) in a picture with
     * half sizes [a], [b]; [outline] is the corner the host gives the picture at full progress.
     * Minimizing turns the cookie in at the end (from 0.72); the preview smooths the scallops
     * out in its first half, the rounded 4:3 picture is left.
     */
    fun shape(progress: Float, peek: Float, a: Float, b: Float, outline: Float, depth: Float, turn: Float): YtShapesWindowShape {
        val docked = smoothstep(0.72f, 1f, progress)
        val open = smoothstep(0f, 0.5f, peek.coerceAtLeast(0f))
        val cookie = docked * (1f - open)
        // the host rounds the picture by its full corner times the progress
        val corner = min(outline * progress.coerceIn(0f, 1f), min(a, b))
        // the cookie turns in as it appears: from a quarter turn back
        val turnIn = -90f * (1f - docked)
        return YtShapesWindowShape(cookie, corner, depth, turn + turnIn)
    }

    /**
     * The turn of a taken drop at [t] (0 to 1): a quarter turn in three detents of 30°, each
     * snapping a little past its stop and settling (the TICK of the haptic on each).
     */
    fun detents(t: Float, steps: Int = 3, step: Float = 30f): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return steps * step
        val per = 1f / steps
        val index = min((t / per).toInt(), steps - 1)
        val local = (t - index * per) / per
        // into the stop in the first 45 % with a small overshoot, then held
        val u = (local / 0.45f).coerceIn(0f, 1f)
        val back = 1.7f
        val eased = 1f + (back + 1f) * (u - 1f) * (u - 1f) * (u - 1f) + back * (u - 1f) * (u - 1f)
        return (index + eased) * step
    }

    /** A damped swing from 0 (towards 1 first), settling at 0 at the end. */
    fun wobble(t: Float, frequency: Float, decay: Float): Float =
        exp(-decay * t) * sin(2f * PI.toFloat() * frequency * t)

    /**
     * The wave of progress around the cookie at [angle]: the ring [base] px from the centre,
     * swelling by [amplitude] px as a wave that travels with [phase] (0 to 1).
     */
    fun ring(angle: Float, base: Float, amplitude: Float, phase: Float, waves: Int = YtShapesLayout.RING_WAVES): Float =
        base + amplitude * sin(waves * angle - 2f * PI.toFloat() * phase)
}

/** How long the accept's turn takes, in ms; the first [ACCEPT_LIFT_MS] the cookie only lifts. */
internal const val ACCEPT_MS = 500
internal const val ACCEPT_LIFT_MS = 110

/** The detents' share of the accept at [t] (0 to 1 over [ACCEPT_MS]): 0 while it lifts, then 0 to 1. */
internal fun acceptStep(t: Float): Float {
    if (t <= 0f) return 0f
    val ms = t * ACCEPT_MS
    return ((ms - ACCEPT_LIFT_MS) / (ACCEPT_MS - ACCEPT_LIFT_MS)).coerceIn(0f, 1f)
}
