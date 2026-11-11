package com.github.libretube.ui.yt

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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

    // -- the sun's place -------------------------------------------------------------------------

    /** The sun's radius in a scene of [w] x [h] px. */
    fun sunRadius(w: Float, h: Float): Float = min(w * 0.38f, h * 0.22f)

    /** The y of the sun's centre: a third of its radius above the horizon at [horizonY]. */
    fun sunCenterY(horizonY: Float, radius: Float): Float = horizonY - radius * 0.32f

    // -- the palms ------------------------------------------------------------------------------

    /** Every palm stays within this fraction of the width from its own edge of the screen. */
    const val PALM_BAND = 0.27f

    /** No part of a palm comes closer to the sun's centre than this many sun radii. */
    const val SUN_CORE = 0.6f

    /** The tallest trunk, as a fraction of the scene's height. */
    const val PALM_TRUNK_MAX = 0.44f

    /** The widest crown (fronds from tip to tip), as a fraction of the width. */
    const val PALM_SPREAD_MAX = 0.4f

    /** How far a palm sways at most, in degrees (about its foot), and how slowly. */
    const val PALM_SWAY_DEG = 1.2f
    const val PALM_SWAY_MAX_HZ = 0.1f

    /**
     * The palms of a scene of [w] x [h] px with the horizon at [horizonY]: [perSide] on the
     * left and on the right (at most 2), the far ones first (they are drawn behind). Each stands
     * on the floor at its edge, partly cut off by it, and leans a little; the near one is
     * taller, its crown a little higher on the screen (perspective). Deterministic.
     */
    fun palms(w: Float, h: Float, horizonY: Float, perSide: Int): List<SynthwavePalmSpec> {
        val n = perSide.coerceIn(0, 2)
        if (n == 0 || w <= 0f || h <= 0f) return emptyList()
        val floor = h - horizonY
        fun near(left: Boolean, seed: Int, scale: Float): SynthwavePalmSpec {
            val footY = horizonY + floor * 0.55f
            val height = min(footY - (horizonY - h * 0.085f), h * PALM_TRUNK_MAX) * scale
            val frond = min(height * 0.42f, w * 0.2f)
            return SynthwavePalmSpec(
                footX = edgeX(-0.035f, left, w), footY = footY, height = height,
                crownX = edgeX(0.04f, left, w), frond = frond, fronds = 13, leaflets = 22,
                left = left, seed = seed,
            )
        }
        fun far(left: Boolean, seed: Int, scale: Float): SynthwavePalmSpec {
            val footY = horizonY + floor * 0.16f
            val height = min(footY - (horizonY - h * 0.035f), h * PALM_TRUNK_MAX) * scale
            val frond = min(height * 0.45f, w * 0.075f)
            return SynthwavePalmSpec(
                footX = edgeX(0.175f, left, w), footY = footY, height = height,
                crownX = edgeX(0.165f, left, w), frond = frond, fronds = 10, leaflets = 15,
                left = left, seed = seed,
            )
        }
        val nearOnes = listOf(near(true, 1, 1f), near(false, 2, 0.95f))
        return if (n == 1) nearOnes else listOf(far(true, 3, 1f), far(false, 4, 0.9f)) + nearOnes
    }

    private fun edgeX(fraction: Float, left: Boolean, w: Float) = if (left) w * fraction else w * (1f - fraction)

    /**
     * Traces palm [p] into [sink] as filled polygons, all wound the same way (so a non-zero
     * fill makes one silhouette of them): a slightly curved trunk tapering from a flared foot to
     * the crown, a knob at the crown, and [SynthwavePalmSpec.fronds] fronds, each a curved spine
     * that arches out and droops at its tip, feathered with thin leaflets on both sides (the
     * lower ones hang, the upper ones lie flatter). Coordinates are the scene's px.
     */
    fun palm(p: SynthwavePalmSpec, sink: PalmSink) {
        val poly = PalmPolygon(sink)
        val crownY = p.footY - p.height
        // the trunk: a quadratic curve, straight at the foot, bending towards the crown
        val dir = if (p.left) 1f else -1f
        val kx = p.footX + (p.crownX - p.footX) * 0.15f - dir * p.height * 0.035f
        val ky = p.footY - p.height * 0.6f
        val foot = p.height * 0.026f
        val neck = p.height * 0.012f
        val n = 16
        for (side in 0..1) {
            for (s in 0..n) {
                val t = if (side == 0) s.toFloat() / n else 1f - s.toFloat() / n
                val bx = quad(p.footX, kx, p.crownX, t)
                val by = quad(p.footY, ky, crownY, t)
                var tx = quadSlope(p.footX, kx, p.crownX, t)
                var ty = quadSlope(p.footY, ky, crownY, t)
                val len = kotlin.math.sqrt(tx * tx + ty * ty).coerceAtLeast(1e-4f)
                tx /= len
                ty /= len
                val flare = 1f + 0.5f * (1f - t) * (1f - t) * (1f - t) * (1f - t)
                val half = (foot + (neck - foot) * t) * flare * (if (side == 0) 1f else -1f)
                poly.add(bx - ty * half, by + tx * half)
            }
        }
        poly.close()
        // the knob the fronds grow from
        val knob = neck * 2.1f
        for (k in 0 until 10) {
            val a = TAU * k / 10f
            poly.add(p.crownX + kotlin.math.cos(a) * knob, crownY + sin(a) * knob * 0.8f)
        }
        poly.close()
        // the fronds, around the upper half and a little below the sides
        for (f in 0 until p.fronds) {
            // more fronds to the sides than straight up, a few hanging below the crown
            val u = 2f * (f + 0.5f) / p.fronds - 1f
            val spread = kotlin.math.sign(u) * kotlin.math.abs(u).pow(0.75f)
            val theta = kotlin.math.PI.toFloat() * (1.5f + 0.6f * spread) + (hash(p.seed * 31 + f, 8) - 0.5f) * 0.14f
            val dx = kotlin.math.cos(theta)
            val dy = sin(theta)
            val side = kotlin.math.abs(dx)
            val length = p.frond * (0.72f + 0.28f * side) * (0.92f + 0.16f * hash(p.seed * 31 + f, 9))
            val droop = length * (0.15f + 0.55f * side)
            val x1 = p.crownX + length * dx * 0.55f
            val y1 = crownY + length * dy * 0.7f
            val x2 = p.crownX + length * dx * 0.9f
            val y2 = crownY + length * dy * 0.55f + droop
            frond(poly, p.crownX, crownY, x1, y1, x2, y2, length, p.leaflets)
        }
    }

    private fun frond(poly: PalmPolygon, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, length: Float, leaflets: Int) {
        // the spine: a thin strip, tapering to the tip
        val m = 10
        for (side in 0..1) {
            for (s in 0..m) {
                val t = if (side == 0) s.toFloat() / m else 1f - s.toFloat() / m
                val bx = quad(x0, x1, x2, t)
                val by = quad(y0, y1, y2, t)
                var tx = quadSlope(x0, x1, x2, t)
                var ty = quadSlope(y0, y1, y2, t)
                val len = kotlin.math.sqrt(tx * tx + ty * ty).coerceAtLeast(1e-4f)
                tx /= len
                ty /= len
                val half = length * (0.013f * (1f - t) + 0.002f) * (if (side == 0) 1f else -1f)
                poly.add(bx - ty * half, by + tx * half)
            }
        }
        poly.close()
        // the leaflets: slim blades along the spine, longest in its middle, pointing to the tip
        val count = leaflets.coerceAtLeast(2)
        for (k in 0 until count) {
            val t = 0.1f + 0.86f * k / (count - 1)
            val bx = quad(x0, x1, x2, t)
            val by = quad(y0, y1, y2, t)
            var tx = quadSlope(x0, x1, x2, t)
            var ty = quadSlope(y0, y1, y2, t)
            val len = kotlin.math.sqrt(tx * tx + ty * ty).coerceAtLeast(1e-4f)
            tx /= len
            ty /= len
            val blade = length * 0.25f * (4f * t * (1f - t)).coerceIn(0.25f, 1f)
            val base = blade * 0.06f
            for (s in -1..1 step 2) {
                // swept towards the tip, out to the spine's side, and hanging
                var ex = tx * 0.75f - ty * s * 0.5f
                var ey = ty * 0.75f + tx * s * 0.5f + 0.6f
                val el = kotlin.math.sqrt(ex * ex + ey * ey).coerceAtLeast(1e-4f)
                ex /= el
                ey /= el
                poly.add(bx - tx * base, by - ty * base)
                poly.add(bx + tx * base, by + ty * base)
                poly.add(bx + ex * blade, by + ey * blade)
                poly.close()
            }
        }
    }

    /** The box of palm [p] (left, top, right, bottom in the scene's px), without any margin. */
    fun palmBounds(p: SynthwavePalmSpec): FloatArray {
        val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        palm(
            p,
            object : PalmSink {
                override fun moveTo(x: Float, y: Float) = lineTo(x, y)
                override fun lineTo(x: Float, y: Float) {
                    box[0] = min(box[0], x)
                    box[1] = min(box[1], y)
                    box[2] = max(box[2], x)
                    box[3] = max(box[3], y)
                }
                override fun close() = Unit
            },
        )
        return box
    }

    /**
     * How far palm [i] leans now, in degrees about its foot, at [time] seconds: a very slow,
     * gentle sway of its own rate and phase, at most [PALM_SWAY_DEG].
     */
    fun palmSway(i: Int, time: Float): Float {
        val hz = 0.055f + (PALM_SWAY_MAX_HZ - 0.055f) * hash(i, 11)
        return PALM_SWAY_DEG * sin(time * hz * TAU + hash(i, 12) * TAU)
    }

    private fun quad(a: Float, b: Float, c: Float, t: Float): Float {
        val u = 1f - t
        return u * u * a + 2f * u * t * b + t * t * c
    }

    private fun quadSlope(a: Float, b: Float, c: Float, t: Float): Float = 2f * (1f - t) * (b - a) + 2f * t * (c - b)

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
 * One palm of Synthwave's scene, in the scene's px: its foot on the floor, how tall its trunk
 * is, where its crown is (the trunk curves from the foot to it), how long its fronds are, how
 * many fronds and leaflets per frond side, on which edge it stands, and a seed for the small
 * differences between palms.
 */
