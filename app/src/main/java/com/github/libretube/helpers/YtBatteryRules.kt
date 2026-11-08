package com.github.libretube.helpers

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * YT: what a low battery does to playback. Pure, so it can be tested without a device:
 * when the video quality is capped ([YtBatteryCapRules]), which battery marks announce
 * themselves ([YtBatteryMarks]), and how each mark looks and feels ([YtBatteryCues]).
 */
object YtBatteryCapRules {
    /** The cap is lifted only this many percent above the threshold (no flapping at 19/20 %). */
    const val HYSTERESIS = 5

    /** The frame rate the cap allows. */
    const val CAPPED_FPS = 30

    /** Defaults of the settings. */
    const val DEFAULT_THRESHOLD = 20
    const val DEFAULT_CAP = 480

    /**
     * Whether the cap applies now. [level] is the battery in percent (negative = unknown),
     * [was] the answer before; from the threshold up to below threshold + [HYSTERESIS] it stays.
     * The power saver (with [usePowerSave]) caps whatever the level, on the charger too.
     */
    fun active(
        was: Boolean,
        level: Int,
        charging: Boolean,
        powerSave: Boolean,
        enabled: Boolean,
        threshold: Int,
        usePowerSave: Boolean,
    ): Boolean {
        if (!enabled) return false
        if (usePowerSave && powerSave) return true
        if (charging || level < 0) return false
        if (level < threshold) return true
        if (level >= threshold + HYSTERESIS) return false
        return was
    }

    /**
     * The video height to ask the track selector for. [requested] is what the page asked
     * (Int.MAX_VALUE = auto; for shorts already widened by 16/9, as the page does), [cap] the
     * setting's height. [exempt]: the request is the user's pick by hand and passes uncapped.
     */
    fun height(requested: Int, cap: Int, active: Boolean, exempt: Boolean, short: Boolean): Int {
        if (!active || exempt) return requested
        return minOf(requested, capHeight(cap, short))
    }

    /** The cap's height; for shorts (portrait, the height is the long side) widened by 16/9. */
    fun capHeight(cap: Int, short: Boolean): Int = if (short) ceil(cap * 16.0 / 9.0).toInt() else cap

    /** The highest frame rate to allow (Int.MAX_VALUE = any). */
    fun maxFrameRate(active: Boolean, exempt: Boolean): Int =
        if (active && !exempt) CAPPED_FPS else Int.MAX_VALUE

    /** A manual pick counts only for a real resolution; "Auto" by hand is no fixed choice. */
    fun isManualPick(byUser: Boolean, resolution: Int): Boolean = byUser && resolution != Int.MAX_VALUE

    /**
     * The height the user picked by hand (null = none) after a request of [requested]. A pick
     * by hand replaces it ("Auto" by hand clears it); a request not made by hand (a rotation
     * re-sends a stored resolution) leaves it, but only a request of exactly that height is
     * exempt from the cap ([exempt]): the page may store the pick for the other orientation and
     * send its default here, which must not pass uncapped.
     */
    fun pickAfter(picked: Int?, requested: Int, byUser: Boolean): Int? = when {
        !byUser -> picked
        isManualPick(true, requested) -> requested
        else -> null
    }

    /** Whether [requested] is the pick by hand ([picked]) and so passes uncapped. */
    fun exempt(picked: Int?, requested: Int): Boolean = picked != null && picked == requested

    /**
     * The Shorts view's own player ([com.github.libretube.ui.yt.shorts.YtShortsPlayer]) has no
     * resolution request: the cap is the only limit, applied to both sides (a short may be
     * portrait or landscape), so it is the long side of a 16:9 picture of [cap]'s height.
     */
    fun shortsMaxSide(cap: Int, active: Boolean): Int = if (active) capHeight(cap, short = true) else Int.MAX_VALUE

    fun parsePercent(value: String?, fallback: Int): Int = value?.toIntOrNull()?.takeIf { it in 1..99 } ?: fallback

    fun parseHeight(value: String?, fallback: Int): Int = value?.toIntOrNull()?.takeIf { it in 144..4320 } ?: fallback
}

/**
 * YT: the battery marks (20, 15, 10, 5 %) announce themselves once each time the battery
 * falls to them. A mark comes back when the battery rises [RESET] percent above it again,
 * and all marks come back while charging. The first reading after a pause (the page was not
 * visible, the charger was just pulled) only takes note: a mark passed meanwhile stays quiet.
 */
class YtBatteryMarks(private val marks: IntArray = YtBatteryCues.MARKS) {
    private val fired = BooleanArray(marks.size)
    private var synced = false

