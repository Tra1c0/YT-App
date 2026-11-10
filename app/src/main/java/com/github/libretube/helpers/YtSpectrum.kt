package com.github.libretube.helpers

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * YT: the sound of one moment as [BANDS] levels from 0 to 1, bass to treble, for the wave at the
 * floating player. Pure Kotlin (no Android), so it can be checked on the JVM.
 *
 * How (each step after a source that can be checked):
 * - One FFT of [N] samples every [HOP] samples, with the Blackman window of the W3C Web Audio
 *   AnalyserNode; frames overlap by three quarters, so no syllable falls between two of them.
 * - Bands spaced evenly on the ERB scale of human hearing (Glasberg & Moore, as in Slaney's
 *   Auditory Toolbox), 50 Hz to 12 kHz: the range of speech (fundamental about 85 to 255 Hz,
 *   formants up to about 4 kHz) gets most of the bands, so voices show clearly.
 * - Power per band in dB relative to a full-scale sine, with fractional FFT bins at the edges.
 * - Each band is shown against its own recent loudest level (held for half a second, then
 *   falling), never more than [BOOST_DB] above the loudest band's share: quiet speech under loud
 *   music still moves, and the spectrum keeps its shape, so different sounds look different.
 *   This follows the automatic sensitivity of Cava (karlstav/cava), a widely used visualizer.
 * - Rising fast and falling a little slower, in stream time: the same sound always gives the
 *   same picture, whatever the frame rate of the screen.
 */
class YtSpectrum(sampleRate: Int) {
    val sampleRate: Int = sampleRate.coerceAtLeast(MIN_RATE)

    /** Seconds of sound from one frame to the next. */
    val frameSeconds: Double = HOP.toDouble() / this.sampleRate

    private val window = FloatArray(N) {
        (0.42 - 0.5 * cos(2 * PI * it / N) + 0.08 * cos(4 * PI * it / N)).toFloat()
    }

    /** Makes a sine of amplitude A wholly inside one band read A²: 0 dB at full scale. */
    private val powerScale: Double = 4.0 / (N * window.sumOf { it.toDouble() * it })

    private val bitReverse = IntArray(N).also { rev ->
        var bits = 0
        while (1 shl bits < N) bits++
        for (i in 0 until N) {
            var r = 0
            var v = i
            repeat(bits) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            rev[i] = r
        }
    }
    private val cosTable = FloatArray(N / 2) { cos(2 * PI * it / N).toFloat() }
    private val sinTable = FloatArray(N / 2) { sin(2 * PI * it / N).toFloat() }

    /** For each band: its first FFT bin and how much of each following bin lies inside it. */
    private val bandFirst = IntArray(BANDS)
    private val bandWeights: Array<FloatArray>

    private val re = FloatArray(N)
    private val im = FloatArray(N)
    /**
     * The power of each band in the last frame, linear, 1.0 = a full-scale sine wholly inside
     * the band (read only; the sound engine's features are made from it).
     */
    val bandPower = DoubleArray(BANDS)

    /** The level of each band in the last frame, in dB (for tests and logs). */
    val levelDb = DoubleArray(BANDS)
    private val ceiling = DoubleArray(BANDS)
    private val hold = DoubleArray(BANDS)
    private val shown = FloatArray(BANDS)
    private val attack = (1 - exp(-frameSeconds / ATTACK_S)).toFloat()
    private val release = (1 - exp(-frameSeconds / RELEASE_S)).toFloat()

    init {
        val edges = edges(this.sampleRate)
        val binWidth = this.sampleRate.toDouble() / N
        bandWeights = Array(BANDS) { b ->
            val from = edges[b]
            val to = edges[b + 1]
            // bin k covers [(k - ½)·Δf, (k + ½)·Δf); no DC
            val first = max(1, ((from / binWidth) - 0.5).toInt())
            val last = min(N / 2, ((to / binWidth) + 0.5).toInt() + 1)
            bandFirst[b] = first
            FloatArray(last - first + 1) { i ->
                val k = first + i
                val lo = (k - 0.5) * binWidth
                val hi = (k + 0.5) * binWidth
                (max(0.0, min(hi, to) - max(lo, from)) / binWidth).toFloat()
            }
        }
        reset()
    }

