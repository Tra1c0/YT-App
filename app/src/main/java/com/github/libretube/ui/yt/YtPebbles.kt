package com.github.libretube.ui.yt

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * YT: a small physics world for the pebbles of the design "Kiesel". Every body is a capsule:
 * two points held at a fixed distance and a radius around the segment between them, so pills
 * roll, tip over and stack, and a body whose points coincide is a ball. Position based: points
 * move by Verlet integration, then distance, contact and wall constraints are solved a few
 * times per sub step. A finger can grab a body at any point and throw it; gravity can follow
 * the tilt of the phone.
 */

/** One pebble: a capsule of [radius] around a segment of [length] (0: a ball). */
class YtPebble(
    val radius: Float,
    val length: Float,
    /** What the pebble stands for, handed back on a tap. */
    val tag: Any?,
) {
    internal var ax = 0f
    internal var ay = 0f
    internal var bx = 0f
    internal var by = 0f
    internal var pax = 0f
    internal var pay = 0f
    internal var pbx = 0f
    internal var pby = 0f

    /** Heavier pebbles push lighter ones away. */
    internal val mass: Float = radius * (length + radius)

    val centerX: Float get() = (ax + bx) / 2f
    val centerY: Float get() = (ay + by) / 2f

    /** Rotation in degrees. */
    val angle: Float
        get() = if (length == 0f) 0f else Math.toDegrees(atan2(by - ay, bx - ax).toDouble()).toFloat()
}

class YtPebbleWorld {
    val pebbles = ArrayList<YtPebble>()
    var width = 0f
    var height = 0f

    /** Direction of gravity on the screen (y points down), length 0 to 1. */
    var gravityX = 0f
    var gravityY = 1f

    /** Gravity in px/s² at full strength. */
    var gravity = 2600f

    /** How far the points moved in the last step, to let the world rest when nothing moves. */
    var motion = 0f
        private set

    private var grabbed: YtPebble? = null
    private var grabT = 0f
    private var grabX = 0f
    private var grabY = 0f

    fun add(pebble: YtPebble, x: Float, y: Float, degrees: Float, velocityX: Float = 0f, velocityY: Float = 0f) {
        val a = Math.toRadians(degrees.toDouble())
        val dx = (cos(a) * pebble.length / 2).toFloat()
        val dy = (sin(a) * pebble.length / 2).toFloat()
        pebble.ax = x - dx
        pebble.ay = y - dy
        pebble.bx = x + dx
        pebble.by = y + dy
        // the previous positions carry the starting velocity (per 1/60 s)
        pebble.pax = pebble.ax - velocityX / 60f
        pebble.pay = pebble.ay - velocityY / 60f
        pebble.pbx = pebble.bx - velocityX / 60f
        pebble.pby = pebble.by - velocityY / 60f
        pebbles += pebble
    }

    /** The pebble under a point (the topmost, i.e. the last drawn), or null. */
    fun hit(x: Float, y: Float, slop: Float): YtPebble? = pebbles.lastOrNull { p ->
        val t = segmentParameter(p, x, y)
        val cx = p.ax + (p.bx - p.ax) * t
        val cy = p.ay + (p.by - p.ay) * t
        hypot(x - cx, y - cy) <= p.radius + slop
    }

    fun grab(pebble: YtPebble, x: Float, y: Float) {
        grabbed = pebble
        grabT = segmentParameter(pebble, x, y)
        grabX = x
        grabY = y
    }

    fun moveGrab(x: Float, y: Float) {
        grabX = x
        grabY = y
    }

    fun release() {
        grabbed = null
    }

    /** Advances the world by [dt] seconds. */
    fun step(dt: Float) {
        if (width <= 0f || height <= 0f) return
        val subSteps = 4
        val h = dt.coerceAtMost(1f / 30f) / subSteps
        motion = 0f
        repeat(subSteps) {
            integrate(h)
            repeat(3) {
                keepLengths()
                collide()
                pullGrab()
                walls()
            }
        }
    }

