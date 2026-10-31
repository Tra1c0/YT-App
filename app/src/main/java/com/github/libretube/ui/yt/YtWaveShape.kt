package com.github.libretube.ui.yt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * YT: the shape of the wave at the player's lower edge, from the [BANDS] levels of the sound
 * (pure Kotlin, so it can be checked on the JVM).
 *
 * The ribbon is mirrored: the bass sits in the middle, higher sounds lie further out, the
 * treble near the pointed tips at the player's lower corners. Its depth below the edge at each
 * place follows the level of the band that lies there, so a voice swells the inner part and a
 * hiss the outer parts. Nothing moves by itself: the same sound always gives the same shape.
 *
 * Units: positions u from 0 (left tip) to 1 (right tip); depths in [unit], today's wave height.
 */
object YtWaveShape {
    const val BANDS = 16

    /** Where band 0 and band 15 lie, from the middle (0) to a tip (1). */
    private const val FIRST = 0.03f
    private const val LAST = 0.85f

    /** Depth in silence and at full level, in units: twice the earlier wave's. */
    const val QUIET = 0.132f
    const val FULL = 0.534f

    /** How much the braided lines swing across the ribbon, and how often along it. */
    private const val BRAID = 0.15f
    private const val CYCLES = 2.5f

    private val positions = FloatArray(BANDS) { FIRST + (LAST - FIRST) * it / (BANDS - 1) }

    /** Slopes for [level], from [levels]; [out] has [BANDS] places. */
    fun slopes(levels: FloatArray, out: FloatArray) {
        out.fill(0f)
        for (j in 1 until BANDS - 1) {
            val before = (levels[j] - levels[j - 1]) / (positions[j] - positions[j - 1])
            val after = (levels[j + 1] - levels[j]) / (positions[j + 1] - positions[j])
            // a monotone curve (Fritsch-Butland): flat where the levels turn, else the harmonic
            // mean of both sides, so it never swings beyond the levels it passes through
            out[j] = if (before * after > 0f) 2f * before * after / (before + after) else 0f
        }
    }

    /**
     * The level at [s] (0 in the middle, 1 at a tip), a smooth curve through the band levels;
     * beyond the last band it falls to 0 at the tip.
     */
    fun level(s: Float, levels: FloatArray, slopes: FloatArray): Float {
        if (s <= positions[0]) return levels[0].coerceIn(0f, 1f)
        if (s >= positions[BANDS - 1]) {
            return (levels[BANDS - 1] * ((1f - s) / (1f - LAST)).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        }
        var j = ((s - FIRST) / (LAST - FIRST) * (BANDS - 1)).toInt().coerceIn(0, BANDS - 2)
        while (j < BANDS - 2 && s >= positions[j + 1]) j++
        while (j > 0 && s < positions[j]) j--
        val h = positions[j + 1] - positions[j]
        val t = (s - positions[j]) / h
        val t2 = t * t
        val t3 = t2 * t
        val value = (2 * t3 - 3 * t2 + 1) * levels[j] +
            (t3 - 2 * t2 + t) * h * slopes[j] +
            (-2 * t3 + 3 * t2) * levels[j + 1] +
            (t3 - t2) * h * slopes[j + 1]
        return value.coerceIn(0f, 1f)
    }

    /** The ribbon's depth at [u], in units: pointed at both tips, deeper where it is loud. */
    fun depth(u: Float, levels: FloatArray, slopes: FloatArray): Float {
        val s = abs(2f * u - 1f).coerceIn(0f, 1f)
        val taper = cos(PI.toFloat() * s / 2f).coerceAtLeast(0f)
        return QUIET * taper + (FULL - QUIET) * level(s, levels, slopes) * sqrt(taper)
    }

    /**
     * Where line [c] (0 at the player's edge to 1 at the ribbon's outer side) runs at [u], as a
     * share of the depth there: the lines braid a little, always inside the ribbon.
     */
    fun line(c: Float, u: Float): Float =
        c + BRAID * 4f * c * (1f - c) * sin(2f * PI.toFloat() * (CYCLES * u + c))
}
