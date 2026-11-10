package com.github.libretube.ui.yt.sound

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/*
 * YT: the screen side of the sound engine, pure Kotlin (tested on the JVM). The audio thread
 * delivers raw features per analysis frame (YtSoundFeatures, 0..1, unsmoothed, in stream time);
 * these classes turn them, once per display frame and in wall time, into the smooth values of
 * YtSoundFrame. Wall time, because the eye judges motion and flashing in real seconds, also
 * when the video plays at 2x.
 */

/**
 * Attack/release one-pole ([attackS] while rising, [releaseS] while falling) followed by a
 * symmetric one-pole of [easeS], so a step becomes an S-shaped move, not a jump: organic, and it
 * removes most of the frame-to-frame jitter of the analysis. Frame-rate independent.
 */
class YtEnvelope(
    private val attackS: Float,
    private val releaseS: Float,
    private val easeS: Float,
) {
    private var fast = 0f

    var value = 0f
        private set

    fun reset() {
        fast = 0f
        value = 0f
    }

    fun step(target: Float, dt: Float): Float {
        if (dt <= 0f) return value
        val tau = if (target > fast) attackS else releaseS
        fast += (target - fast) * (1f - exp(-dt / tau))
        value += (fast - value) * (1f - exp(-dt / easeS))
        return value
    }
}

/**
 * The punch: rises to an onset within a frame or two ([attackS]) and decays exponentially
 * ([decayS] time constant: to 5 % in about 3·decayS). Its strength comes from a budget that
 * refills by 1 in [refillS]: a rise of r uses r of it, so a full-strength punch can come at most
 * once per [refillS] (330 ms, i.e. at most about 3 per second); a hit right after another is
 * only as strong as the budget allows. Frame-rate independent.
 */
class YtPunch(
    private val attackS: Float = 0.012f,
    private val decayS: Float = 0.07f,
    private val refillS: Float = 0.33f,
) {
    private var peak = 0f
    private var budget = 1f

    var value = 0f
        private set

    fun reset() {
        peak = 0f
        budget = 1f
        value = 0f
    }

    /** [onset] is the strongest onset heard since the last frame (0..1). */
    fun step(onset: Float, dt: Float): Float {
        if (dt <= 0f) return value
        budget = min(1f, budget + dt / refillS)
        peak *= exp(-dt / decayS)
        val rise = min(onset.coerceIn(0f, 1f) - peak, budget)
        if (rise > 0f) {
            peak += rise
            budget -= rise
        }
        value = if (peak > value) value + (peak - value) * (1f - exp(-dt / attackS)) else peak
        return value
    }
}

/**
 * The flash guard, the last step of every value. A flash (WCAG 2.3.1) is a pair of opposing
 * changes; more than three in one second is the limit. With [swing] as the size that counts
 * (0.1 of the full range, i.e. 10 % if the UI maps 0..1 onto the whole brightness range), the
 * output is followed as a zigzag of turning points at least [swing] apart, and a turn is only
 * allowed [minLegS] after the previous one: until then the value holds its extreme. Each change
 * of [swing] or more thus lasts at least minLegS minus one display frame (200 ms − 33 ms at
 * 30 fps = 167 ms), a flash at least 333 ms: never more than 3 per second, whatever the input.
 *
 * After a hold the value catches up with the input eased ([catchS]), so the end of a hold is no
 * jump either; otherwise it follows exactly. Smaller wiggles (under [swing]) pass freely.
 * Frame-rate independent.
 */
class YtSwingGuard(
    private val swing: Float = SWING,
    private val minLegS: Float = MIN_LEG_S,
    private val catchS: Float = 0.04f,
) {
    private var time = 0f
    private var dir = 0
    private var anchor = 0f
    private var anchorTime = 0f
    private var extreme = 0f
    private var extremeTime = 0f
    private var catching = false

    var value = 0f
        private set

    fun reset() {
        time = 0f
        dir = 0
        anchor = 0f
        anchorTime = 0f
        extreme = 0f
        extremeTime = 0f
        catching = false
        value = 0f
    }

    fun step(input: Float, dt: Float): Float {
        time += dt.coerceAtLeast(0f)
        if (dir == 0) {
            // no direction yet: follow until the first move
            if (input != anchor) {
                dir = if (input > anchor) 1 else -1
                extreme = input
                extremeTime = time
            }
            value = input
            return value
        }
        if ((input - value) * dir >= 0f && (input - extreme) * dir >= 0f) {
            // the leg goes on: follow at once
            catching = false
            value = input
            extreme = input
            extremeTime = time
            return value
        }
        val legSize = abs(extreme - anchor)
        if (legSize >= swing && time - anchorTime < minLegS) {
            // too soon after the last turn: hold the extreme
            value = extreme
            extremeTime = time
            catching = true
            return value
        }
        // back from the extreme: eased while catching up after a hold, else exact
        val next = if (catching) value + (input - value) * (1f - exp(-dt.coerceAtLeast(0f) / catchS)) else input
        if (abs(input - next) < CAUGHT) catching = false
        value = if ((next - extreme) * dir > 0f) extreme else next
        if ((value - extreme) * dir > 0f) {
            extreme = value
            extremeTime = time
        }
        if (abs(extreme - value) >= swing) {
            // a turn: the extreme is the new anchor, the leg runs the other way now
            anchor = extreme
            anchorTime = extremeTime
            dir = -dir
            extreme = value
            extremeTime = time
        }
        return value
    }

    companion object {
        const val SWING = 0.1f
        const val MIN_LEG_S = 0.2f
        private const val CAUGHT = 0.01f
    }
}

