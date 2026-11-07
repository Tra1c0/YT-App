package com.github.libretube.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Loudness meter, levelling and limiter against synthetic sound. */
class YtLoudnessTest {

    private fun sine(rate: Int, seconds: Double, amp: Double, freq: Double = 1000.0, ch: Int = 2): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n * ch) { i -> (amp * sin(2 * PI * freq * (i / ch) / rate)).toFloat() }
    }

    /** Amplitude of a stereo 1 kHz sine at [lufs] (0 dBFS on both channels is about 0 LUFS). */
    private fun ampFor(rate: Int, lufs: Double): Double {
        val m = YtLoudnessMeter(rate, 2)
        val s = sine(rate, 5.0, 0.5)
        for (f in 0 until s.size / 2) m.add(s, f * 2)
        return 0.5 * 10.0.pow((lufs - m.integrated) / 20.0)
    }

    private fun loudness(rate: Int, data: FloatArray, fromFrame: Int): Double {
        val m = YtLoudnessMeter(rate, 2)
        for (f in fromFrame until data.size / 2) m.add(data, f * 2)
        return m.integrated
    }

    @Test
    fun meterMatchesReference() {
        // EBU Tech 3341: a 1 kHz sine at 0 dBFS on one channel reads -3.01 LUFS
        for (rate in intArrayOf(44100, 48000)) {
            val m = YtLoudnessMeter(rate, 2)
            val s = sine(rate, 5.0, 1.0)
            for (f in 0 until s.size / 2) s[f * 2 + 1] = 0f
            for (f in 0 until s.size / 2) m.add(s, f * 2)
            assertEquals(-3.01, m.integrated, 0.1)
            assertEquals(-3.01, m.shortTerm, 0.1)
        }
    }

    @Test
    fun meterGatesSilence() {
        val rate = 48000
        val m = YtLoudnessMeter(rate, 2)
        val loud = sine(rate, 5.0, ampFor(rate, -20.0))
        for (f in 0 until loud.size / 2) m.add(loud, f * 2)
        val quiet = FloatArray(rate * 20 * 2)
        for (f in 0 until quiet.size / 2) m.add(quiet, f * 2)
        // silence after the tone does not pull the integrated value down
        assertEquals(-20.0, m.integrated, 0.2)
    }

    private fun levelled(rate: Int, inLufs: Double, settings: YtLevelSettings, seconds: Double = 40.0): Double {
        val lev = YtLeveller(rate, 2).apply { this.settings = settings; reset() }
        val data = sine(rate, seconds, ampFor(rate, inLufs), 440.0)
        // in blocks of varying size, like the player hands them over
        var at = 0
        val r = Random(1)
        while (at < data.size / 2) {
            val n = minOf(64 + r.nextInt(4000), data.size / 2 - at)
            val chunk = data.copyOfRange(at * 2, (at + n) * 2)
            lev.process(chunk, n)
            System.arraycopy(chunk, 0, data, at * 2, n * 2)
            at += n
        }
        return loudness(rate, data, data.size / 2 - rate * 10)
    }

    @Test
    fun staticLevelReachesTarget() {
        val plain = YtLevelSettings(dynamic = false)
        for (rate in intArrayOf(44100, 48000)) {
            assertEquals(-14.0, levelled(rate, -23.0, plain), 1.0)
            assertEquals(-14.0, levelled(rate, -6.0, plain), 1.0)
            assertEquals(-16.0, levelled(rate, -24.0, plain.copy(targetLufs = -16.0)), 1.0)
            assertEquals(-12.0, levelled(rate, -20.0, plain.copy(targetLufs = -12.0)), 1.0)
        }
    }

    @Test
    fun dynamicDoesNotDisturbASteadyLevel() {
        assertEquals(-14.0, levelled(48000, -20.0, YtLevelSettings()), 1.0)
    }

    @Test
    fun staticBoostIsCapped() {
        // -40 LUFS needs +26 dB: only +12 are given
        val out = levelled(48000, -40.0, YtLevelSettings(dynamic = false))
        assertEquals(-28.0, out, 1.0)
    }

    @Test
    fun quietPassagesAreLifted() {
        val rate = 48000
        val lev = YtLeveller(rate, 2).apply { reset() }
        // a long normal part, then a passage 15 LU quieter
        val loud = sine(rate, 30.0, ampFor(rate, -14.0), 440.0)
        lev.process(loud, loud.size / 2)
        val quiet = sine(rate, 15.0, ampFor(rate, -29.0), 440.0)
        lev.process(quiet, quiet.size / 2)
        val out = loudness(rate, quiet, quiet.size / 2 - rate * 5)
        // lifted by about half the gap, within the limit
        assertTrue("out=$out", out > -25.0 && out < -19.0)
        assertTrue(lev.dynamicDb <= YtLeveller.MAX_DYNAMIC_BOOST + 1e-9)
    }

    @Test
    fun limiterHoldsCeilingOnBoostedSquare() {
        val rate = 48000
        val lev = YtLeveller(rate, 2).apply { settings = YtLevelSettings(enabled = false, trimDb = 6.0); reset() }
        val n = rate * 3
        val data = FloatArray(n * 2) { i -> if ((i / 2 / 50) % 2 == 0) 1f else -1f }
        lev.process(data, n)
        val ceiling = 10.0.pow(-1.0 / 20.0)
        var peak = 0f
        for (v in data) peak = max(peak, abs(v))
        assertTrue("peak=$peak", peak <= ceiling + 1e-6)
        // still loud, not squashed
        val tail = data.copyOfRange(data.size - rate, data.size)
        assertTrue(tail.maxOf { abs(it) } > 0.6f)
    }

    @Test
    fun limiterCatchesPeaksBetweenSamples() {
        // a sine at a quarter of the rate, 45° off: samples at 0.707 of the real peak
        val rate = 48000
        val lim = YtPeakLimiter(rate, 1)
        val n = rate
        val amp = 1.2
        val data = FloatArray(n) { (amp * sin(PI / 2 * it + PI / 4)).toFloat() }
        val out = FloatArray(n)
        for (i in 0 until n) lim.process(data, i, out, i)
        val samplePeak = out.copyOfRange(rate / 2, n).maxOf { abs(it) }
        // the true peak (samplePeak · √2) stays near the ceiling, not just the samples
        val truePeak = samplePeak * Math.sqrt(2.0)
        assertTrue("true peak $truePeak", truePeak <= lim.ceiling * 1.03)
        assertTrue("true peak $truePeak", truePeak > lim.ceiling * 0.9)
    }

    @Test
    fun limiterGainNeverExceedsWhatAnySampleNeeds() {
        val rate = 44100
        val lim = YtPeakLimiter(rate, 2)
        val r = Random(7)
        val n = rate * 2
        val data = FloatArray(n * 2) { (r.nextGaussian() * 0.6 * (if ((it / 4410) % 3 == 0) 3 else 1)).toFloat() }
        val out = FloatArray(n * 2)
        for (f in 0 until n) lim.process(data, f * 2, out, f * 2)
        // apart from the hard ceiling the limiter only scales: out = in · gain, gain ≤ 1
        for (f in lim.delay until n) {
            for (c in 0..1) {
                val x = data[(f - lim.delay) * 2 + c]
                val y = out[f * 2 + c]
                assertTrue(abs(y) <= lim.ceiling + 1e-6)
                assertTrue(abs(y) <= abs(x) + 1e-6)
            }
        }
    }

    @Test
    fun offIsPassthroughDelayed() {
        assertTrue(!YtLevelSettings(enabled = false).active)
        assertTrue(YtLevelSettings(enabled = false, trimDb = -3.0).active)
        // even while running (switched off mid-video) the sound is only delayed, bit for bit
        val rate = 48000
        val lev = YtLeveller(rate, 2).apply { settings = YtLevelSettings(enabled = false); reset() }
        val input = sine(rate, 2.0, 0.5, 300.0)
        val data = input.copyOf()
        lev.process(data, data.size / 2)
        val d = lev.limiter.delay
        for (i in d * 2 until data.size) assertEquals(input[i - d * 2], data[i], 0f)
        val tail = lev.drain()
        assertEquals(d * 2, tail.size)
        assertEquals(input[input.size - 1], tail[tail.size - 1], 0f)
    }

    @Test
    fun noNaNFromBrokenInput() {
        val rate = 48000
        val lev = YtLeveller(rate, 2).apply { reset() }
        val r = Random(3)
        val data = FloatArray(rate * 4) {
            when (r.nextInt(50)) {
                0 -> Float.NaN
                1 -> Float.POSITIVE_INFINITY
                2 -> Float.NEGATIVE_INFINITY
                else -> (r.nextGaussian() * 0.3).toFloat()
            }
        }
        lev.process(data, data.size / 2)
        val silence = FloatArray(rate * 10)
        lev.process(silence, silence.size / 2)
        for (v in data) assertTrue(v.isFinite())
        for (v in silence) assertTrue(v.isFinite())
        assertTrue(lev.staticDb.isFinite() && lev.dynamicDb.isFinite())
    }

    @Test
    fun gainMovesWithoutJumps() {
        val rate = 48000
        val lev = YtLeveller(rate, 2).apply { reset() }
        val frame = FloatArray(2)
        var last = lev.currentGain
        var maxStep = 0f
        val amp = ampFor(rate, -30.0).toFloat()
        for (i in 0 until rate * 20) {
            when (i) {
                rate * 5 -> lev.settings = lev.settings.copy(trimDb = 6.0)
                rate * 10 -> lev.settings = lev.settings.copy(targetLufs = -16.0, trimDb = -10.0)
                rate * 15 -> lev.settings = lev.settings.copy(enabled = false)
            }
            val v = (amp * sin(2 * PI * 440 * i / rate)).toFloat()
            frame[0] = v; frame[1] = v
            lev.process(frame, 1)
            val g = lev.currentGain
            maxStep = max(maxStep, abs(g - last) / max(g, last))
            last = g
        }
        // under 0.05 % per sample: a full trim step spreads over at least 100 ms
        assertTrue("step=$maxStep", maxStep < 5e-4f)
    }

    @Test
    fun seekKeepsWhatWasLearnt() {
        val rate = 48000
        val lev = YtLeveller(rate, 2).apply { settings = YtLevelSettings(dynamic = false); reset() }
        val data = sine(rate, 30.0, ampFor(rate, -24.0), 440.0)
        lev.process(data, data.size / 2)
        val before = lev.staticDb
        lev.flush()
        assertEquals(before, lev.staticDb, 1e-9)
        lev.reset()
        assertEquals(0.0, lev.staticDb, 1e-9)
    }
}