    /**
     * A new reading. [announce] false = take note only (first reading of a registration, the
     * page in picture-in-picture). @return the mark to show now, or null.
     */
    fun observe(level: Int, charging: Boolean, announce: Boolean): Int? {
        if (level < 0) return null
        if (charging) {
            fired.fill(false)
            synced = false
            return null
        }
        for (i in marks.indices) {
            if (fired[i] && level >= marks[i] + RESET) fired[i] = false
        }
        var reached: Int? = null
        for (i in marks.indices) {
            if (!fired[i] && level <= marks[i]) {
                fired[i] = true
                reached = if (reached == null) marks[i] else minOf(reached, marks[i])
            }
        }
        val wasSynced = synced
        synced = true
        return if (wasSynced && announce) reached else null
    }

    /** Forget the last reading: the next one only takes note. */
    fun pause() {
        synced = false
    }

    companion object {
        const val RESET = 3
    }
}

/** YT: one battery mark's cue: its colours and its vibration. */
class YtBatteryCue(
    val mark: Int,
    /** The colour the frame starts in (the level before) and the one it fades to. */
    val startColor: Int,
    val endColor: Int,
    /** Waveform with amplitudes: segment lengths (ms) and amplitudes (0 to 255) of the same count. */
    val timings: LongArray,
    val amplitudes: IntArray,
    /** Without amplitude control: off/on lengths (ms), starting with an off. */
    val plainTimings: LongArray,
)

/**
 * YT: the colours and vibrations of the battery cue, the same in every design. The colours
 * live here and only here: change a constant to change the cue.
 */
object YtBatteryCues {
    val MARKS = intArrayOf(20, 15, 10, 5)

    // The colour ramp of the cue. Each cue starts in the colour of the level above and fades to its own.
    const val COLOR_FULL = 0xFF34C759.toInt() // green, a full battery (the start of the 20 % cue)
    const val COLOR_20 = 0xFFA8D52B.toInt() // yellow-green
    const val COLOR_15 = 0xFFFFD60A.toInt() // yellow
    const val COLOR_10 = 0xFFFF9500.toInt() // orange
    const val COLOR_5 = 0xFFFF3B30.toInt() // red

    // The single pulse of 20 % (longer than any touch feedback); 15 % repeats it.
    const val PULSE_MS = 140L
    const val PULSE_GAP_MS = 140L
    const val PULSE_AMP = 200
    const val SHORT_MS = 60L
    const val SHORT_GAP_MS = 100L
    const val SHORT_AMP = 170
    const val LONG_MS = 320L
    const val LONG_AMP = 230

    /** 5 %: a smooth swell, 12 steps of 100 ms rising and falling. */
    const val SWELL_STEP_MS = 100L
    val SWELL_AMPS = intArrayOf(40, 70, 110, 160, 210, 255, 255, 210, 160, 110, 70, 40)

    /** 5 % without amplitude control: pulses whose gaps shrink, then grow (slow, fast, slow). */
    val SWELL_PLAIN = longArrayOf(0, 40, 200, 50, 140, 60, 80, 80, 80, 60, 140, 50, 200, 40)

    private val cues = listOf(
        YtBatteryCue(
            mark = 20,
            startColor = COLOR_FULL,
            endColor = COLOR_20,
            timings = longArrayOf(PULSE_MS),
            amplitudes = intArrayOf(PULSE_AMP),
            plainTimings = longArrayOf(0, PULSE_MS),
        ),
        YtBatteryCue(
            mark = 15,
            startColor = COLOR_20,
            endColor = COLOR_15,
            timings = longArrayOf(PULSE_MS, PULSE_GAP_MS, PULSE_MS),
            amplitudes = intArrayOf(PULSE_AMP, 0, PULSE_AMP),
            plainTimings = longArrayOf(0, PULSE_MS, PULSE_GAP_MS, PULSE_MS),
        ),
        YtBatteryCue(
            mark = 10,
            startColor = COLOR_15,
            endColor = COLOR_10,
            timings = longArrayOf(SHORT_MS, SHORT_GAP_MS, SHORT_MS, SHORT_GAP_MS, LONG_MS),
            amplitudes = intArrayOf(SHORT_AMP, 0, SHORT_AMP, 0, LONG_AMP),
            plainTimings = longArrayOf(0, SHORT_MS, SHORT_GAP_MS, SHORT_MS, SHORT_GAP_MS, LONG_MS),
        ),
        YtBatteryCue(
            mark = 5,
            startColor = COLOR_10,
            endColor = COLOR_5,
            timings = LongArray(SWELL_AMPS.size) { SWELL_STEP_MS },
            amplitudes = SWELL_AMPS,
            plainTimings = SWELL_PLAIN,
        ),
    )

