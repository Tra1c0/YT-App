package com.github.libretube.ui.yt.ambilight

import com.github.libretube.ui.yt.YtContrast
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * YT: the colour maths of the ambilight, pure Kotlin (tested on the JVM). A tiny copy of the
 * video frame (48 x 27 px or so) is cut free of letterbox bars, split into eight zones along its
 * edges, and each zone is averaged in linear light (near-black and near-white pixels count
 * little, colourful ones more). The averages become Oklab colours that glide towards each new
 * sample, so the glow follows the picture softly and never flickers; a scene cut makes the glide
 * quicker for a moment.
 */

/** The zones along the edges of the frame, clockwise from the top left corner. */
object YtAmbilightZones {
    const val TOP_LEFT = 0
    const val TOP = 1
    const val TOP_RIGHT = 2
    const val RIGHT = 3
    const val BOTTOM_RIGHT = 4
    const val BOTTOM = 5
    const val BOTTOM_LEFT = 6
    const val LEFT = 7

    /** The edge zones. */
    const val COUNT = 8

    /** The whole frame's colour (mean of the zones), after the edge zones in display arrays. */
    const val ALL = 8

    /** Edge zones plus [ALL]. */
    const val DISPLAY = 9

    /** Floats per zone in a target or display array: Oklab L, a, b and the presence. */
    const val CHANNELS = 4

    /** Floats per zone in a raw array: linear r, g, b and the plain mean luminance. */
    const val RAW = 4

    /** Zone of a pixel by its third of the frame: row * 3 + column; -1 is the centre. */
    internal val MAP = intArrayOf(
        TOP_LEFT, TOP, TOP_RIGHT,
        LEFT, -1, RIGHT,
        BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT,
    )
}

object YtAmbilightMath {
    /** A letterbox pixel: no channel brighter than this (sRGB 0..255; video noise stays below). */
    const val DARK = 22

    /** A bar is never more than this share of the frame on one side. */
    const val MAX_BAR = 0.35f

    /** Rows (or columns) of a bar may be this dark at least, as a share of the line. */
    const val BAR_SHARE = 0.92f

    /** A light line inside a bar (burned-in subtitles) up to this many px is skipped. */
    const val BAR_GAP = 3

    /** How deep the edge zones reach into the picture, as a share of its width or height. */
    const val DEPTH = 0.3f

    /** Saturation boost of the glow (Oklab chroma) and its upper bound. */
    const val CHROMA_BOOST = 1.35f
    const val CHROMA_MAX = 0.25f

