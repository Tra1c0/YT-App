package com.github.libretube.helpers

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/** YT: what the loudness levelling should do; read once per 100 ms step. */
data class YtLevelSettings(
    /** Brings every video towards [targetLufs] and enables [dynamic]. */
    val enabled: Boolean = true,
    /** Lifts quiet passages on top of the static level. */
    val dynamic: Boolean = true,
    val targetLufs: Double = -14.0,
    /** Global trim in dB, also applied while [enabled] is off. */
    val trimDb: Double = 0.0
) {
    /** False when the processor can leave the sound alone (bit-exact passthrough). */
    val active: Boolean get() = enabled || trimDb != 0.0
}

/**
 * YT: the loudness levelling, on interleaved float frames.
 *
 * 1. Static: measures the video's integrated loudness ([YtLoudnessMeter]) and moves it to the
 *    target (at most +[MAX_STATIC_BOOST] dB). It falls quickly and rises slowly, so a loud part
 *    after a quiet start is caught soon, and the gain settles once the video is known.
 * 2. Dynamic: an upward compressor (ratio 2:1 below the target, at most +[MAX_DYNAMIC_BOOST]
 *    dB) driven by the 3 s loudness, with ~1 s attack and ~2 s release; the 400 ms loudness
 *    takes the boost back quickly when it gets loud again. Near silence is not lifted, so
 *    hiss and room noise stay where they are.
 * 3. Trim: the global volume.
 * 4. A true-peak limiter at -1 dBTP ([YtPeakLimiter]) whenever this runs.
 *
 * The gain moves in linear ramps across each 100 ms step, so it never jumps (no clicks).
 * Pure Kotlin, no Android: tested on the JVM.
 */
class YtLeveller(val sampleRate: Int, val channels: Int) {
    val meter = YtLoudnessMeter(sampleRate, channels)
    val limiter = YtPeakLimiter(sampleRate, channels)

    var settings = YtLevelSettings()

    /** Smoothed gains in dB, for tests and statistics. */
    var staticDb = 0.0
        private set
    var dynamicDb = 0.0
        private set
    private var trimDb = 0.0

    private var gain = 1.0f

    /** The linear gain before the limiter right now, for tests. */
    val currentGain: Float get() = gain
    private var step = 0f
    private var rampLeft = 0

    private val stepSeconds = meter.hop.toDouble() / sampleRate

    /** A new video: forget its loudness and start from the plain level. */
    fun reset() {
        meter.reset()
        limiter.reset()
        staticDb = 0.0
        dynamicDb = 0.0
        trimDb = settings.trimDb
        gain = targetGain()
        step = 0f
        rampLeft = 0
    }

    /** A seek: drop what is in flight but keep the gains learnt for this video. */
    fun flush() {
        meter.restart()
        limiter.reset()
        gain = targetGain()
        step = 0f
        rampLeft = 0
    }

    /** Levels [frames] frames of [data] in place (they come out [YtPeakLimiter.delay] late). */
    fun process(data: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            val o = f * channels
            for (c in 0 until channels) {
                val x = data[o + c]
                // NaN or wild values from a broken decoder must not poison the filters
                data[o + c] = if (x.isNaN()) 0f else x.coerceIn(-LIMIT, LIMIT)
            }
            if (meter.add(data, o)) update()
            for (c in 0 until channels) data[o + c] *= gain
            if (rampLeft > 0) {
                gain += step
                if (--rampLeft == 0) gain = rampTarget
            }
            limiter.process(data, o, data, o)
        }
    }

    /** The audio still in the limiter's delay, [YtPeakLimiter.delay] frames, at the end. */
    fun drain(): FloatArray {
        val out = FloatArray(limiter.delay * channels)
        val zero = FloatArray(channels)
        for (f in 0 until limiter.delay) limiter.process(zero, 0, out, f * channels)
        return out
    }

    private var rampTarget = 1f

    private fun update() {
        val s = settings
        staticDb = smooth(staticDb, staticTarget(s), if (meter.gatedBlocks < 50) 1.5 else 4.0, 0.4)
        dynamicDb = smooth(dynamicDb, dynamicTarget(s), 2.0, 1.0)
        trimDb = smooth(trimDb, s.trimDb, TRIM_TAU, TRIM_TAU)
        rampTarget = targetGain()
        rampLeft = meter.hop
        step = (rampTarget - gain) / rampLeft
    }

    // switching off lets static and dynamic glide to 0 dB instead of jumping
    private fun targetGain(): Float {
        val db = (staticDb + dynamicDb).coerceAtMost(MAX_TOTAL_BOOST) + trimDb
        return 10.0.pow(db / 20.0).toFloat()
    }

    private fun staticTarget(s: YtLevelSettings): Double {
        if (!s.enabled) return 0.0
        val i = meter.integrated
        // nothing heard yet (start, or only silence): keep where it is
        if (i.isNaN()) return staticDb
        return (s.targetLufs - i).coerceIn(-MAX_STATIC_CUT, MAX_STATIC_BOOST)
    }

    private fun dynamicTarget(s: YtLevelSettings): Double {
        if (!s.enabled || !s.dynamic) return 0.0
        val short = meter.shortTerm
        val moment = meter.momentary
        if (short.isNaN() || short.isInfinite()) return 0.0
        val after = short + staticDb
        // the louder of the two, so a loud entry takes the boost back within ~400 ms
        val level = if (moment.isNaN()) after else max(after, moment + staticDb)
        val boost = ((s.targetLufs - level) * (1.0 - 1.0 / RATIO)).coerceIn(0.0, MAX_DYNAMIC_BOOST)
        // fade the lift out towards silence (full at -40 LUFS, none at -50 LUFS)
        val weight = ((after - SILENCE) / 10.0).coerceIn(0.0, 1.0)
        return boost * weight
    }

    /** One-pole smoothing per 100 ms step with separate rise and fall time constants (s). */
    private fun smooth(value: Double, target: Double, riseTau: Double, fallTau: Double): Double {
        val tau = if (target > value) riseTau else fallTau
        val k = 1.0 - exp(-stepSeconds / tau)
        return value + (target - value) * k
    }

    companion object {
        const val MAX_STATIC_BOOST = 12.0
        const val MAX_STATIC_CUT = 20.0
        const val MAX_DYNAMIC_BOOST = 9.0
        const val MAX_TOTAL_BOOST = 15.0
        private const val RATIO = 2.0
        private const val TRIM_TAU = 0.25
        private const val SILENCE = -50.0
        private const val LIMIT = 4f
    }
}