data class SynthwavePalmSpec(
    val footX: Float,
    val footY: Float,
    val height: Float,
    val crownX: Float,
    val frond: Float,
    val fronds: Int,
    val leaflets: Int,
    val left: Boolean,
    val seed: Int,
)

/** Where a palm's polygons go (a Path in the scene, a box or a check in the tests). */
interface PalmSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun close()
}

/**
 * Collects one polygon at a time and hands it to the sink wound clockwise on the screen
 * (y down), so overlapping polygons fill as one shape under the non-zero rule.
 */
private class PalmPolygon(private val sink: PalmSink) {
    private var xs = FloatArray(64)
    private var ys = FloatArray(64)
    private var count = 0

    fun add(x: Float, y: Float) {
        if (count == xs.size) {
            xs = xs.copyOf(count * 2)
            ys = ys.copyOf(count * 2)
        }
        xs[count] = x
        ys[count] = y
        count++
    }

    fun close() {
        if (count >= 3) {
            var area = 0f
            for (i in 0 until count) {
                val j = (i + 1) % count
                area += xs[i] * ys[j] - xs[j] * ys[i]
            }
            val forward = area >= 0f
            for (k in 0 until count) {
                val i = if (forward) k else count - 1 - k
                if (k == 0) sink.moveTo(xs[i], ys[i]) else sink.lineTo(xs[i], ys[i])
            }
            sink.close()
        }
        count = 0
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
    /** Palms on each side (the near one; with 2 also a smaller far one). */
    val palms: Int,
    /** A soft wide stroke under every neon line. */
    val glowLines: Boolean,
    /** The sun's slits scroll, the stars twinkle and the palms sway. */
    val sky: Boolean,
    /** The sound moves the scene (else: the calm drive only). */
    val sound: Boolean,
)

object SynthwaveTiers {
    /** The most stars and ridge lines any tier draws (arrays are sized for them once). */
    const val MAX_STARS = 64
    const val MAX_RIDGES = 7

