package com.github.libretube.ui.yt

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * YT: the pure maths of the design "Synthwave"'s scene (SynthwaveScene): the perspective of the
 * neon floor, the sun's slits, the wireframe ridges, the stars, how the sound moves the scene
 * and how much of it a phone gets. No Android, tested on the JVM (SynthwaveMathTest).
 *
 * Coordinates are fractions: of the floor between the horizon (0) and the bottom (1), of the
 * sun's radius around its centre (-1 top .. 1 bottom), of the width (0..1).
 */
object SynthwaveMath {
    const val TAU = 6.2831855f

    // -- the neon floor -------------------------------------------------------------------------

    /** Depth between two rows of the floor, in units of the nearest row's depth. */
    const val ROW_GAP = 0.55f

    /**
     * Where row [k] of the floor sits between the horizon (0) and the bottom (1) when the floor
     * has driven [phase] (0..1) of a row towards the viewer. Perspective: the screen position is
     * 1 / depth, row k is at depth 1 + (k - phase) * [ROW_GAP]. At phase 1 every row is where
     * the row before it was at phase 0, so the drive loops without a seam. Above 1: the row has
     * passed below the screen.
     */
    fun rowAt(k: Int, phase: Float): Float {
        val depth = 1f + (k - phase) * ROW_GAP
        return if (depth <= 0.01f) Float.MAX_VALUE else 1f / depth
    }

    /** How bright a row is at [at] (0 horizon .. 1 bottom): it fades into the haze at the horizon. */
    fun rowAlpha(at: Float): Float = smooth(0.02f, 0.35f, at)

    /**
     * The x of the floor's line [j] (0 = centre, negative left) at [at] between the horizon (0)
     * and the bottom (1): all lines run to the vanishing point on the horizon. [spacing] is
     * their distance at the bottom.
     */
    fun columnX(j: Int, at: Float, centerX: Float, spacing: Float): Float = centerX + j * spacing * at

    /** The lowest row drawn: rows closer to the horizon than this melt into one glow. */
    const val ROW_MIN = 0.045f

    // -- the sun --------------------------------------------------------------------------------

    /** Where the slits begin, in sun radii from its centre (a little above the middle). */
    const val SLIT_START = -0.08f

    /** The thickest slit, at the sun's bottom edge, in sun radii. */
    const val SLIT_MAX = 0.13f

    /**
     * The top of slit [i] of [count] (in sun radii from the centre, [SLIT_START] .. 1) when the
     * slits have scrolled [phase] (0..1) of one slit downwards. At phase 1 slit i is where slit
     * i+1 was, so the scroll loops without a seam.
     */
    fun slitTop(i: Int, count: Int, phase: Float): Float =
        SLIT_START + (1f - SLIT_START) * slitPosition(i, count, phase)

    /**
     * The height of slit [i] (in sun radii): it grows from nothing where it appears to [SLIT_MAX]
     * where it leaves the sun at the bottom (so it neither pops in nor out).
     */
    fun slitHeight(i: Int, count: Int, phase: Float): Float = SLIT_MAX * slitPosition(i, count, phase)

    /** 0 (appears) .. 1 (leaves at the bottom). */
    fun slitPosition(i: Int, count: Int, phase: Float): Float {
        val n = count.coerceAtLeast(1)
        val p = (i + frac(phase)) / n
        return frac(p)
    }

    // -- the ridges -----------------------------------------------------------------------------

    /**
     * The height (0..1) of the mountains at [x] (0..1 across the width) for ridge line [line] of
     * [lines] (0 = the farthest): a large peak a little right of the middle, two smaller ones at
     * the sides and fine wrinkles; the nearer lines are lower and shifted, so drawn back to front
     * they look like the contour lines of a wireframe range. Deterministic.
     */
    fun ridge(x: Float, line: Int, lines: Int): Float {
        val n = lines.coerceAtLeast(1)
        val near = line.toFloat() / n
        val shift = 0.035f * line
        val s = x + shift
        var h = 0.95f * peak(s, 0.6f, 0.15f) +
            0.55f * peak(s, 0.2f, 0.11f) +
            0.45f * peak(s, 0.88f, 0.1f) +
            0.22f * peak(s, 0.4f, 0.08f) +
            0.05f * sin(s * 37f + line * 1.7f) +
            0.035f * sin(s * 71f + line * 2.9f)
        h = 0.06f + 0.94f * h
        // the nearer lines lie lower: the range rises towards the back
        return clamp01(h * (1f - 0.62f * near))
    }

    private fun peak(x: Float, center: Float, width: Float): Float {
        val d = (x - center) / width
        return exp(-d * d)
    }

    // -- the stars ------------------------------------------------------------------------------