/**
 * All values of one [YtSoundFrame], from the raw features to what is published: envelopes per
 * field, the punch, a fade of [FADE_S] in and out (to SILENT when playback stops or the UI stops
 * listening), the flash guard on every value, and [playing] with hysteresis. Main thread.
 */
class YtSoundShaper {
    private val envelopes = arrayOf(
        YtEnvelope(attackS = 0.05f, releaseS = 0.25f, easeS = 0.04f), // level
        YtEnvelope(attackS = 0.03f, releaseS = 0.20f, easeS = 0.035f), // bass
        YtEnvelope(attackS = 0.03f, releaseS = 0.16f, easeS = 0.035f), // mid
        YtEnvelope(attackS = 0.02f, releaseS = 0.12f, easeS = 0.035f), // high
    )
    private val punch = YtPunch()
    private val guards = Array(FIELDS) { YtSwingGuard() }

    /** level, bass, mid, high, punch as published (0..1). */
    val out = FloatArray(FIELDS)

    var fade = 0f
        private set

    var playing = false
        private set
    private var playingFor = 0f

    /** True when everything has come to rest at zero: SILENT, the engine may stop. */
    val settled: Boolean get() = fade == 0f && out.all { it == 0f }

    fun reset() {
        envelopes.forEach { it.reset() }
        punch.reset()
        guards.forEach { it.reset() }
        out.fill(0f)
        fade = 0f
        playing = false
        playingFor = 0f
    }

    /**
     * One display frame, [dt] s after the last one. [active]: playback runs and the UI listens.
     * [level]..[high]: the heard moment's features (0 where none are known), [onset] the
     * strongest onset heard since the last frame.
     */
    fun step(dt: Float, active: Boolean, level: Float, bass: Float, mid: Float, high: Float, onset: Float) {
        val d = dt.coerceIn(0f, MAX_DT)
        fade = if (active) min(1f, fade + d / FADE_S) else (fade - d / FADE_S).coerceAtLeast(0f)
        val on = if (active) 1f else 0f
        out[LEVEL] = shape(LEVEL, envelopes[0].step(level * on, d), d)
        out[BASS] = shape(BASS, envelopes[1].step(bass * on, d), d)
        out[MID] = shape(MID, envelopes[2].step(mid * on, d), d)
        out[HIGH] = shape(HIGH, envelopes[3].step(high * on, d), d)
        out[PUNCH] = shape(PUNCH, punch.step(onset * on, d), d)

        // playing: on above PLAY_ON, off below PLAY_OFF after PLAY_DWELL_S, at once when stopped
        playingFor += d
        val next = when {
            !active -> false
            playing -> out[LEVEL] >= PLAY_OFF || playingFor < PLAY_DWELL_S
            else -> out[LEVEL] >= PLAY_ON && playingFor >= PLAY_DWELL_S
        }
        if (next != playing) {
            playing = next
            playingFor = 0f
        }
    }

    private fun shape(field: Int, value: Float, dt: Float): Float {
        val guarded = guards[field].step((value * fade).coerceIn(0f, 1f), dt)
        return if (guarded < SNAP) 0f else guarded
    }

    /** The frame to publish (a new object only when something changed). */
    fun frame(last: YtSoundFrame): YtSoundFrame {
        if (last.level == out[LEVEL] && last.bass == out[BASS] && last.mid == out[MID] &&
            last.high == out[HIGH] && last.punch == out[PUNCH] && last.playing == playing
        ) {
            return last
        }
        if (!playing && out.all { it == 0f }) return YtSoundFrame.SILENT
        return YtSoundFrame(out[LEVEL], out[BASS], out[MID], out[HIGH], out[PUNCH], playing)
    }

    companion object {
        const val LEVEL = 0
        const val BASS = 1
        const val MID = 2
        const val HIGH = 3
        const val PUNCH = 4
        const val FIELDS = 5

        const val FADE_S = 0.3f
        const val MAX_DT = 0.1f
        const val SNAP = 0.002f
        const val PLAY_ON = 0.04f
        const val PLAY_OFF = 0.015f
        const val PLAY_DWELL_S = 0.3f
    }
}
