package com.github.libretube.helpers

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/**
 * YT: maps a level in dB onto 0..1 against what this sound has been doing lately, so a quiet
 * talk and a loud song both use the whole range.
 *
 * - The ceiling jumps up to every new loudest level, is held [holdS], then sinks towards the
 *   current level with a time constant of [ceilingFallS] (never below [ceilingMinDb], so near
 *   silence is not blown up to full scale).
 * - The floor follows the level down quickly ([floorFallS]: a pause in speech shows as a dip)
 *   and up slowly ([floorRiseS]), and stays between [minSpanDb] and [maxSpanDb] below the
 *   ceiling: a steady tone does not swing the range, a long pause does not stretch it.
 * - The position is linear in dB between floor and ceiling (dB already is roughly how loud a
 *   sound is felt).
 *
 * Times are seconds of the stream, so the same sound always maps the same way. Pure Kotlin, no
 * allocation: runs on the player's audio thread.
 */
class YtAdaptiveRange(
    private val minSpanDb: Double,
    private val maxSpanDb: Double,
    private val ceilingMinDb: Double,
    private val holdS: Double = 1.5,
    private val ceilingFallS: Double = 4.0,
    private val floorRiseS: Double = 8.0,
    private val floorFallS: Double = 0.4,
) {
    /** The current ceiling in dB, NaN before the first sounding moment. */
    var ceiling = Double.NaN
        private set

    /** The current floor in dB, NaN before the first sounding moment. */
    var floor = Double.NaN
        private set

    private var hold = 0.0

    fun reset() {
        ceiling = Double.NaN
        floor = Double.NaN
        hold = 0.0
    }

    /** Learns from [db] (a sounding moment) over [dt] s of the stream; its place from 0 to 1. */
    fun map(db: Double, dt: Double): Float {
        if (ceiling.isNaN()) {
            // the first moment: a little headroom, the floor halfway into the allowed span
            ceiling = max(db + START_HEADROOM_DB, ceilingMinDb)
            floor = ceiling - (minSpanDb + maxSpanDb) / 2
            hold = holdS
        } else {
            if (db >= ceiling) {
                ceiling = db
                hold = holdS
            } else if (hold > 0) {
                hold = max(0.0, hold - dt)
            } else {
                ceiling -= (ceiling - db) * (1 - exp(-dt / ceilingFallS))
            }
            ceiling = max(ceiling, ceilingMinDb)
            val tau = if (db < floor) floorFallS else floorRiseS
            floor += (db - floor) * (1 - exp(-dt / tau))
        }
        floor = floor.coerceIn(ceiling - maxSpanDb, ceiling - minSpanDb)
        return ((db - floor) / (ceiling - floor)).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        const val START_HEADROOM_DB = 3.0
    }
}

/**
 * YT: how strongly something new starts in this moment (a drum hit, a plucked string, a
 * consonant), from 0 to 1. Spectral flux on the bands: the mean rise in dB per band against the
 * frame two hops back (23 ms at 44.1 kHz; the frames overlap by three quarters, so the one just
 * before is too similar), only rises count. Bands more than [DYNAMIC_DB] below the loudest
 * band are raised to that floor, so hiss in empty bands does not flicker. The flux is then
 * measured against an adaptive threshold (its own running mean × [RATIO] + [OFFSET_DB]) and
 * scaled by its recent peak, so busy and calm sound both give clear, sparse onsets.
 *
 * The output has no decay of its own: the screen side ([com.github.libretube.ui.yt.sound])
 * turns it into a punch with attack, decay and a rate limit. Pure, no allocation after
 * construction.
 */
class YtOnsetDetector(private val bands: Int) {
    private val prev1 = DoubleArray(bands)
    private val prev2 = DoubleArray(bands)
    private var frames = 0
    private var mean = 0.0
    private var peak = PEAK_MIN_DB

    /** The stream restarted (seek, new video): the history is no longer the sound before. */
    fun restart() {
        frames = 0
    }

    /** Forgets everything, the learnt statistics too. */
    fun reset() {
        frames = 0
        mean = 0.0
        peak = PEAK_MIN_DB
    }

    /**
     * One frame: [db] per band, [sounding] false in silence (no learning, no onset), [dt] s of
     * the stream since the last frame.
     */
    fun step(db: DoubleArray, sounding: Boolean, dt: Double): Float {
        var top = -200.0
        for (b in 0 until bands) top = max(top, db[b])
        val bottom = max(top - DYNAMIC_DB, ABSOLUTE_FLOOR_DB)
        var flux = 0.0
        for (b in 0 until bands) {
            val v = max(db[b], bottom)
            if (frames >= 2) flux += max(0.0, v - prev2[b])
            prev2[b] = prev1[b]
            prev1[b] = v
        }
        if (frames < 2) {
            // warming up after a restart: no history to compare with yet
            frames++
            return 0f
        }
        if (!sounding) return 0f
        flux /= bands
        val threshold = mean * RATIO + OFFSET_DB
        val strength = ((flux - threshold) / max(peak - threshold, RANGE_MIN_DB)).coerceIn(0.0, 1.0)
        mean += (flux - mean) * (1 - exp(-dt / MEAN_S))
        peak = if (flux > peak) flux else peak - (peak - PEAK_MIN_DB) * (1 - exp(-dt / PEAK_FALL_S))
        return strength.toFloat()
    }