    /** A stable pseudo-random number in 0..1 for star [i] and its property [salt]. */
    fun hash(i: Int, salt: Int): Float {
        var h = i * 374761393 + salt * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    /** The slowest and fastest twinkle, in cycles a second (well below 3 Hz). */
    const val TWINKLE_MIN_HZ = 0.12f
    const val TWINKLE_MAX_HZ = 0.55f

    /** The twinkle rate of star [i], in cycles a second. */
    fun twinkleHz(i: Int): Float = TWINKLE_MIN_HZ + (TWINKLE_MAX_HZ - TWINKLE_MIN_HZ) * hash(i, 3)

    /**
     * The light of star [i] (0..1) at [time] seconds: its own brightness, dimmed by up to [depth]
     * on a slow sine of its own rate and phase.
     */
    fun starLight(i: Int, time: Float, depth: Float): Float {
        val base = 0.45f + 0.55f * hash(i, 2)
        val wave = 0.5f + 0.5f * sin(time * twinkleHz(i) * TAU + hash(i, 4) * TAU)
        return clamp01(base * (1f - clamp01(depth) * wave))
    }

    // -- the sound ------------------------------------------------------------------------------

    /** Rows a second the floor drives in silence, and how much faster the bass makes it. */
    const val DRIVE_CALM = 0.55f
    const val DRIVE_BASS = 0.75f

    /** Rows a second at [bass] (a motion channel, 0..1). */
    fun driveSpeed(bass: Float): Float = DRIVE_CALM + DRIVE_BASS * clamp01(bass)

    /** Slits a second the sun scrolls (always calm, not moved by the sound). */
    const val SLIT_SPEED = 0.11f

    /** The alpha of the sun's halo in silence and at its loudest. */
    const val GLOW_REST = 0.5f
    const val GLOW_PEAK = 0.72f

    /** The halo's alpha at [glow] (the guarded level, 0..1): a gentle pulse of light. */
    fun sunGlow(glow: Float): Float = GLOW_REST + (GLOW_PEAK - GLOW_REST) * clamp01(glow)

    /** How far a ridge line stretches up at most with its band (7 %). */
    const val SWAY_MAX = 0.07f

    /** The stretch of a ridge line at [band] (a motion channel, 0..1). */
    fun ridgeSway(band: Float): Float = SWAY_MAX * clamp01(band)

    /** The band (of [bands]) that sways ridge line [line] of [lines]: the far lines take the bass. */
    fun ridgeBand(line: Int, lines: Int, bands: Int): Int {
        val n = lines.coerceAtLeast(1)
        return ((line.toFloat() / n) * (bands - 1) * 0.7f).toInt().coerceIn(0, bands - 1)
    }

    /** How deep the stars twinkle in silence and with the highs (guarded, 0..1). */
    const val TWINKLE_REST = 0.3f
    const val TWINKLE_HIGH = 0.65f

    fun twinkleDepth(high: Float): Float = TWINKLE_REST + (TWINKLE_HIGH - TWINKLE_REST) * clamp01(high)

    // -- the mini player's landing ----------------------------------------------------------------

    /** How long the landing on the bar takes. */
    const val LAND_MS = 560L

    /**
     * The landing of the mini player at [t] (0..1), as a car's suspension takes a bump: down
     * (positive) at once, back up a little past rest, settling; 0 at both ends, at most 1.
     */
    fun bump(t: Float): Float {
        val x = clamp01(t)
        return sin(2.4f * kotlin.math.PI.toFloat() * x) * exp(-3.6f * x) * (1f - x) * 1.25f
    }

    /** Where the neon light runs along the card's edge after the landing (0..1, -1: none). */
    fun edgeSweep(t: Float): Float = if (t <= 0f || t >= 1f) -1f else smooth(0f, 1f, t)

    // -- parallax -------------------------------------------------------------------------------

    /** The most the scene moves up as the list scrolls, in px, at a depth [factor] of 0..1. */
    fun parallax(scrollPx: Float, factor: Float, maxPx: Float): Float =
        -min(max(scrollPx, 0f) * factor, maxPx)

    /**
     * How far a lazy list has scrolled, roughly: the items above the first visible one at
     * [itemPx] each, plus the first one's offset. Enough for a slight parallax.
     */
    fun scrolled(firstIndex: Int, firstOffsetPx: Int, itemPx: Float): Float =
        firstIndex.coerceAtLeast(0) * itemPx + firstOffsetPx.coerceAtLeast(0)

    // -- helpers --------------------------------------------------------------------------------

    fun clamp01(x: Float): Float = if (x.isNaN()) 0f else x.coerceIn(0f, 1f)

    fun frac(x: Float): Float {
        val f = x - kotlin.math.floor(x)
        return if (f >= 1f) 0f else f
    }

    fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp01((x - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }
}

/**
 * How much of the scene a phone gets ([SynthwaveTiers.of], from FrequencyTiers' tier): the
 * number of stars, rows, lines, ridge lines and slits, and which parts move.
 */
data class SynthwaveDetail(
    val stars: Int,
    val rows: Int,
    val columns: Int,
    val ridges: Int,
    val slits: Int,
    /** A soft wide stroke under every neon line. */
    val glowLines: Boolean,
    /** The sun's slits scroll and the stars twinkle. */
    val sky: Boolean,
    /** The sound moves the scene (else: the calm drive only). */
    val sound: Boolean,
)

object SynthwaveTiers {
    /** The most stars and ridge lines any tier draws (arrays are sized for them once). */
    const val MAX_STARS = 96
    const val MAX_RIDGES = 9

    fun of(tier: FrequencyTier): SynthwaveDetail = when (tier) {
        // everything at the display's rate
        FrequencyTier.HIGH -> SynthwaveDetail(stars = MAX_STARS, rows = 15, columns = 10, ridges = MAX_RIDGES, slits = 7, glowLines = true, sky = true, sound = true)
        // fewer stars and lines, at most 60 frames a second
        FrequencyTier.MID -> SynthwaveDetail(stars = 52, rows = 11, columns = 7, ridges = 6, slits = 6, glowLines = false, sky = true, sound = true)
        // power saver, hot or small phones: a still scene, only the floor drives (≈ 30 a second)
        FrequencyTier.LOW -> SynthwaveDetail(stars = 34, rows = 9, columns = 6, ridges = 5, slits = 6, glowLines = false, sky = false, sound = false)
    }

    /** The scene when nothing may move (animations off): the full picture, standing still. */
    val STILL = SynthwaveDetail(stars = 72, rows = 13, columns = 9, ridges = 7, slits = 7, glowLines = true, sky = false, sound = false)

    /** The moment the still scene shows: the floor and the slits between two steps, calm stars. */
    const val STILL_DRIVE = 0.4f
    const val STILL_SLITS = 0.45f
    const val STILL_CLOCK = 2.2f
}
