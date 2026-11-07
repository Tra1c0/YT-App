package com.github.libretube.helpers

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * YT: a look-ahead true-peak limiter, the last stage of the loudness levelling, so a raised
 * level never clips.
 *
 * Peaks are judged on the samples and between them (4× oversampled with a short windowed sinc),
 * which is what a DAC or a resampler later turns into real overshoot. The gain each sample
 * needs is held as a minimum over the look-ahead window and then averaged over that same
 * window: the averaged gain is never above what any sample in the window needs, so it reaches
 * the needed value exactly in time while still ramping smoothly (no clicks). Recovery is a
 * slow exponential ([releaseMs]) so the gain does not pump. Audio leaves [delay] frames late.
 *
 * Pure Kotlin, no Android: tested on the JVM.
 */
class YtPeakLimiter(
    sampleRate: Int,
    val channels: Int,
    ceilingDb: Double = -1.0,
    lookaheadMs: Double = 5.0,
    releaseMs: Double = 120.0
) {
    val ceiling = 10.0.pow(ceilingDb / 20.0).toFloat()

    private val window = (sampleRate * lookaheadMs / 1000.0).toInt().coerceAtLeast(2)

    /** Frames the audio leaves late. */
    val delay = HALF + window - 1

    private val release = exp(-1000.0 / (releaseMs * sampleRate))

    // the last TAPS frames of the input, for the interpolation: per channel a ring written
    // twice (at pos and pos + TAPS), so the kernels read it without wrapping
    private val hist = FloatArray(2 * TAPS * channels)
    private var histPos = 0

    // delayed audio
    private val line = FloatArray((delay + 1) * channels)
    private var linePos = 0

    // sliding minimum over the window (monotone deque of (index, value))
    private val dqIdx = LongArray(window + 1)
    private val dqVal = DoubleArray(window + 1)
    private var dqHead = 0
    private var dqSize = 0

    // running mean of the released gain over the window
    private val ring = DoubleArray(window)
    private var ringPos = 0
    private var ringSum = 0.0

    private var released = 1.0
    private var lastSegment = 0f
    private var t = 0L

    /** The gain applied to the last frame (1 = untouched), for tests and statistics. */
    var lastGain = 1.0
        private set

    init {
        reset()
    }

    fun reset() {
        hist.fill(0f)
        histPos = 0
        line.fill(0f)
        linePos = 0
        dqHead = 0
        dqSize = 0
        ring.fill(1.0)
        ringPos = 0
        ringSum = window.toDouble()
        released = 1.0
        lastSegment = 0f
        t = 0
        lastGain = 1.0
    }

    /**
     * Takes one frame from [input] at [inOffset] and writes the frame from [delay] frames ago,
     * limited, to [output] at [outOffset] (the two may be the same array and place).
     */
    fun process(input: FloatArray, inOffset: Int, output: FloatArray, outOffset: Int) {
        // store the new frame for interpolation and delay
        val lp = linePos * channels
        for (c in 0 until channels) {
            var x = input[inOffset + c]
            if (x.isNaN()) x = 0f
            val h = c * 2 * TAPS + histPos
            hist[h] = x
            hist[h + TAPS] = x
            line[lp + c] = x
        }
        histPos = (histPos + 1) % TAPS

        // the peak around the frame HALF frames back: the sample itself and the segments to
        // its left and right (a peak between two samples needs both of them lowered)
        var sample = 0f
        var segment = 0f
        for (c in 0 until channels) {
            // the oldest frame sits at histPos, the newest TAPS-1 later
            val base = c * 2 * TAPS + histPos
            sample = max(sample, abs(hist[base + HALF - 1]))
            for (p in 0 until PHASES - 1) {
                var y = 0f
                val k = KERNEL[p]
                for (j in 0 until TAPS) y += k[j] * hist[base + j]
                segment = max(segment, abs(y))
            }
        }
        val peak = max(sample, max(segment, lastSegment))
        lastSegment = segment
        val need = if (peak > ceiling) ceiling.toDouble() / peak else 1.0

        // sliding minimum of the needed gain over the window
        while (dqSize > 0 && dqVal[(dqHead + dqSize - 1) % dqVal.size] >= need) dqSize--
        dqIdx[(dqHead + dqSize) % dqIdx.size] = t
        dqVal[(dqHead + dqSize) % dqVal.size] = need
        dqSize++
        while (dqIdx[dqHead] <= t - window) {
            dqHead = (dqHead + 1) % dqIdx.size
            dqSize--
        }
        val hold = dqVal[dqHead]

        // slow recovery, never above the hold
        released = min(hold, 1.0 - (1.0 - released) * release)

        // mean over the window: ramps down in time for every sample it covers
        ringSum += released - ring[ringPos]
        ring[ringPos] = released
        ringPos = (ringPos + 1) % window
        val gain = min(1.0, ringSum / window)
        lastGain = gain
        t++

        // write the delayed frame
        val outPos = ((linePos + 1) % (delay + 1)) * channels
        val g = gain.toFloat()
        for (c in 0 until channels) {
            // rounding in the running mean could leave a hair above: keep the ceiling hard
            output[outOffset + c] = (line[outPos + c] * g).coerceIn(-ceiling, ceiling)
        }
        linePos = (linePos + 1) % (delay + 1)
    }

    companion object {
        private const val PHASES = 4
        private const val TAPS = 16
        private const val HALF = TAPS / 2

        /**
         * Interpolation kernels for the points ¼, ½ and ¾ between the frames HALF-1 and HALF
         * of the window (Hann-windowed sinc, each scaled to unity gain).
         */
        private val KERNEL: Array<FloatArray> = Array(PHASES - 1) { p ->
            val frac = (p + 1).toDouble() / PHASES
            val raw = DoubleArray(TAPS) { j ->
                val x = j - (HALF - 1) - frac
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                val w = 0.5 + 0.5 * cos(PI * x / HALF)
                sinc * w
            }
            val sum = raw.sum()
            FloatArray(TAPS) { (raw[it] / sum).toFloat() }
        }
    }
}