    private val toLinear = FloatArray(256) { i ->
        val s = i / 255f
        if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    /** sRGB 0..255 to linear light 0..1 (a table, no pow per pixel). */
    fun linear(channel: Int): Float = toLinear[channel and 0xFF]

    /** Linear light 0..1 to sRGB 0..255. */
    fun encode(linear: Float): Int {
        val c = linear.coerceIn(0f, 1f)
        val s = if (c <= 0.0031308f) c * 12.92f else (1.055f * c.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
        return (s * 255f).roundToInt().coerceIn(0, 255)
    }

    // -- letterbox ------------------------------------------------------------------------------

    /**
     * The picture inside the frame without its black bars: [out] = left, top, right, bottom
     * (right and bottom exclusive). A bar is a run of dark lines from the edge; a short light run
     * inside it (subtitles in the bar) is skipped when dark lines follow. A frame that is dark
     * nearly everywhere (a fade to black) is taken whole: its zones are dark anyway, and a crop
     * found in it would jump.
     */
    fun activeArea(pixels: IntArray, width: Int, height: Int, out: IntArray) {
        out[0] = 0
        out[1] = 0
        out[2] = width
        out[3] = height
        if (width <= 0 || height <= 0 || pixels.size < width * height) return
        val maxRows = (height * MAX_BAR).toInt()
        val maxCols = (width * MAX_BAR).toInt()
        var top = bar(maxRows) { darkRow(pixels, width, it, 0, width) }
        var bottom = bar(maxRows) { darkRow(pixels, width, height - 1 - it, 0, width) }
        // dark from both sides as far as a bar may reach: a dark frame, not a letterbox
        if (maxRows > 0 && top >= maxRows && bottom >= maxRows) return
        if (!even(top, bottom)) {
            // bars are centred; a dark sky on one side only is picture
            top = min(top, bottom)
            bottom = top
        }
        val t = top
        val b = height - bottom
        var left = bar(maxCols) { darkColumn(pixels, width, it, t, b) }
        var right = bar(maxCols) { darkColumn(pixels, width, width - 1 - it, t, b) }
        if (maxCols > 0 && left >= maxCols && right >= maxCols) {
            left = 0
            right = 0
        } else if (!even(left, right)) {
            left = min(left, right)
            right = left
        }
        out[0] = left
        out[1] = t
        out[2] = width - right
        out[3] = b
    }

    /** Two bars that may belong to one centred letterbox (a px of rounding apart, or a quarter). */
    private fun even(a: Int, b: Int): Boolean = abs(a - b) <= max(1, max(a, b) / 4)

    /** The length of the bar from one edge, at most [limit] lines; [dark] says if line i is dark. */
    private inline fun bar(limit: Int, dark: (Int) -> Boolean): Int {
        var i = 0
        var end = 0
        while (i < limit) {
            if (dark(i)) {
                i++
                end = i
                continue
            }
            // a light run: skipped only if it is short and dark lines follow
            var j = i
            while (j < limit && j - i < BAR_GAP && !dark(j)) j++
            if (j - i >= BAR_GAP || j >= limit) break
            var darkAfter = 0
            var k = j
            while (k < limit && dark(k) && darkAfter < 2) {
                darkAfter++
                k++
            }
            if (darkAfter < 2) break
            i = j
        }
        return end
    }

    private fun isDark(c: Int): Boolean =
        (c shr 16 and 0xFF) <= DARK && (c shr 8 and 0xFF) <= DARK && (c and 0xFF) <= DARK

    private fun darkRow(pixels: IntArray, width: Int, y: Int, from: Int, to: Int): Boolean {
        var dark = 0
        val base = y * width
        for (x in from until to) if (isDark(pixels[base + x])) dark++
        return dark >= (to - from) * BAR_SHARE
    }

    private fun darkColumn(pixels: IntArray, width: Int, x: Int, from: Int, to: Int): Boolean {
        if (to <= from) return true
        var dark = 0
        for (y in from until to) if (isDark(pixels[y * width + x])) dark++
        return dark >= (to - from) * BAR_SHARE
    }

    // -- zones ----------------------------------------------------------------------------------

    /**
     * How much a pixel counts in its zone's colour: near-black little (shadows, bars that were
     * not cut), near-white little (subtitles, flashes, sky), colourful pixels more.
     */
    fun weight(r: Int, g: Int, b: Int, luminance: Float): Float {
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val saturation = (hi - lo) / 255f
        val base = when {
            hi <= DARK -> 0.03f
            luminance > 0.80f && saturation < 0.12f -> 0.15f
            else -> 1f
        }
        return base * (1f + 2.5f * saturation)
    }

    /**
     * The colour of each edge zone of the picture [area] (see [activeArea]) in [pixels]:
     * [out] gets, per zone ([YtAmbilightZones.RAW] floats), the weighted mean in linear light
     * (r, g, b) and the plain mean luminance (how bright the zone is, for the glow's presence).
     * The centre counts for no zone. Allocates nothing.
     */
    fun zones(pixels: IntArray, width: Int, area: IntArray, out: FloatArray, sums: FloatArray, depth: Float = DEPTH) {
        // sums: per zone r, g, b, weight, plain luminance sum, count, plain r, g, b (9 floats)
        sums.fill(0f)
        val left = area[0]
        val top = area[1]
        val right = area[2]
        val bottom = area[3]
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) {
            out.fill(0f)
            return
        }
        val dx = max(1, (w * depth).roundToInt())
        val dy = max(1, (h * depth).roundToInt())
        for (y in top until bottom) {
            val row = when {
                y < top + dy -> 0
                y >= bottom - dy -> 2
                else -> 1
            }
            val base = y * width
            for (x in left until right) {
                val column = when {
                    x < left + dx -> 0
                    x >= right - dx -> 2
                    else -> 1
                }
                val zone = YtAmbilightZones.MAP[row * 3 + column]
                if (zone < 0) continue
                val c = pixels[base + x]
                val r8 = c shr 16 and 0xFF
                val g8 = c shr 8 and 0xFF
                val b8 = c and 0xFF
                val lr = toLinear[r8]
                val lg = toLinear[g8]
                val lb = toLinear[b8]
                val lum = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
                val wt = weight(r8, g8, b8, lum)
                val s = zone * SUMS
                sums[s] += lr * wt
                sums[s + 1] += lg * wt
                sums[s + 2] += lb * wt
                sums[s + 3] += wt
                sums[s + 4] += lum
                sums[s + 5] += 1f
                sums[s + 6] += lr
                sums[s + 7] += lg
                sums[s + 8] += lb
            }
        }
        for (z in 0 until YtAmbilightZones.COUNT) {
            val s = z * SUMS
            val o = z * YtAmbilightZones.RAW
            val count = sums[s + 5]
            if (count <= 0f) {
                out[o] = 0f
                out[o + 1] = 0f
                out[o + 2] = 0f
                out[o + 3] = 0f
                continue
            }
            val wt = sums[s + 3]
            if (wt > count * 0.01f) {
                out[o] = sums[s] / wt
                out[o + 1] = sums[s + 1] / wt
                out[o + 2] = sums[s + 2] / wt
            } else {
                out[o] = sums[s + 6] / count
                out[o + 1] = sums[s + 7] / count
                out[o + 2] = sums[s + 8] / count
            }
            out[o + 3] = sums[s + 4] / count
        }
    }

    /** Floats [zones] needs in its scratch array. */
    const val SUMS = 9
    const val SUMS_SIZE = SUMS * YtAmbilightZones.COUNT

    /**
     * The raw zones of [zones] as glow targets in [out] ([YtAmbilightZones.CHANNELS] floats per
     * zone): Oklab with the chroma boosted (capped, greys stay grey) and the presence, which
     * dims the glow of a dark zone (a night scene glows little, as on a TV).
     */
    fun targets(raw: FloatArray, out: FloatArray, lab: FloatArray) {
        for (z in 0 until YtAmbilightZones.COUNT) {
            val i = z * YtAmbilightZones.RAW
            val o = z * YtAmbilightZones.CHANNELS
            oklab(raw[i], raw[i + 1], raw[i + 2], lab)
            val chroma = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            val boosted = if (chroma < 0.02f) chroma else min(chroma * CHROMA_BOOST, CHROMA_MAX)
            val scale = if (chroma > 1e-5f) boosted / chroma else 0f
            out[o] = lab[0]
            out[o + 1] = lab[1] * scale
            out[o + 2] = lab[2] * scale
            out[o + 3] = presence(raw[i + 3])
        }
    }

    /** How present the glow of a zone with this mean luminance (linear) is, 0..1. */
    fun presence(luminance: Float): Float = smoothstep(0.003f, 0.07f, luminance)

    /** The mean of the edge zones into slot [YtAmbilightZones.ALL] of a display array. */
    fun mean(values: FloatArray) {
        val all = YtAmbilightZones.ALL * YtAmbilightZones.CHANNELS
        for (c in 0 until YtAmbilightZones.CHANNELS) {
            var sum = 0f
            for (z in 0 until YtAmbilightZones.COUNT) sum += values[z * YtAmbilightZones.CHANNELS + c]
            values[all + c] = sum / YtAmbilightZones.COUNT
        }
    }

    /**
     * How different two target arrays are: the mean Oklab distance of their zones (0 equal;
     * a cut from a red to a blue scene is about 0.3).
     */
    fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (z in 0 until YtAmbilightZones.COUNT) {
            val o = z * YtAmbilightZones.CHANNELS
            val dl = a[o] - b[o]
            val da = a[o + 1] - b[o + 1]
            val db = a[o + 2] - b[o + 2]
            sum += sqrt(dl * dl + da * da + db * db)
        }
        return sum / YtAmbilightZones.COUNT
    }

