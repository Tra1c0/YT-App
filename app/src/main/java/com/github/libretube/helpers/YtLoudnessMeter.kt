package com.github.libretube.helpers

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * YT: loudness as ITU-R BS.1770 / EBU R128 measures it (LUFS), sample by sample.
 *
 * K-weighting (a high shelf for the head plus a high pass) per channel, then mean squares in
 * 100 ms steps. From those come the momentary (400 ms), the short-term (3 s) and the gated
 * integrated loudness of everything since [reset] (absolute gate -70 LUFS, relative gate
 * -10 LU). The integrated value is kept as a histogram of 0.1 LU bins, so it can be asked every
 * step without keeping all blocks. All channels weigh 1 (the surround weights do not matter
 * for stereo, which is what the player plays).
 *
 * Pure Kotlin, no Android: tested on the JVM.
 */
class YtLoudnessMeter(val sampleRate: Int, val channels: Int) {

    /** Samples per 100 ms step. */
    val hop = (sampleRate / 10).coerceAtLeast(1)

    // K-weighting coefficients: shelf b0, b1, b2, a1, a2, then high pass a1, a2
    private val k = kWeighting(sampleRate.toDouble())
    private val s1b0 = k[0]
    private val s1b1 = k[1]
    private val s1b2 = k[2]
    private val s1a1 = k[3]
    private val s1a2 = k[4]
    private val s2a1 = k[5]
    private val s2a2 = k[6]

    // two biquads per channel, direct form I state
    private val z = DoubleArray(channels * 8)

    private var acc = 0.0
    private var inHop = 0

    // energies (sum over channels of the mean square) of the last 30 steps
    private val steps = DoubleArray(SHORT_STEPS)
    private var stepCount = 0L

    // histogram of the 400 ms blocks for the integrated loudness
    private val binCount = IntArray(BINS)
    private val binEnergy = DoubleArray(BINS)

    /** Loudness of the last 400 ms, NaN before the first block. */
    var momentary = Double.NaN
        private set

    /** Loudness of the last 3 s (or less, right after a reset), NaN before the first block. */
    var shortTerm = Double.NaN
        private set

    /** Gated loudness since [reset], NaN while no block passed the gates. */
    var integrated = Double.NaN
        private set

    /** How many 400 ms blocks passed the absolute gate since [reset]. */
    var gatedBlocks = 0
        private set

    /** Forgets everything: a new video. */
    fun reset() {
        restart()
        binCount.fill(0)
        binEnergy.fill(0.0)
        integrated = Double.NaN
        gatedBlocks = 0
    }

    /**
     * Starts the short windows afresh but keeps the integrated loudness (a seek within the
     * same video: what was heard still says how loud it is).
     */
    fun restart() {
        z.fill(0.0)
        acc = 0.0
        inHop = 0
        steps.fill(0.0)
        stepCount = 0
        momentary = Double.NaN
        shortTerm = Double.NaN
    }

    /**
     * Adds one frame (all channels, interleaved at [offset] of [data]). True when a 100 ms
     * step just ended and the loudness values were updated.
     */
    fun add(data: FloatArray, offset: Int): Boolean {
        var sum = 0.0
        for (c in 0 until channels) {
            val x = data[offset + c].toDouble()
            val i = c * 8
            // stage 1: shelf
            val y1 = s1b0 * x + s1b1 * z[i] + s1b2 * z[i + 1] - s1a1 * z[i + 2] - s1a2 * z[i + 3]
            z[i + 1] = z[i]; z[i] = x
            z[i + 3] = z[i + 2]; z[i + 2] = y1
            // stage 2: high pass (b = 1, -2, 1)
            val y2 = y1 - 2.0 * z[i + 4] + z[i + 5] - s2a1 * z[i + 6] - s2a2 * z[i + 7]
            z[i + 5] = z[i + 4]; z[i + 4] = y1
            z[i + 7] = z[i + 6]; z[i + 6] = y2
            sum += y2 * y2
        }
        acc += sum
        if (++inHop < hop) return false
        endStep()
        return true
    }

    private fun endStep() {
        val energy = acc / hop
        acc = 0.0
        inHop = 0
        // denormals and lingering filter noise in silence would only cost time
        if (energy < 1e-20) z.fill(0.0)
        steps[(stepCount % SHORT_STEPS).toInt()] = energy
        stepCount++

        val m = mean(BLOCK_STEPS)
        val s = mean(SHORT_STEPS)
        momentary = lufs(m)
        shortTerm = lufs(s)
        if (stepCount >= BLOCK_STEPS) addBlock(m)
    }

    private fun mean(n: Int): Double {
        val have = minOf(stepCount, n.toLong()).toInt()
        if (have == 0) return 0.0
        var sum = 0.0
        for (k in 1..have) sum += steps[((stepCount - k) % SHORT_STEPS).toInt()]
        return sum / have
    }

    private fun addBlock(energy: Double) {
        val l = lufs(energy)
        if (l.isNaN() || l <= ABSOLUTE_GATE) return
        val bin = ((l - ABSOLUTE_GATE) / BIN_LU).toInt().coerceIn(0, BINS - 1)
        binCount[bin]++
        binEnergy[bin] += energy
        gatedBlocks++
        integrated = gated()
    }

    private fun gated(): Double {
        var n = 0L
        var e = 0.0
        for (b in 0 until BINS) {
            n += binCount[b]
            e += binEnergy[b]
        }
        if (n == 0L) return Double.NaN
        val relative = lufs(e / n) + RELATIVE_GATE
        val from = ((relative - ABSOLUTE_GATE) / BIN_LU).toInt().coerceIn(0, BINS)
        n = 0
        e = 0.0
        for (b in from until BINS) {
            n += binCount[b]
            e += binEnergy[b]
        }
        return if (n == 0L) Double.NaN else lufs(e / n)
    }

    companion object {
        const val ABSOLUTE_GATE = -70.0
        const val RELATIVE_GATE = -10.0
        private const val BLOCK_STEPS = 4
        private const val SHORT_STEPS = 30
        private const val BIN_LU = 0.1
        private const val BINS = 800 // -70 … +10 LUFS

        /** libebur128's formulas, valid for any sample rate. */
        private fun kWeighting(fs: Double): DoubleArray {
            val out = DoubleArray(7)
            var f0 = 1681.974450955533
            val g = 3.999843853973347
            var q = 0.7071752369554196
            var k = tan(PI * f0 / fs)
            val vh = 10.0.pow(g / 20.0)
            val vb = vh.pow(0.4996667741545416)
            var a0 = 1.0 + k / q + k * k
            out[0] = (vh + vb * k / q + k * k) / a0
            out[1] = 2.0 * (k * k - vh) / a0
            out[2] = (vh - vb * k / q + k * k) / a0
            out[3] = 2.0 * (k * k - 1.0) / a0
            out[4] = (1.0 - k / q + k * k) / a0
            f0 = 38.13547087602444
            q = 0.5003270373238773
            k = tan(PI * f0 / fs)
            a0 = 1.0 + k / q + k * k
            out[5] = 2.0 * (k * k - 1.0) / a0
            out[6] = (1.0 - k / q + k * k) / a0
            return out
        }

        fun lufs(energy: Double): Double =
            if (energy <= 0.0) Double.NEGATIVE_INFINITY else -0.691 + 10.0 * log10(energy)
    }
}