    companion object {
        const val DYNAMIC_DB = 40.0
        const val ABSOLUTE_FLOOR_DB = -90.0
        const val RATIO = 1.5
        const val OFFSET_DB = 0.5
        const val PEAK_MIN_DB = 3.0
        const val RANGE_MIN_DB = 2.0
        const val MEAN_S = 1.0
        const val PEAK_FALL_S = 3.0
    }
}

/**
 * YT: the features of one analysis frame for the sound-reactive UI (YtSound), made from the
 * band powers of [YtSpectrum] on the player's audio thread, in stream time:
 *
 * - [LEVEL]: loudness, the band powers weighted like BS.1770's K-weighting (head shelf and
 *   high pass, at each band's centre), in dB, through a [YtAdaptiveRange] (18 to 40 dB span).
 * - [BASS], [MID], [HIGH]: the bands grouped by centre frequency (below [BASS_TOP_HZ], up to
 *   [MID_TOP_HZ], above), each power sum in dB through its own [YtAdaptiveRange] (15 to 36 dB).
 * - [ONSET]: [YtOnsetDetector] (no decay; the screen side shapes it).
 * - [AUDIBLE]: 1 while the loudness is above [GATE_DB], else 0 (then everything else is 0).
 *
 * All outputs 0..1, unsmoothed (the screen side smooths in wall time). No allocation in
 * [analyse].
 */
class YtSoundFeatures(sampleRate: Int) {
    private val bands = YtSpectrum.BANDS
    private val group = IntArray(bands)
    private val weight = DoubleArray(bands)
    private val db = DoubleArray(bands)
    private val sums = DoubleArray(GROUPS)
    private val groupSize = IntArray(GROUPS)

    private val level = YtAdaptiveRange(minSpanDb = 18.0, maxSpanDb = 40.0, ceilingMinDb = -50.0)
    private val groups = Array(GROUPS) { YtAdaptiveRange(minSpanDb = 15.0, maxSpanDb = 36.0, ceilingMinDb = -60.0) }
    private val onset = YtOnsetDetector(bands)

    init {
        val rate = sampleRate.coerceAtLeast(8000)
        val edges = YtSpectrum.edges(rate)
        for (b in 0 until bands) {
            val centre = YtSpectrum.erbToHz((YtSpectrum.erb(edges[b]) + YtSpectrum.erb(edges[b + 1])) / 2)
            group[b] = when {
                centre < BASS_TOP_HZ -> 0
                centre < MID_TOP_HZ -> 1
                else -> 2
            }
            groupSize[group[b]]++
            weight[b] = YtLoudnessMeter.kWeightPower(centre, rate.toDouble())
        }
    }

    /** Which group (0 bass, 1 mid, 2 high) band [b] belongs to. */
    fun groupOf(b: Int): Int = group[b]

    /** The stream restarted (seek, new video, change of speed): onsets start afresh. */
    fun restart() = onset.restart()

    /** Forgets everything (listening again after a while): ranges are learnt anew. */
    fun reset() {
        level.reset()
        groups.forEach { it.reset() }
        onset.reset()
    }

    /**
     * One analysis frame: [power] per band (linear, as [YtSpectrum.bandPower]), [dt] s of the
     * stream since the last frame; writes [COUNT] values into [out].
     */
    fun analyse(power: DoubleArray, dt: Double, out: FloatArray) {
        var weighted = 0.0
        sums.fill(0.0)
        for (b in 0 until bands) {
            val p = power[b]
            db[b] = 10 * log10(p + 1e-12)
            weighted += p * weight[b]
            sums[group[b]] += p
        }
        val loudness = 10 * log10(weighted + 1e-12)
        val sounding = loudness >= GATE_DB
        out[ONSET] = onset.step(db, sounding, dt)
        if (!sounding) {
            out[LEVEL] = 0f
            out[BASS] = 0f
            out[MID] = 0f
            out[HIGH] = 0f
            out[AUDIBLE] = 0f
            return
        }
        out[LEVEL] = level.map(loudness, dt)
        for (g in 0 until GROUPS) {
            val groupDb = 10 * log10(sums[g] + 1e-12)
            out[BASS + g] = if (groupSize[g] == 0 || groupDb < GATE_DB) 0f else groups[g].map(groupDb, dt)
        }
        out[AUDIBLE] = 1f
    }

    companion object {
        const val LEVEL = 0
        const val BASS = 1
        const val MID = 2
        const val HIGH = 3
        const val ONSET = 4
        const val AUDIBLE = 5
        const val COUNT = 6
        private const val GROUPS = 3

        const val BASS_TOP_HZ = 250.0
        const val MID_TOP_HZ = 2000.0

        /** Below this (dB against a full-scale sine) a moment counts as silence. */
        const val GATE_DB = -65.0
    }
}