    // -- Oklab ----------------------------------------------------------------------------------

    /** Linear sRGB to Oklab into [out] (L, a, b). */
    fun oklab(r: Float, g: Float, b: Float, out: FloatArray) {
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** Oklab to linear sRGB into [out] (r, g, b), not clipped. */
    fun linearOf(lightness: Float, a: Float, b: Float, out: FloatArray) {
        val l0 = lightness + 0.3963377774f * a + 0.2158037573f * b
        val m0 = lightness - 0.1055613458f * a - 0.0638541728f * b
        val s0 = lightness - 0.0894841775f * a - 1.2914855480f * b
        val l = l0 * l0 * l0
        val m = m0 * m0 * m0
        val s = s0 * s0 * s0
        out[0] = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        out[1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        out[2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
    }

    /**
     * Oklab to an opaque ARGB colour; out of gamut, the chroma is lowered until it fits (the hue
     * and lightness stay), then clipped. [scratch] takes 3 floats.
     */
    fun argb(lightness: Float, a: Float, b: Float, scratch: FloatArray): Int {
        var ca = a
        var cb = b
        repeat(8) {
            linearOf(lightness, ca, cb, scratch)
            if (scratch[0] in -0.001f..1.001f && scratch[1] in -0.001f..1.001f && scratch[2] in -0.001f..1.001f) {
                return pack(scratch)
            }
            ca *= 0.8f
            cb *= 0.8f
        }
        linearOf(lightness, ca, cb, scratch)
        return pack(scratch)
    }

    private fun pack(linear: FloatArray): Int =
        (0xFF shl 24) or (encode(linear[0]) shl 16) or (encode(linear[1]) shl 8) or encode(linear[2])

    /**
     * The colour a zone glows in on a background of this kind: luminous on a dark one (a dark
     * zone still glows in its hue, only its presence dims it), pale and a little calmer on a
     * light one, so a glow never looks like a dirty stain.
     */
    fun glow(lightness: Float, a: Float, b: Float, lightBack: Boolean, scratch: FloatArray): Int =
        if (lightBack) {
            argb((0.78f + 0.16f * lightness).coerceIn(0.80f, 0.94f), a * 0.75f, b * 0.75f, scratch)
        } else {
            argb((0.30f + 0.62f * lightness).coerceIn(0.42f, 0.84f), a, b, scratch)
        }

    // -- smoothing and cuts -----------------------------------------------------------------------

    /** The share of the way to the target covered in [dt] s with time constant [tau] s. */
    fun follow(dt: Float, tau: Float): Float =
        if (tau <= 0f) 1f else (1f - exp(-dt.coerceAtLeast(0f) / tau)).coerceIn(0f, 1f)

    /** Hermite smoothstep of [x] between [edge0] and [edge1]. */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // -- contrast --------------------------------------------------------------------------------

    /**
     * The most of [tint] that may lie over [back] (as alpha, up to [wanted]) so [text] on it
     * still reads with [min] (WCAG), or as well as it did on [back] alone if that was less. Text
     * colours are never changed; only the tint gives way.
     */
    fun maxAlpha(text: Int, back: Int, tint: Int, wanted: Float, min: Double = YtContrast.BODY): Float {
        if (wanted <= 0f) return 0f
        val floor = minOf(min, YtContrast.ratio(text, back)) - 0.005
        fun ok(alpha: Float) = YtContrast.ratio(text, YtContrast.mix(back, tint, alpha.toDouble())) >= floor
        if (ok(wanted)) return wanted
        var lo = 0f
        var hi = wanted
        repeat(9) {
            val mid = (lo + hi) / 2f
            if (ok(mid)) lo = mid else hi = mid
        }
        return lo
    }

    /** The alpha of each of [layers] equal layers that together cover [total]. */
    fun perLayer(total: Float, layers: Int): Float {
        if (layers <= 1) return total.coerceIn(0f, 1f)
        return (1f - (1f - total.coerceIn(0f, 1f)).toDouble().pow(1.0 / layers).toFloat()).coerceIn(0f, 1f)
    }

    /** True if every channel of [a] and [b] (the first [size] floats) differs by less than [eps]. */
    fun near(a: FloatArray, b: FloatArray, size: Int, eps: Float): Boolean {
        for (i in 0 until size) if (abs(a[i] - b[i]) >= eps) return false
        return true
    }
}

/**
 * The displayed glow: each channel follows its target with a time constant, the same in every
 * frame rate. Primed by the first target; its presence then rises from 0, so the glow fades in.
 */
class YtAmbilightSmoother(private val size: Int = YtAmbilightZones.DISPLAY * YtAmbilightZones.CHANNELS) {
    val value = FloatArray(size)
    var primed = false
        private set

    /** Jumps to [target] ([presenceFromZero]: presence stays where it is, to fade in). */
    fun snap(target: FloatArray, presenceFromZero: Boolean = false) {
        for (i in 0 until size) {
            if (presenceFromZero && i % YtAmbilightZones.CHANNELS == 3) continue
            value[i] = target[i]
        }
        primed = true
    }

    /** Moves towards [target] over [dt] s; returns the largest step taken. */
    fun step(target: FloatArray, dt: Float, tau: Float): Float {
        if (!primed) {
            snap(target, presenceFromZero = true)
        }
        val k = YtAmbilightMath.follow(dt, tau)
        var largest = 0f
        for (i in 0 until size) {
            val d = (target[i] - value[i]) * k
            value[i] += d
            if (abs(d) > largest) largest = abs(d)
        }
        return largest
    }

    /** Fades the presence of every zone to 0 at once (no glow) and forgets the colours. */
    fun clear() {
        value.fill(0f)
        primed = false
    }
}

/**
 * Scene cuts and how busy the picture is: a sample far from the one before is a cut (the glide
 * then runs quickly for [FAST_S]); [activity] is a smoothed distance between samples, from which
 * the sampling rate follows (YtAmbilightRules.intervalMs).
 */
class YtAmbilightScene {
    private val last = FloatArray(YtAmbilightZones.COUNT * YtAmbilightZones.CHANNELS)
    private var has = false
    private var cutAt = -1e9f

    var activity = 0f
        private set

    /** Notes the sample [targets] taken at [timeS]; true if it is a scene cut. */
    fun update(targets: FloatArray, timeS: Float): Boolean {
        if (!has) {
            targets.copyInto(last, 0, 0, last.size)
            has = true
            cutAt = timeS
            return true
        }
        val d = YtAmbilightMath.distance(last, targets)
        targets.copyInto(last, 0, 0, last.size)
        activity += (d - activity) * 0.3f
        if (d >= CUT && timeS - cutAt >= MIN_GAP_S) {
            cutAt = timeS
            return true
        }
        return false
    }

    /** The time constant of the glide at [timeS]: quick right after a cut, soft otherwise. */
    fun tau(timeS: Float): Float = if (timeS - cutAt < FAST_S) TAU_CUT else TAU

    /** A new video or source: the next sample counts as a cut. */
    fun reset() {
        has = false
        activity = 0f
    }

    companion object {
        /** Mean Oklab distance of a cut. */
        const val CUT = 0.14f

        /** Cuts closer than this count once (strobing scenes do not make the glow race). */
        const val MIN_GAP_S = 0.5f
        const val FAST_S = 0.4f
        const val TAU = 0.55f
        const val TAU_CUT = 0.12f
    }
}