    fun of(tier: FrequencyTier): SynthwaveDetail = when (tier) {
        // everything at the display's rate
        FrequencyTier.HIGH -> SynthwaveDetail(stars = MAX_STARS, rows = 15, columns = 10, ridges = MAX_RIDGES, slits = 7, palms = 2, glowLines = true, sky = true, sound = true)
        // fewer stars and lines, at most 60 frames a second
        FrequencyTier.MID -> SynthwaveDetail(stars = 40, rows = 11, columns = 7, ridges = 5, slits = 6, palms = 1, glowLines = false, sky = true, sound = true)
        // power saver, hot or small phones: a still scene, only the floor drives (≈ 30 a second)
        FrequencyTier.LOW -> SynthwaveDetail(stars = 28, rows = 9, columns = 6, ridges = 4, slits = 6, palms = 1, glowLines = false, sky = false, sound = false)
    }

    /** The scene when nothing may move (animations off): the full picture, standing still. */
    val STILL = SynthwaveDetail(stars = 52, rows = 13, columns = 9, ridges = 6, slits = 7, palms = 2, glowLines = true, sky = false, sound = false)

    /** The moment the still scene shows: the floor and the slits between two steps, calm stars. */
    const val STILL_DRIVE = 0.4f
    const val STILL_SLITS = 0.45f
    const val STILL_CLOCK = 2.2f
}
