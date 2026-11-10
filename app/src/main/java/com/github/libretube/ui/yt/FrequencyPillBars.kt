package com.github.libretube.ui.yt

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin

/**
 * YT: the equalizer on Frequency's subscribe pill, in the style of Deezer's logo: a row of
 * rounded bars side by side, standing on the pill's bottom edge, each one band of the spectrum
 * (low frequencies at the start side). Pure maths here (tested on the JVM, FrequencyPillBarsTest);
 * [frequencyPillBars] draws it.
 *
 * - Heights only move (a motion channel: FrequencyPulse.bands plus a little of the bounce
 *   spring, staggered from bar to bar); the colour and alpha never change, so nothing flashes.
 * - In silence, paused, with animations off: a calm staggered arch ([rest]), never flat.
 * - The bars are the label's ink at [safeAlpha]: the label still reads 4.5:1 where it stands on
 *   a bar at full height.
 */
object FrequencyPillBars {
    /** The width one bar takes with its gap (dp), at [BAR_SHARE] bar and the rest gap: ~3 dp + 2 dp. */
    const val SLOT_DP = 5f

    /** The bar's share of its slot. */
    const val BAR_SHARE = 0.6f

    /** The widest a bar gets when few bars share a wide pill (dp). */
    const val MAX_BAR_DP = 5f

    /** Fewest and most bars (the tier's own limit, FrequencyTier.bars, can be lower). */
    const val MIN_BARS = 8
    const val MAX_BARS = 16

    /** The tallest bar, as a share of the pill's height. */
    const val HEIGHT_SHARE = 0.75f

    /** The space left and right of the bars, as a share of the pill's height (the round ends). */
    const val INSET_SHARE = 0.3f

    /** The spring's share of a bar's drive (the rest is its band). */
    const val SPRING_SHARE = 0.2f

    /** The deepest a bar dips below its rest when the spring overshoots (share of its rest). */
    const val MAX_DIP = 0.3f

    /** Rest heights: the lowest and highest a bar rests at (share of the full height). */
    const val REST_MIN = 0.18f
    const val REST_MAX = 0.72f

    /** The most the bars cover the pill (more would look heavy, even where the contrast allows it). */
    const val ALPHA_CAP = 0.3f

    /** The bounce index of the last bar: the spring ripples from the low bars to the high ones. */
    const val SPRING_SPREAD = 6

    /**
     * How many bars fit in [innerPx] at one bar per [slotPx], within [MIN_BARS]..[tierMax] (the
     * tier's limit, itself at most [MAX_BARS]); 0 when nothing fits.
     */
    fun count(innerPx: Float, slotPx: Float, tierMax: Int): Int {
        if (innerPx.isNaN() || slotPx.isNaN() || innerPx <= 0f || slotPx <= 0f) return 0
        val most = tierMax.coerceIn(1, MAX_BARS)
        val fit = (innerPx / slotPx).toInt()
        return fit.coerceAtMost(most).coerceAtLeast(min(MIN_BARS, most))
    }

    /**
     * The rest height of bar [i] of [count] (share of the full height): an arch, higher in the
     * middle, with each bar a little off its neighbours, as the staggered bars of the logo.
     */
    fun rest(i: Int, count: Int): Float {
        if (count <= 0) return REST_MIN
        val k = i.coerceIn(0, count - 1)
        val arch = sin(PI.toFloat() * (k + 0.5f) / count)
        val stagger = sin(k * 2.1f + 0.4f)
        return (0.2f + 0.42f * arch + 0.11f * stagger).coerceIn(REST_MIN, REST_MAX)
    }

    /**
     * The height of a bar (share of the full height) resting at [rest], with its band's [level]
     * (FrequencyMotionMath.barLevel), the spring's [bounce] (may overshoot below 0) and the
     * tier's [amplitude]. Exactly [rest] in silence; never above 1, never below
     * rest × (1 − [MAX_DIP]).
     */
    fun height(rest: Float, level: Float, bounce: Float, amplitude: Float): Float {
        val r = if (rest.isNaN()) REST_MIN else rest.coerceIn(0f, 1f)
        val l = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        val b = if (bounce.isNaN()) 0f else bounce.coerceIn(-FrequencySpring.MAX_DOWN, FrequencySpring.MAX_UP)
        val a = if (amplitude.isNaN()) 0f else amplitude.coerceIn(0f, 1f)
        val drive = (a * ((1f - SPRING_SHARE) * l + SPRING_SHARE * b)).coerceIn(-MAX_DIP, 1f)
        return if (drive >= 0f) r + (1f - r) * drive else r * (1f + drive)
    }

