package com.github.libretube.ui.yt.builder

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * YT: the animator's maths (pure, no allocations on the hot paths): easing curves (cubic Bézier
 * solved for x), colours in OKLab (a perceptual space, so a fade from red to green does not go
 * through a muddy brown), and a damped spring solved exactly (so a step of 8 ms and two of 4 ms
 * land at the same point: frame-rate independent and stable for any step).
 */
object YtCurves {
    /** The control points of [c]: named kinds have fixed ones (the CSS curves). */
    fun x1(c: YtCurve) = when (c.kind) {
        YtCurveKind.EASE_IN, YtCurveKind.EASE_IN_OUT -> 0.42f
        YtCurveKind.BEZIER -> c.x1.coerceIn(0f, 1f)
        else -> 0f
    }

    fun y1(c: YtCurve) = if (c.kind == YtCurveKind.BEZIER) c.y1 else 0f

    fun x2(c: YtCurve) = when (c.kind) {
        YtCurveKind.EASE_OUT, YtCurveKind.EASE_IN_OUT -> 0.58f
        YtCurveKind.BEZIER -> c.x2.coerceIn(0f, 1f)
        else -> 1f
    }

    fun y2(c: YtCurve) = if (c.kind == YtCurveKind.BEZIER) c.y2 else 1f

    /** The eased progress of [c] at [x] (0..1). Bézier y may leave 0..1 (overshoot). */
    fun eval(c: YtCurve, x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return when (c.kind) {
            YtCurveKind.LINEAR -> t
            YtCurveKind.HOLD -> if (t < 1f) 0f else 1f
            else -> bezier(x1(c), y1(c), x2(c), y2(c), t)
        }
    }

    /** Slope dy/dx of [c] at [x] (the speed curve of the graph editor). */
    fun slope(c: YtCurve, x: Float): Float {
        val t0 = x.coerceIn(0f, 1f)
        return when (c.kind) {
            YtCurveKind.LINEAR -> 1f
            YtCurveKind.HOLD -> 0f
            else -> {
                val ax1 = x1(c)
                val ax2 = x2(c)
                val u = solveT(ax1, ax2, t0)
                val dx = derivative(ax1, ax2, u)
                val dy = derivative(y1(c), y2(c), u)
                if (abs(dx) < 1e-6f) (if (dy >= 0f) 1e3f else -1e3f) else dy / dx
            }
        }
    }

    /** y of the curve (0,0)-(x1,y1)-(x2,y2)-(1,1) where its x is [x]. */
    fun bezier(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        return coord(y1, y2, solveT(x1, x2, x))
    }

    private fun coord(p1: Float, p2: Float, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
    }

    private fun derivative(p1: Float, p2: Float, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * p1 + 6f * u * t * (p2 - p1) + 3f * t * t * (1f - p2)
    }

    /** The curve parameter where x(t) = [x]: Newton steps, bisection where the slope is flat. */
    private fun solveT(x1: Float, x2: Float, x: Float): Float {
        var t = x
        repeat(8) {
            val err = coord(x1, x2, t) - x
            if (abs(err) < 1e-6f) return t
            val d = derivative(x1, x2, t)
            if (abs(d) < 1e-6f) return@repeat
            t = (t - err / d).coerceIn(0f, 1f)
        }
        if (abs(coord(x1, x2, t) - x) < 1e-5f) return t
        var lo = 0f
        var hi = 1f
        t = x
        repeat(40) {
            val v = coord(x1, x2, t)
            if (abs(v - x) < 1e-6f) return t
            if (v < x) lo = t else hi = t
            t = (lo + hi) / 2f
        }
        return t
    }
}