    fun of(mark: Int): YtBatteryCue? = cues.firstOrNull { it.mark == mark }

    // The visual: in, hold, out (ms); the colour fades during in + hold.
    const val IN_MS = 250L
    const val HOLD_MS = 600L
    const val OUT_MS = 300L
    const val TOTAL_MS = IN_MS + HOLD_MS + OUT_MS
    const val COLOR_FADE_MS = IN_MS + HOLD_MS

    /** The video's scale at the deepest point of the inset. */
    const val INSET_SCALE = 0.94f

    // A cue that cannot vibrate (YtBatteryVibeRules) shows stronger: a wider frame, held longer.
    const val STRONG_EXTRA_HOLD_MS = 400L
    const val STRONG_INSET_SCALE = 0.92f
    const val STRONG_FLASH_FACTOR = 1.5f

    fun holdMs(strong: Boolean): Long = if (strong) HOLD_MS + STRONG_EXTRA_HOLD_MS else HOLD_MS
    fun totalMs(strong: Boolean): Long = IN_MS + holdMs(strong) + OUT_MS
    fun colorFadeMs(strong: Boolean): Long = IN_MS + holdMs(strong)
    fun insetScale(strong: Boolean): Float = if (strong) STRONG_INSET_SCALE else INSET_SCALE

    /** How far the video is inset at [t] ms (0 = full size, 1 = [insetScale]), eased in and out. */
    fun inset(t: Long, strong: Boolean = false): Float {
        val hold = holdMs(strong)
        return when {
            t <= 0 -> 0f
            t < IN_MS -> ease(t / IN_MS.toFloat())
            t < IN_MS + hold -> 1f
            t < IN_MS + hold + OUT_MS -> 1f - ease((t - IN_MS - hold) / OUT_MS.toFloat())
            else -> 0f
        }
    }

    /** The colour's progress from start to end at [t] ms. */
    fun colorFraction(t: Long, strong: Boolean = false): Float =
        (t / colorFadeMs(strong).toFloat()).coerceIn(0f, 1f)

    // smoothstep: soft at both ends
    private fun ease(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** [from] to [to] at [f], through the hue (the shorter way round), so green → red passes yellow. */
    fun blend(from: Int, to: Int, f: Float): Int {
        val a = hsv(from)
        val b = hsv(to)
        val t = f.coerceIn(0f, 1f)
        var dh = b[0] - a[0]
        if (dh > 180f) dh -= 360f
        if (dh < -180f) dh += 360f
        val h = (a[0] + dh * t + 360f) % 360f
        val s = a[1] + (b[1] - a[1]) * t
        val v = a[2] + (b[2] - a[2]) * t
        val alpha = ((from ushr 24) + (((to ushr 24) - (from ushr 24)) * t)).roundToInt()
        return (alpha shl 24) or (rgb(h, s, v) and 0xFFFFFF)
    }

    internal fun hsv(color: Int): FloatArray {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (max == 0f) 0f else d / max
        return floatArrayOf(h, s, max)
    }

    internal fun rgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

/**
 * YT: whether a battery cue's vibration can be felt. Android drops it silently in some states
 * (the cue vibrates as a notification on Android 13+, without a usage before): in battery saver
 * (IGNORED_FOR_POWER), with the ringer on silent or the notification vibration off in the
 * system settings (Android 13+ only, where the notification usage applies). The app does not
 * work around that with another usage; instead it says so and the picture shows stronger.
 */
object YtBatteryVibeRules {
    enum class Block {
        /** "Custom haptics" is off. */
        HAPTICS_OFF,

        /** The device has no vibrator. */
        NO_VIBRATOR,

        /** Battery saver is on: Android ignores the vibration. */
        POWER_SAVE,

        /** The ringer is on silent: Android ignores notification vibrations. */
        SILENT,

        /** Notification vibration is off in the system settings. */
        SYSTEM_OFF,
    }

    /**
     * Why the cue would not be felt now, or null if it vibrates. [ringerSilent] and [systemOff]
     * are only passed where they apply (Android 13+, see the object's comment).
     */
    fun blocked(
        haptics: Boolean,
        hasVibrator: Boolean,
        powerSave: Boolean,
        ringerSilent: Boolean,
        systemOff: Boolean,
    ): Block? = when {
        !haptics -> Block.HAPTICS_OFF
        !hasVibrator -> Block.NO_VIBRATOR
        powerSave -> Block.POWER_SAVE
        ringerSilent -> Block.SILENT
        systemOff -> Block.SYSTEM_OFF
        else -> null
    }

    /** Without a vibration the picture carries the cue alone: stronger ([YtBatteryCues.holdMs]). */
    fun strongVisual(block: Block?): Boolean = block != null
}