    private fun integrate(h: Float) {
        val gx = gravityX * gravity * h * h
        val gy = gravityY * gravity * h * h
        // speed limit per sub step, so a wild throw cannot tunnel through a pebble
        val limit = 5200f * h
        for (p in pebbles) {
            var vax = (p.ax - p.pax) * 0.997f
            var vay = (p.ay - p.pay) * 0.997f
            var vbx = (p.bx - p.pbx) * 0.997f
            var vby = (p.by - p.pby) * 0.997f
            val speed = maxOf(hypot(vax, vay), hypot(vbx, vby))
            if (speed > limit) {
                val k = limit / speed
                vax *= k; vay *= k; vbx *= k; vby *= k
            }
            motion = maxOf(motion, speed)
            p.pax = p.ax; p.pay = p.ay; p.pbx = p.bx; p.pby = p.by
            p.ax += vax + gx; p.ay += vay + gy
            p.bx += vbx + gx; p.by += vby + gy
        }
    }

    private fun keepLengths() {
        for (p in pebbles) {
            val dx = p.bx - p.ax
            val dy = p.by - p.ay
            if (p.length == 0f) {
                val mx = (p.ax + p.bx) / 2f
                val my = (p.ay + p.by) / 2f
                p.ax = mx; p.ay = my; p.bx = mx; p.by = my
                continue
            }
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
            val diff = (d - p.length) / d * 0.5f
            p.ax += dx * diff; p.ay += dy * diff
            p.bx -= dx * diff; p.by -= dy * diff
        }
    }

    private fun collide() {
        val n = pebbles.size
        for (i in 0 until n) {
            val p = pebbles[i]
            for (j in i + 1 until n) {
                val q = pebbles[j]
                // quick reject by bounding circles
                val reachP = p.length / 2f + p.radius
                val reachQ = q.length / 2f + q.radius
                val cdx = q.centerX - p.centerX
                val cdy = q.centerY - p.centerY
                if (abs(cdx) > reachP + reachQ || abs(cdy) > reachP + reachQ) continue
                contact(p, q)
            }
        }
    }

    /** Pushes two capsules apart at their closest points, each in proportion to the other's mass. */
    private fun contact(p: YtPebble, q: YtPebble) {
        val (s, t) = closestParameters(p, q)
        val px = p.ax + (p.bx - p.ax) * s
        val py = p.ay + (p.by - p.ay) * s
        val qx = q.ax + (q.bx - q.ax) * t
        val qy = q.ay + (q.by - q.ay) * t
        var nx = qx - px
        var ny = qy - py
        var dist = sqrt(nx * nx + ny * ny)
        val reach = p.radius + q.radius
        if (dist >= reach) return
        if (dist < 0.0001f) {
            nx = 0f; ny = -1f; dist = 0f
        } else {
            nx /= dist; ny /= dist
        }
        val depth = reach - dist
        val total = p.mass + q.mass
        move(p, s, -nx * depth * q.mass / total, -ny * depth * q.mass / total)
        move(q, t, nx * depth * p.mass / total, ny * depth * p.mass / total)
        // a little friction between pebbles in contact
        slow(p, 0.985f)
        slow(q, 0.985f)
    }

    /** Moves the point at [t] along the segment by (dx, dy), shared by both ends. */
    private fun move(p: YtPebble, t: Float, dx: Float, dy: Float) {
        if (p === grabbed) return
        val wa = 1f - t
        val wb = t
        val k = 1f / (wa * wa + wb * wb)
        p.ax += dx * wa * k; p.ay += dy * wa * k
        p.bx += dx * wb * k; p.by += dy * wb * k
    }

    private fun slow(p: YtPebble, factor: Float) {
        p.pax = p.ax - (p.ax - p.pax) * factor
        p.pay = p.ay - (p.ay - p.pay) * factor
        p.pbx = p.bx - (p.bx - p.pbx) * factor
        p.pby = p.by - (p.by - p.pby) * factor
    }