/** sRGB colours ↔ OKLab (Björn Ottosson), alpha kept linear. */
object YtOklab {
    private fun toLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    private fun toGamma(c: Float): Float = if (c <= 0.0031308f) 12.92f * c else (1.055 * c.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()

    /** Writes L, a, b and alpha (0..1) of [argb] into [out] from [at]. */
    fun fromArgb(argb: Int, out: FloatArray, at: Int = 0) {
        val r = toLinear(YtColor.red(argb) / 255f)
        val g = toLinear(YtColor.green(argb) / 255f)
        val b = toLinear(YtColor.blue(argb) / 255f)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[at] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[at + 1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[at + 2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        out[at + 3] = YtColor.alpha(argb) / 255f
    }

    /** The ARGB colour of OKLab [l], [a], [b] and [alpha], clipped to sRGB. */
    fun toArgb(l: Float, a: Float, b: Float, alpha: Float): Int {
        val l1 = l + 0.3963377774f * a + 0.2158037573f * b
        val m1 = l - 0.1055613458f * a - 0.0638541728f * b
        val s1 = l - 0.0894841775f * a - 1.2914855480f * b
        val l3 = l1 * l1 * l1
        val m3 = m1 * m1 * m1
        val s3 = s1 * s1 * s1
        val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
        val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
        val bl = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
        fun ch(v: Float) = (toGamma(v.coerceIn(0f, 1f)) * 255f).roundToInt()
        return YtColor.argb((alpha.coerceIn(0f, 1f) * 255f).roundToInt(), ch(r), ch(g), ch(bl))
    }

    fun toArgb(v: FloatArray, at: Int = 0): Int = toArgb(v[at], v[at + 1], v[at + 2], v[at + 3])

    /** The colour between [x] and [y] at [t], in OKLab. */
    fun lerp(x: Int, y: Int, t: Float): Int {
        val a = FloatArray(8)
        fromArgb(x, a, 0)
        fromArgb(y, a, 4)
        return toArgb(
            a[0] + (a[4] - a[0]) * t, a[1] + (a[5] - a[1]) * t, a[2] + (a[6] - a[2]) * t, a[3] + (a[7] - a[3]) * t,
        )
    }
}

/**
 * A damped spring with mass 1, solved in closed form: [step] moves a displacement [x] (from the
 * target) and velocity [v] on by [dt] seconds. Exact for any step, so the result does not depend
 * on the frame rate, and stable however stiff the spring or long the frame.
 */
object YtSpring {
    /** Writes the new displacement and velocity into [out] (0 and 1). */
    fun step(x: Double, v: Double, dt: Double, stiffness: Double, damping: Double, out: DoubleArray) {
        val w0 = sqrt(stiffness.coerceAtLeast(1e-4))
        val z = damping.coerceAtLeast(0.0)
        when {
            z < 1.0 - 1e-6 -> {
                val wd = w0 * sqrt(1.0 - z * z)
                val e = exp(-z * w0 * dt)
                val c = cos(wd * dt)
                val s = sin(wd * dt)
                val b = (v + z * w0 * x) / wd
                out[0] = e * (x * c + b * s)
                out[1] = e * (-z * w0 * (x * c + b * s) + (-x * wd * s + b * wd * c))
            }
            z > 1.0 + 1e-6 -> {
                val q = w0 * sqrt(z * z - 1.0)
                val r1 = -z * w0 + q
                val r2 = -z * w0 - q
                val c1 = (v - r2 * x) / (r1 - r2)
                val c2 = x - c1
                val e1 = exp(r1 * dt)
                val e2 = exp(r2 * dt)
                out[0] = c1 * e1 + c2 * e2
                out[1] = c1 * r1 * e1 + c2 * r2 * e2
            }
            else -> {
                val e = exp(-w0 * dt)
                val b = v + w0 * x
                out[0] = (x + b * dt) * e
                out[1] = (b - w0 * (x + b * dt)) * e
            }
        }
    }

    /** Settled: displacement and velocity both below [threshold] (velocity per second × 0.1). */
    fun settled(x: Double, v: Double, threshold: Double): Boolean = abs(x) <= threshold && abs(v) * 0.1 <= threshold

    /**
     * How long a spring from displacement [x0] with velocity [v0] takes to settle within
     * [threshold], in ms (at most [maxMs]). The editor shows it as the spring's length.
     */
    fun settleMs(x0: Double, v0: Double, stiffness: Double, damping: Double, threshold: Double, maxMs: Int = 20_000): Int {
        if (settled(x0, v0, threshold)) return 0
        val out = DoubleArray(2)
        var x = x0
        var v = v0
        var t = 0
        // the last time it was outside the band, found in 4 ms steps
        var lastOutside = 0
        while (t < maxMs) {
            step(x, v, 0.004, stiffness, damping, out)
            x = out[0]
            v = out[1]
            t += 4
            if (!settled(x, v, threshold)) lastOutside = t
            else if (t - lastOutside > 400) break
        }
        return (lastOutside + 4).coerceAtMost(maxMs)
    }

    /** The settling threshold of a property: a hundredth of a dp, a thousandth of a scale. */
    fun threshold(prop: YtAnimProp): Double = when (prop) {
        YtAnimProp.SCALE_X, YtAnimProp.SCALE_Y, YtAnimProp.OPACITY, YtAnimProp.PIVOT_X, YtAnimProp.PIVOT_Y,
        YtAnimProp.SHADOW_ALPHA, YtAnimProp.GRADIENT_SHIFT, YtAnimProp.SHIMMER -> 0.0005
        YtAnimProp.FILL_COLOR, YtAnimProp.STROKE_COLOR, YtAnimProp.TEXT_COLOR -> 0.001
        else -> 0.01
    }
}
