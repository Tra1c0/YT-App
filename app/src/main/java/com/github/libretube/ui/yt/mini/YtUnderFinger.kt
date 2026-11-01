package com.github.libretube.ui.yt.mini

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * YT: the math of dragging the player under the finger (YtMinimizeDrag), without Android, so the
 * JVM tests can check it. All lengths are px, all times seconds.
 */

/** A rectangle in px. */
data class YtBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The rectangle [t] of the way to [to] (MotionLayout moves the views' bounds this way). */
    fun lerp(to: YtBox, t: Float) = YtBox(
        left + (to.left - left) * t,
        top + (to.top - top) * t,
        right + (to.right - right) * t,
        bottom + (to.bottom - bottom) * t,
    )

    fun offset(dx: Float, dy: Float) = YtBox(left + dx, top + dy, right + dx, bottom + dy)
}

/**
 * How far the activity shifts the player container (its translationY) at a progress of the
 * minimize transition: activity_main_scene.xml holds it still until [holdUntil] and then moves
 * it in a straight line (curveFit linear) to [end] at 1. [fixed] when the activity does not move
 * it at all (no tabs): then it stays where it is.
 */
data class YtContainerCurve(val end: Float, val holdUntil: Float = HOLD, val fixed: Float? = null) {
    fun at(p: Float): Float {
        fixed?.let { return it }
        val q = p.coerceIn(0f, 1f)
        if (q <= holdUntil) return 0f
        return end * (q - holdUntil) / (1f - holdUntil)
    }

    /** The progress values between which the curve (and with it the grabbed point) is straight. */
    val breaks: FloatArray
        get() = if (fixed != null || end == 0f) floatArrayOf(0f, 1f) else floatArrayOf(0f, holdUntil, 1f)

    companion object {
        /** The frame (35 of 100) from which activity_main_scene.xml moves the container. */
        const val HOLD = 0.35f
    }
}

/**
 * Where the finger puts the transition: [progress] 0 (open) to 1 (docked), and how many px the
 * finger is [beyond] what the transition can reach (negative above, positive below).
 */
data class YtFingerFit(val progress: Float, val beyond: Float)

/**
 * The point of a view the finger took hold of, as the minimize transition moves the view: it
 * goes from [start] (progress 0) to [end] (progress 1) in a straight line (page px, as
 * MotionLayout lays it out), the page lies at [pageLeft], [pageTop] in the window when the
 * activity has not shifted the container, and the activity shifts it by [curve]. The finger
 * holds the point at [u], [v] of the view's width and height.
 */
class YtUnderFinger(
    val start: YtBox,
    val end: YtBox,
    val u: Float,
    val v: Float,
    val pageLeft: Float,
    val pageTop: Float,
    val curve: YtContainerCurve,
) {
    fun box(p: Float): YtBox = start.lerp(end, p)

    /** The grabbed point's window x at [p], before any sideways offset. */
    fun pointX(p: Float): Float {
        val b = box(p)
        return pageLeft + b.left + u * b.width
    }

    /** The grabbed point's window y at [p], before the page's own offset. */
    fun pointY(p: Float): Float {
        val b = box(p)
        return pageTop + curve.at(p) + b.top + v * b.height
    }

    /**
     * The progress at which the grabbed point lies at window y [y]. Between the breaks of the
     * curve the point moves in a straight line, so each piece solves exactly; where several
     * pieces reach [y] (a layout in which the point moves back), the one closest to [near] wins,
     * so the transition never jumps. Past what it can reach: the nearer end and the rest as
     * [YtFingerFit.beyond].
     */
    fun fit(y: Float, near: Float = 0f): YtFingerFit {
        val breaks = curve.breaks
        var best = Float.NaN
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until breaks.size - 1) {
            val a = breaks[i]
            val b = breaks[i + 1]
            val ya = pointY(a)
            val yb = pointY(b)
            if (y < min(ya, yb) || y > max(ya, yb)) continue
            val p = if (abs(yb - ya) < FLAT) near.coerceIn(a, b) else a + (b - a) * (y - ya) / (yb - ya)
            val distance = abs(p - near)
            if (distance < bestDistance) {
                best = p
                bestDistance = distance
            }
        }
        if (!best.isNaN()) return YtFingerFit(best.coerceIn(0f, 1f), 0f)
        val y0 = pointY(0f)
        val y1 = pointY(1f)
        val p = if (abs(y - y0) <= abs(y - y1)) 0f else 1f
        return YtFingerFit(p, y - if (p == 0f) y0 else y1)
    }

    /**
     * Where a release at window y [y] with the finger's [velocity] (px/s, + = down) comes to rest:
     * 1 (docked) when the flick would carry the point at least half way down, else 0 (open).
     */
    fun settle(y: Float, velocity: Float, near: Float): Int =
        if (fit(y + YtDragMath.project(velocity), near).progress >= 0.5f) 1 else 0

    private companion object {
        /** A piece along which the point moves less than this px counts as flat. */
        const val FLAT = 1e-3f
    }
}