    /** Forgets the past: a new stream, or listening again after a pause in listening. */
    fun reset() {
        ceiling.fill(CEILING_MIN_DB)
        hold.fill(0.0)
        shown.fill(0f)
        levelDb.fill(-120.0)
    }

    /**
     * Analyses [N] samples, sample n being `ring[(start + n) % N]` (oldest first), and writes
     * the shown levels (0 to 1) into [out].
     */
    fun analyse(ring: FloatArray, start: Int, out: FloatArray) {
        for (n in 0 until N) {
            val v = ring[(start + n) and MASK] * window[n]
            val r = bitReverse[n]
            re[r] = v
            im[r] = 0f
        }
        fft()
        var total = 0.0
        for (b in 0 until BANDS) {
            val weights = bandWeights[b]
            val first = bandFirst[b]
            var sum = 0.0
            for (i in weights.indices) {
                val k = first + i
                if (k > N / 2) break
                val w = weights[i]
                if (w == 0f) continue
                sum += w * (re[k].toDouble() * re[k] + im[k].toDouble() * im[k])
            }
            bandPower[b] = sum * powerScale
            levelDb[b] = 10 * log10(bandPower[b] + 1e-12)
            total += bandPower[b]
        }
        val silent = 10 * log10(total + 1e-12) < SILENCE_DB
        var top = CEILING_MIN_DB
        if (!silent) {
            for (b in 0 until BANDS) {
                val d = levelDb[b]
                if (d >= ceiling[b]) {
                    ceiling[b] = d
                    hold[b] = HOLD_S
                } else if (hold[b] > 0) {
                    hold[b] = max(0.0, hold[b] - frameSeconds)
                } else {
                    ceiling[b] -= FALL_DB_PER_S * frameSeconds
                }
                ceiling[b] = max(ceiling[b], CEILING_MIN_DB)
                top = max(top, ceiling[b])
            }
        }
        for (b in 0 until BANDS) {
            val target = if (silent) {
                0f
            } else {
                val reference = max(ceiling[b], top - BOOST_DB)
                ((levelDb[b] - (reference - RANGE_DB)) / RANGE_DB).coerceIn(0.0, 1.0).toFloat()
            }
            val rate = if (target > shown[b]) attack else release
            shown[b] += (target - shown[b]) * rate
            out[b] = shown[b]
        }
    }

    /** In-place radix-2 FFT of [re]/[im], whose input is already in bit-reversed order. */
    private fun fft() {
        var len = 2
        while (len <= N) {
            val half = len shr 1
            val step = N / len
            var i = 0
            while (i < N) {
                var t = 0
                for (k in 0 until half) {
                    val wr = cosTable[t]
                    val wi = -sinTable[t]
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    t += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val BANDS = 16
        const val N = 2048
        const val HOP = 512
        private const val MASK = N - 1
        private const val MIN_RATE = 8000

        const val LOW_HZ = 50.0
        const val HIGH_HZ = 12000.0

        /** Below this total level a frame counts as silence and shows nothing. */
        const val SILENCE_DB = -70.0

        /** A band's loudest level is held this long, then falls [FALL_DB_PER_S]. */
        const val HOLD_S = 0.5
        const val FALL_DB_PER_S = 24.0

        /** A band is shown at most this much above the loudest band's share. */
        const val BOOST_DB = 12.0

        /** The range shown below a band's loudest level. */
        const val RANGE_DB = 30.0

        /** A band's reference never sinks below this, so room noise stays small. */
        const val CEILING_MIN_DB = -45.0

        const val ATTACK_S = 0.015
        const val RELEASE_S = 0.060

        /** ERB number of [hz] (Glasberg & Moore 1990). */
        fun erb(hz: Double): Double = 21.4 * log10(1 + 0.00437 * hz)

        fun erbToHz(erb: Double): Double = (10.0.pow(erb / 21.4) - 1) / 0.00437

        /** The [BANDS] + 1 band edges in Hz for [sampleRate]. */
        fun edges(sampleRate: Int): DoubleArray {
            val high = min(HIGH_HZ, 0.45 * sampleRate)
            val from = erb(LOW_HZ)
            val to = erb(high)
            return DoubleArray(BANDS + 1) { erbToHz(from + (to - from) * it / BANDS) }
        }
    }
}
