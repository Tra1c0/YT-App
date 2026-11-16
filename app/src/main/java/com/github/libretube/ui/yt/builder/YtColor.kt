package com.github.libretube.ui.yt.builder

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * YT: colours of the theme builder as ARGB ints (pure, for the JVM tests): HEX, RGB and HSL
 * in and out, and gradient stops.
 */
object YtColor {
    /** Parses "#RGB", "#ARGB", "#RRGGBB", "#AARRGGBB" (the # is optional); null if it is none. */
    fun parse(text: String?): Int? {
        val raw = text?.trim()?.removePrefix("#") ?: return null
        if (raw.isEmpty() || raw.any { it.digitToIntOrNull(16) == null }) return null
        val full = when (raw.length) {
            3 -> "FF" + raw.map { "$it$it" }.joinToString("")
            4 -> raw.map { "$it$it" }.joinToString("")
            6 -> "FF$raw"
            8 -> raw
            else -> return null
        }
        return full.toLong(16).toInt()
    }

    /** "#AARRGGBB", upper case. */
    fun hex(argb: Int): String = "#" + (argb.toLong() and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')

    /** "#RRGGBB" without the alpha. */
    fun hexRgb(argb: Int): String = "#" + (argb.toLong() and 0xFFFFFFL).toString(16).uppercase().padStart(6, '0')

    fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    fun red(argb: Int): Int = (argb shr 16) and 0xFF
    fun green(argb: Int): Int = (argb shr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun withAlpha(argb: Int, alpha: Float): Int = (argb and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24)

    /** Hue 0..360, saturation and lightness 0..1. */
    data class Hsl(val h: Float, val s: Float, val l: Float)

    fun toHsl(argb: Int): Hsl {
        val r = red(argb) / 255f
        val g = green(argb) / 255f
        val b = blue(argb) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        if (d < 1e-6f) return Hsl(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return Hsl((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    fun fromHsl(hsl: Hsl, alpha: Int = 255): Int {
        val h = ((hsl.h % 360f) + 360f) % 360f
        val s = hsl.s.coerceIn(0f, 1f)
        val l = hsl.l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(alpha, ((r + m) * 255f).roundToInt(), ((g + m) * 255f).roundToInt(), ((b + m) * 255f).roundToInt())
    }

    /** The colour between [a] and [b] at [t] (0..1), per channel. */
    fun lerp(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * f).roundToInt()
        return argb(ch(alpha(a), alpha(b)), ch(red(a), red(b)), ch(green(a), green(b)), ch(blue(a), blue(b)))
    }

    /** WCAG relative luminance (YtContrast's, the one every design check uses). */
    fun luminance(argb: Int): Double = com.github.libretube.ui.yt.YtContrast.luminance(argb)

    /** WCAG contrast of two colours, 1..21 ([a] laid over [b] where it is translucent). */
    fun contrast(a: Int, b: Int): Double = com.github.libretube.ui.yt.YtContrast.ratio(a, b)

    /** [fore] laid over [back] by its alpha (the colour the eye sees). */
    fun over(fore: Int, back: Int): Int {
        val a = alpha(fore) / 255f
        return lerp(back or 0xFF000000.toInt(), fore or 0xFF000000.toInt(), a)
    }

    /**
     * Stops sorted by position, positions clamped to 0..1, at most [max]; one stop becomes two of
     * the same colour, none becomes [fallback] twice, so every gradient can be drawn.
     */
    fun normalizeStops(stops: List<YtStop>, fallback: Int, max: Int = 16): List<YtStop> {
        val clean = stops.filter { !it.at.isNaN() }.map { it.copy(at = it.at.coerceIn(0f, 1f)) }.sortedBy { it.at }.take(max)
        return when (clean.size) {
            0 -> listOf(YtStop(0f, fallback), YtStop(1f, fallback))
            1 -> listOf(clean[0].copy(at = 0f), clean[0].copy(at = 1f))
            else -> clean
        }
    }

    /** Colour of a gradient at [t] (0..1), from normalized stops. */
    fun sample(stops: List<YtStop>, t: Float): Int {
        if (stops.isEmpty()) return 0
        val x = t.coerceIn(0f, 1f)
        if (x <= stops.first().at) return stops.first().color
        if (x >= stops.last().at) return stops.last().color
        for (i in 1 until stops.size) {
            val a = stops[i - 1]
            val b = stops[i]
            if (x <= b.at) {
                val span = b.at - a.at
                return if (span <= 0f) b.color else lerp(a.color, b.color, (x - a.at) / span)
            }
        }
        return stops.last().color
    }

    /**
     * Gradient as text, "linear 90 #FF000000 0, #FFFFFFFF 1" or "radial 0.5 0.5 0.5 …", for
     * copying between elements; [parseGradient] reads it back.
     */
    fun formatGradient(paint: YtPaint): String = when (paint.type) {
        YtPaintType.SOLID, YtPaintType.IMAGE -> hex(paint.color)
        YtPaintType.LINEAR -> "linear ${fmt(paint.angle)} " + paint.stops.joinToString(", ") { "${hex(it.color)} ${fmt(it.at)}" }
        YtPaintType.RADIAL -> "radial ${fmt(paint.centerX)} ${fmt(paint.centerY)} ${fmt(paint.radius)} " +
            paint.stops.joinToString(", ") { "${hex(it.color)} ${fmt(it.at)}" }
    }

    fun parseGradient(text: String): YtPaint? {
        val t = text.trim()
        parse(t)?.let { return YtPaint.solid(it) }
        val parts = t.split(' ', limit = 2)
        if (parts.size < 2) return null
        val head = parts[0].lowercase()
        val rest = parts[1].trim()
        val numbers = when (head) {
            "linear" -> 1
            "radial" -> 3
            else -> return null
        }
        val tokens = rest.split(' ', limit = numbers + 1)
        if (tokens.size < numbers + 1) return null
        val values = tokens.take(numbers).map { it.toFloatOrNull() ?: return null }
        val stops = tokens[numbers].split(',').map { stop ->
            val p = stop.trim().split(' ').filter { it.isNotEmpty() }
            if (p.size != 2) return null
            YtStop(p[1].toFloatOrNull() ?: return null, parse(p[0]) ?: return null)
        }
        return if (head == "linear") {
            YtPaint(type = YtPaintType.LINEAR, angle = values[0], stops = stops)
        } else {
            YtPaint(type = YtPaintType.RADIAL, centerX = values[0], centerY = values[1], radius = values[2], stops = stops)
        }
    }

    private fun fmt(v: Float): String = if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0')
}