/** The small rules of the drag: rubber band, where a flick ends, the sideways follow. */
object YtDragMath {
    /** How far past a limit something moves for [overshoot] px of pull: less and less (ytRubberBand). */
    fun rubberBand(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float =
        if (dimension <= 0f) 0f else (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))

    /** How much further a flick at [velocity] px/s carries, as scrolling decelerates (ytProject). */
    fun project(velocity: Float, decelerationRate: Float = 0.998f): Float =
        (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

    /** The pull that [rubberBand] turns into [moved] (it never quite reaches [dimension]). */
    fun unRubberBand(moved: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f || moved == 0f) return 0f
        val a = min(abs(moved), dimension * 0.999f)
        // moved = x d c / (d + c x), solved for x
        val x = a * dimension / (constant * (dimension - a))
        return if (moved < 0f) -x else x
    }

    /**
     * The sideways offset for [offset] px between finger and grabbed point: 1:1 up to [linear],
     * then with a gentle rubber band over [dimension], so a far pull still moves it a little.
     */
    fun follow(offset: Float, linear: Float, dimension: Float): Float {
        val a = abs(offset)
        if (a <= linear) return offset
        val more = linear + rubberBand(a - linear, dimension)
        return if (offset < 0f) -more else more
    }

    /** The offset that [follow] turns into [moved]. */
    fun unFollow(moved: Float, linear: Float, dimension: Float): Float {
        val a = abs(moved)
        if (a <= linear) return moved
        val more = linear + unRubberBand(a - linear, dimension)
        return if (moved < 0f) -more else more
    }

    /**
     * Whether letting go of the docked player [below] px under its place, moving at [velocity]
     * px/s, throws it away: past [threshold] with the flick, and not flicked back up.
     */
    fun closes(below: Float, velocity: Float, threshold: Float): Boolean =
        below > 0f && velocity > -FLICK_BACK && below + project(velocity, 0.99f) >= threshold

    /** An upward flick faster than this (px/s) keeps the player even past the threshold. */
    private const val FLICK_BACK = 300f
}

/**
 * A damped spring toward a target, solved exactly for each step (no integration error, a late
 * frame only moves it further along the same curve). [stiffness] in 1/s², [dampingRatio] 1 =
 * critical: no overshoot unless it is thrown at the target; less, a little settle.
 */
class YtSpring(private val stiffness: Float, private val dampingRatio: Float) {
    var position = 0f
    var velocity = 0f

    fun step(target: Float, dt: Float) {
        if (dt <= 0f) return
        val w = sqrt(stiffness.toDouble())
        val z = dampingRatio.toDouble()
        val x0 = (position - target).toDouble()
        val v0 = velocity.toDouble()
        val t = dt.toDouble()
        val x: Double
        val v: Double
        if (abs(z - 1.0) < 1e-4) {
            // critical: x = (a + b t) e^(-w t)
            val b = v0 + w * x0
            val e = exp(-w * t)
            x = (x0 + b * t) * e
            v = (b - w * (x0 + b * t)) * e
        } else if (z < 1.0) {
            val wd = w * sqrt(1.0 - z * z)
            val e = exp(-z * w * t)
            val c = (v0 + z * w * x0) / wd
            val cos = cos(wd * t)
            val sin = sin(wd * t)
            x = e * (x0 * cos + c * sin)
            v = e * ((c * wd - z * w * x0) * cos - (x0 * wd + z * w * c) * sin)
        } else {
            val s = sqrt(z * z - 1.0)
            val r1 = -w * (z - s)
            val r2 = -w * (z + s)
            val c2 = (v0 - r1 * x0) / (r2 - r1)
            val c1 = x0 - c2
            x = c1 * exp(r1 * t) + c2 * exp(r2 * t)
            v = c1 * r1 * exp(r1 * t) + c2 * r2 * exp(r2 * t)
        }
        position = (target + x).toFloat()
        velocity = v.toFloat()
    }

    /** Close enough to [target] and slow enough to stop without anyone seeing a jump. */
    fun isAtRest(target: Float, distance: Float = 0.5f, speed: Float = 20f): Boolean =
        abs(position - target) <= distance && abs(velocity) <= speed

    companion object {
        /** The stiffness for a spring that swings once in [response] seconds (Apple's "response"). */
        fun stiffnessFor(response: Float): Float {
            val w = 2.0 * PI / response
            return (w * w).toFloat()
        }
    }
}

/** The springs that take over from the finger when the minimize drag lets go. */
object YtMinimizeSprings {
    /**
     * Docking: quick, onto the bar with next to no overshoot of its own (the landing is the mini
     * player's); damped a little under critical so it arrives instead of creeping the last px.
     */
    fun dock() = YtSpring(YtSpring.stiffnessFor(0.38f), 0.9f)

    /** Opening: a breath of settle at the top. */
    fun open() = YtSpring(YtSpring.stiffnessFor(0.42f), 0.86f)

    /** The sideways offset going back to 0. */
    fun sideways() = YtSpring(YtSpring.stiffnessFor(0.36f), 0.9f)
}
