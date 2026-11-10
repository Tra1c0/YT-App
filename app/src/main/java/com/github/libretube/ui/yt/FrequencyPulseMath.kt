package com.github.libretube.ui.yt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/**
 * YT: how the design "Frequency" turns the sound (YtSoundFrame) into motion. Pure functions, no
 * Android, tested on the JVM (FrequencyPulseMathTest).
 *
 * Two rules keep it tasteful and safe:
 * - Every channel follows its target through [follow]: a quick attack, a slower release and a
 *   slew limit of [MAX_SLEW] per second. A full swing up and back takes at least 1/3 s, so no
 *   brightness or size changes faster than 3 times a second, whatever the sound does (no strobe).
 * - Every visual mapping is clamped to a small amplitude ([BREATH_MAX], [PULSE_LIFT_MAX], ...),
 *   so shapes breathe and light lifts, but nothing jumps and text never moves.
 * Silence (all inputs 0) maps to the rest values: the design looks finished without sound.
 */
object FrequencyPulseMath {
    /** Seconds to rise most of the way to a louder target. */
    const val ATTACK_S = 0.07f

    /** Seconds to fall most of the way back to a quieter one. */
    const val RELEASE_S = 0.28f

    /** The fastest a channel may change, in units (0..1) per second: ≤ 3 full swings a second. */
    const val MAX_SLEW = 6f

    /** A frame that took longer than this (a hitch) counts as this long. */
    const val MAX_DT = 0.1f

    /** The most a shape grows with the sound (6 %). */
    const val BREATH_MAX = 0.06f

    /** The most the nav's selected indicator brightens on a hit (a white veil of 18 %). */
    const val PULSE_LIFT_MAX = 0.18f

    /** How far a cookie's scallops deepen with the bass, on top of their rest depth. */
    const val DEPTH_MAX = 0.05f

    /** Degrees per second the shapes turn at full energy (they stand still in silence). */
    const val TURN_MAX = 28f

    /** The glow's alpha at rest and at its loudest. */
    const val GLOW_REST = 0.16f
    const val GLOW_PEAK = 0.5f

    /** The background aura's alpha at rest and at its loudest, and how far its lights drift. */
    const val AURA_REST = 0.14f
    const val AURA_PEAK = 0.24f
    const val AURA_DRIFT = 0.07f

    /** NaN and anything outside 0..1 as the nearest value in 0..1. */
    fun clamp01(x: Float): Float = if (x.isNaN()) 0f else x.coerceIn(0f, 1f)

    /**
     * One step of a channel from [current] towards [target] after [dt] seconds: exponential
     * attack/release, then the slew limit. Always in 0..1; a zero or negative [dt] keeps it.
     */
    fun follow(
        current: Float,
        target: Float,
        dt: Float,
        attack: Float = ATTACK_S,
        release: Float = RELEASE_S,
        slew: Float = MAX_SLEW,
    ): Float {
        val from = clamp01(current)
        val to = clamp01(target)
        val d = if (dt.isNaN()) 0f else dt.coerceIn(0f, MAX_DT)
        if (d <= 0f) return from
        val tau = (if (to > from) attack else release).coerceAtLeast(0.001f)
        val eased = from + (to - from) * (1f - exp(-d / tau))
        val step = slew * d
        return clamp01(eased.coerceIn(from - step, from + step))
    }

    /** What makes shapes breathe: mostly the bass, some of the overall level. */
    fun energyTarget(level: Float, bass: Float): Float = clamp01(0.6f * clamp01(bass) + 0.4f * clamp01(level))

    /** What lights the glow: the level, lifted a little so quiet music still shows. */
    fun glowTarget(level: Float): Float {
        val l = clamp01(level)
        return clamp01(l * (1.6f - 0.6f * l))
    }

    /** Scale of a breathing shape at [energy]; [amount] is capped at [BREATH_MAX]. */
    fun breathScale(energy: Float, amount: Float = BREATH_MAX): Float =
        1f + (if (amount.isNaN()) 0f else amount.coerceIn(0f, BREATH_MAX)) * clamp01(energy)

    /** Depth of a cookie's scallops: [rest] in silence, deeper with [energy]. */
    fun cookieDepth(rest: Float, energy: Float): Float =
        (if (rest.isNaN()) 0f else rest.coerceIn(0f, 0.2f)) + DEPTH_MAX * clamp01(energy)

    /** Alpha of a glow at [glow] (0 = rest). */
    fun glowAlpha(glow: Float, rest: Float = GLOW_REST, peak: Float = GLOW_PEAK): Float =
        rest + (peak - rest) * clamp01(glow)

    /** The white veil over the nav's selected indicator at [punch] (rate-safe input). */
    fun pulseLift(punch: Float): Float = PULSE_LIFT_MAX * clamp01(punch)

    /** Alpha of a shimmer band at [high]: nothing in silence, at most 0.35. */
    fun shimmerAlpha(high: Float): Float = 0.35f * clamp01(high)

    /** Alpha of the background aura at [level]. */
    fun auraAlpha(level: Float): Float = AURA_REST + (AURA_PEAK - AURA_REST) * clamp01(level)

    /** Horizontal drift of the aura's lights (fraction of the width): mids pull, highs push. */
    fun auraDx(mid: Float, high: Float): Float = AURA_DRIFT * (clamp01(mid) - clamp01(high))

    /** Vertical drift (fraction of the height): the bass lifts the lights. */
    fun auraDy(bass: Float): Float = -AURA_DRIFT * clamp01(bass)

    /** The aura's lights grow a little with the bass. */
    fun auraGrow(bass: Float): Float = 1f + 0.12f * clamp01(bass)

    /** Degrees the shapes turn in [dt] seconds at [energy]; nothing in silence. */
    fun turnStep(energy: Float, dt: Float): Float {
        val d = if (dt.isNaN()) 0f else dt.coerceIn(0f, MAX_DT)
        return TURN_MAX * clamp01(energy) * d
    }

    /** Position of the shimmer band along an element, 0..1, from the running turn (degrees). */
    fun shimmerAt(turn: Float): Float {
        val t = (turn / 90f) % 1f
        return if (t < 0f) t + 1f else t
    }

    /**
     * The radius of a scalloped circle ("cookie") of [lobes] at [angle] (radians), as a fraction
     * of its outer radius: 1 at the tips, 1 - [depth] in the valleys, turned by [turn] radians.
     */
    fun cookieRadius(angle: Float, lobes: Int, depth: Float, turn: Float = 0f): Float {
        val wave = 0.5f - 0.5f * cos(lobes * (angle - turn).toDouble()).toFloat()
        val d = if (depth.isNaN()) 0f else depth.coerceIn(0f, 0.5f)
        return 1f - d * wave
    }

    /** Whether all channels are at rest (nothing to draw differently from silence). */
    fun atRest(vararg channels: Float): Boolean = channels.all { it < REST_EPSILON }

    /** Below this a channel counts as rest. */
    const val REST_EPSILON = 0.002f

    /** 2π, for the cookie maths. */
    const val TAU = (2 * PI).toFloat()
}
