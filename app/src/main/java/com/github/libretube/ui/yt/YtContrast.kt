package com.github.libretube.ui.yt

import kotlin.math.pow

/**
 * YT: text contrast as WCAG 2 measures it, on plain ARGB ints so it is tested on the JVM.
 * The overlays of the home sit on light and dark designs alike; a colour that reads on one
 * (white on a dark pill) can vanish on another (white on mint). [readable] keeps the design's
 * colour where it already reads and only pushes it towards black or white as far as needed.
 */
object YtContrast {
    /** Body text needs this ratio (WCAG AA). */
    const val BODY = 4.5

    /** [fg] drawn over [bg]: alpha composited, the result opaque. */
    fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24 and 0xFF) / 255.0
        if (a >= 1.0) return fg
        val back = opaque(bg)
        fun channel(shift: Int): Int {
            val f = fg ushr shift and 0xFF
            val b = back ushr shift and 0xFF
            return (f * a + b * (1 - a) + 0.5).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** The relative luminance of [color] (its alpha ignored), 0 black to 1 white. */
    fun luminance(color: Int): Double {
        fun linear(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color ushr 16 and 0xFF) + 0.7152 * linear(color ushr 8 and 0xFF) + 0.0722 * linear(color and 0xFF)
    }

    /** The contrast ratio of [fg] over [bg], 1 to 21; a translucent [fg] counts as composited. */
    fun ratio(fg: Int, bg: Int): Double {
        val back = opaque(bg)
        val a = luminance(over(fg, back))
        val b = luminance(back)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /**
     * [fg] if it reads on [bg] with at least [min]; else [fg] mixed towards black (on a light
     * [bg]) or white (on a dark one) in small steps until it does. The hue stays as long as it can.
     */
    fun readable(fg: Int, bg: Int, min: Double = BODY): Int {
        val back = opaque(bg)
        val start = over(fg, back)
        if (ratio(start, back) >= min) return start
        // towards whichever end gives more room
        val target = if ((1.05) / (luminance(back) + 0.05) >= (luminance(back) + 0.05) / 0.05) WHITE else BLACK
        var t = 0.05
        while (t < 1.0) {
            val mixed = mix(start, target, t)
            if (ratio(mixed, back) >= min) return mixed
            t += 0.05
        }
        return target
    }

    /** [from] mixed with [to] by [t] (0 all [from], 1 all [to]), opaque. */
    fun mix(from: Int, to: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val f = from ushr shift and 0xFF
            val o = to ushr shift and 0xFF
            return (f + (o - f) * t + 0.5).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun opaque(color: Int): Int = color or (0xFF shl 24)

    private const val WHITE = -0x1 // 0xFFFFFFFF
    private const val BLACK = -0x1000000 // 0xFF000000
}