    private fun pullGrab() {
        val p = grabbed ?: return
        val x = p.ax + (p.bx - p.ax) * grabT
        val y = p.ay + (p.by - p.ay) * grabT
        val wa = 1f - grabT
        val wb = grabT
        val k = 0.6f / (wa * wa + wb * wb)
        val dx = grabX - x
        val dy = grabY - y
        p.ax += dx * wa * k; p.ay += dy * wa * k
        p.bx += dx * wb * k; p.by += dy * wb * k
    }

    private fun walls() {
        for (p in pebbles) {
            val r = p.radius
            // a: walls with a little bounce and friction along them
            if (p.ax < r) { val v = p.ax - p.pax; p.ax = r; p.pax = r + v * 0.25f; p.pay = p.ay - (p.ay - p.pay) * 0.9f }
            if (p.ax > width - r) { val v = p.ax - p.pax; p.ax = width - r; p.pax = p.ax + v * 0.25f; p.pay = p.ay - (p.ay - p.pay) * 0.9f }
            if (p.ay < r) { val v = p.ay - p.pay; p.ay = r; p.pay = r + v * 0.25f; p.pax = p.ax - (p.ax - p.pax) * 0.9f }
            if (p.ay > height - r) { val v = p.ay - p.pay; p.ay = height - r; p.pay = p.ay + v * 0.25f; p.pax = p.ax - (p.ax - p.pax) * 0.86f }
            // b
            if (p.bx < r) { val v = p.bx - p.pbx; p.bx = r; p.pbx = r + v * 0.25f; p.pby = p.by - (p.by - p.pby) * 0.9f }
            if (p.bx > width - r) { val v = p.bx - p.pbx; p.bx = width - r; p.pbx = p.bx + v * 0.25f; p.pby = p.by - (p.by - p.pby) * 0.9f }
            if (p.by < r) { val v = p.by - p.pby; p.by = r; p.pby = r + v * 0.25f; p.pbx = p.bx - (p.bx - p.pbx) * 0.9f }
            if (p.by > height - r) { val v = p.by - p.pby; p.by = height - r; p.pby = p.by + v * 0.25f; p.pbx = p.bx - (p.bx - p.pbx) * 0.86f }
        }
    }

    /** Where along the segment of [p] (0 to 1) the point closest to (x, y) lies. */
    private fun segmentParameter(p: YtPebble, x: Float, y: Float): Float {
        val dx = p.bx - p.ax
        val dy = p.by - p.ay
        val len2 = dx * dx + dy * dy
        if (len2 < 0.0001f) return 0.5f
        return (((x - p.ax) * dx + (y - p.ay) * dy) / len2).coerceIn(0f, 1f)
    }

    /** The parameters of the closest points between the segments of two capsules. */
    private fun closestParameters(p: YtPebble, q: YtPebble): Pair<Float, Float> {
        val d1x = p.bx - p.ax
        val d1y = p.by - p.ay
        val d2x = q.bx - q.ax
        val d2y = q.by - q.ay
        val rx = p.ax - q.ax
        val ry = p.ay - q.ay
        val a = d1x * d1x + d1y * d1y
        val e = d2x * d2x + d2y * d2y
        val f = d2x * rx + d2y * ry
        val eps = 0.0001f
        if (a <= eps && e <= eps) return 0f to 0f
        if (a <= eps) return 0f to (f / e).coerceIn(0f, 1f)
        val c = d1x * rx + d1y * ry
        if (e <= eps) return (-c / a).coerceIn(0f, 1f) to 0f
        val b = d1x * d2x + d1y * d2y
        val denom = a * e - b * b
        var s = if (denom > eps) ((b * f - c * e) / denom).coerceIn(0f, 1f) else 0.5f
        var t = (b * s + f) / e
        if (t < 0f) {
            t = 0f
            s = (-c / a).coerceIn(0f, 1f)
        } else if (t > 1f) {
            t = 1f
            s = ((b - c) / a).coerceIn(0f, 1f)
        }
        return s to t
    }
}