    /** The spring index (FrequencyPulse.bounce) of bar [i] of [count]: a ripple from low to high. */
    fun springIndex(i: Int, count: Int): Int =
        if (count <= 1) 0 else (i.coerceIn(0, count - 1) * SPRING_SPREAD) / (count - 1)

    /**
     * Turns the first [count] entries of [out] from each bar's bounce into its height: bar i
     * takes band share i of [bands] (low to high). In place, no allocation.
     */
    fun heights(out: FloatArray, count: Int, bands: FloatArray, amplitude: Float) {
        val n = min(count, out.size)
        for (i in 0 until n) {
            out[i] = height(rest(i, n), FrequencyMotionMath.barLevel(bands, i, n), out[i], amplitude)
        }
    }

    /** The bar drawn at position [position] (from the left) of [count]: mirrored right to left. */
    fun barAt(position: Int, count: Int, rtl: Boolean): Int = if (rtl) count - 1 - position else position

    /**
     * The highest alpha (in steps of 0.01, at most [cap]) at which [ink] drawn over each of
     * [fills] keeps [ink] itself (the label) readable with [min]:1 there. 0 when even the bare
     * fills do not reach it. Conservative: the alpha is rounded up to a byte as drawn.
     */
    fun safeAlpha(ink: Int, fills: IntArray, min: Double = YtContrast.BODY, cap: Float = ALPHA_CAP): Float {
        var best = 0f
        var step = 0
        while (true) {
            val a = step / 100f
            if (a > cap + 1e-6f) break
            if (fills.all { YtContrast.ratio(ink, under(ink, a, it)) >= min }) best = a else break
            step++
        }
        return best
    }

    /** The colour of [fill] with [ink] over it at [alpha] (the alpha byte rounded up), opaque. */
    fun under(ink: Int, alpha: Float, fill: Int): Int {
        val byte = ceil(alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return YtContrast.over((ink and 0x00FFFFFF) or (byte shl 24), fill)
    }
}

/**
 * Draws the pill's equalizer ([FrequencyPillBars]) in [color] behind the content: put it right
 * after the pill's fill, inside its clip. Reads the pulse only while drawing; the heights live in
 * one array made once per size, so a frame allocates nothing and nothing recomposes.
 */
fun Modifier.frequencyPillBars(color: Color): Modifier = drawWithCache {
    val heights = FloatArray(FrequencyPillBars.MAX_BARS)
    val inset = size.height * FrequencyPillBars.INSET_SHARE
    val inner = size.width - 2f * inset
    val slotPx = FrequencyPillBars.SLOT_DP.dp.toPx()
    val maxBar = FrequencyPillBars.MAX_BAR_DP.dp.toPx()
    val full = size.height * FrequencyPillBars.HEIGHT_SHARE
    onDrawBehind {
        FrequencyPulse.tick.intValue
        val tier = FrequencyPulse.tier
        val n = FrequencyPillBars.count(inner, slotPx, tier.bars)
        if (n <= 0) return@onDrawBehind
        val slot = inner / n
        val barW = min(slot * FrequencyPillBars.BAR_SHARE, maxBar)
        val radius = CornerRadius(barW / 2f)
        for (i in 0 until n) heights[i] = FrequencyPulse.bounce(FrequencyPillBars.springIndex(i, n))
        FrequencyPillBars.heights(heights, n, FrequencyPulse.bands, tier.amplitude)
        val rtl = layoutDirection == LayoutDirection.Rtl
        val bottom = size.height
        for (p in 0 until n) {
            val h = full * heights[FrequencyPillBars.barAt(p, n, rtl)]
            val x = inset + slot * p + (slot - barW) / 2f
            // the bottom cap reaches below the edge: the pill's clip cuts it flat
            drawRoundRect(color, Offset(x, bottom - h), Size(barW, h + barW), radius)
        }
    }
}

/**
 * The alpha of the subscribe pill's bars: safe for the label (the ink) on the coral, the violet
 * and the colours the pill passes through between them.
 */
fun frequencyPillBarAlpha(palette: YtPalette): Float =
    FrequencyPillBars.safeAlpha(palette.ink.toArgb(), frequencyPillFills(palette))

/** The pill's fills: coral (not subscribed), violet (subscribed) and three steps between. */
fun frequencyPillFills(palette: YtPalette): IntArray {
    val coral = palette.accent.toArgb()
    val violet = palette.lavender.toArgb()
    return intArrayOf(coral, YtContrast.mix(coral, violet, 0.25), YtContrast.mix(coral, violet, 0.5), YtContrast.mix(coral, violet, 0.75), violet)
}
