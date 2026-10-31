package com.github.libretube.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The wave's analysis against synthetic sound: tones, sweeps, vowels with the formants of
 * Peterson & Barney (1952, medians), a hiss, speech over music, silence. Pass marks as in the
 * Python prototype the design was tuned with.
 */
class YtSpectrumTest {
    private val n = YtSpectrum.N
    private val hop = YtSpectrum.HOP
    private val bands = YtSpectrum.BANDS

    /** Frames of [x] as the tap makes them; frame i is centred on sample i·HOP of [x]. */
    private fun run(x: FloatArray, fs: Int, levels: ArrayList<DoubleArray>? = null): Array<FloatArray> {
        val spectrum = YtSpectrum(fs)
        val ring = FloatArray(n)
        var pos = 0
        var counted = 0L
        val frames = ArrayList<FloatArray>()
        val padded = FloatArray(n / 2) + x + FloatArray(n / 2)
        for (v in padded) {
            ring[pos] = v
            pos = (pos + 1) and (n - 1)
            counted++
            if (counted >= n && counted % hop == 0L) {
                val out = FloatArray(bands)
                spectrum.analyse(ring, pos, out)
                frames += out
                levels?.add(spectrum.levelDb.copyOf())
            }
        }
        return frames.toTypedArray()
    }

    private fun sampleAt(frames: Array<FloatArray>, seconds: Double, fs: Int): FloatArray {
        val phi = seconds * fs / hop
        val i = floor(phi).toInt().coerceIn(0, frames.size - 1)
        if (i + 1 >= frames.size) return frames[i]
        val f = (phi - i).toFloat()
        return FloatArray(bands) { frames[i][it] * (1 - f) + frames[i + 1][it] * f }
    }

    private fun bandOf(edges: DoubleArray, hz: Double): Int {
        var b = 0
        while (b < bands - 1 && hz >= edges[b + 1]) b++
        return b
    }

    private fun mean(frames: Array<FloatArray>, fs: Int, from: Double, to: Double): FloatArray {
        val out = FloatArray(bands)
        var count = 0
        frames.forEachIndexed { i, f ->
            val t = i.toDouble() * hop / fs
            if (t > from && t < to) {
                for (b in 0 until bands) out[b] += f[b]
                count++
            }
        }
        if (count > 0) for (b in 0 until bands) out[b] /= count
        return out
    }

    // ---- signals

    private fun amp(db: Double) = 10.0.pow(db / 20)

    private fun sine(fs: Int, hz: Double, seconds: Double, db: Double, phase: Double = 0.0) =
        FloatArray((fs * seconds).toInt()) { (amp(db) * sin(2 * PI * hz * it / fs + phase)).toFloat() }

    private fun sweep(fs: Int, f0: Double, f1: Double, seconds: Double, db: Double): Pair<FloatArray, DoubleArray> {
        val k = ln(f1 / f0)
        val count = (fs * seconds).toInt()
        val x = FloatArray(count) {
            val t = it.toDouble() / fs
            (amp(db) * sin(2 * PI * f0 * seconds / k * (exp(t / seconds * k) - 1))).toFloat()
        }
        val f = DoubleArray(count) { f0 * exp(it.toDouble() / fs / seconds * k) }
        return x to f
    }

    private fun rmsTo(x: FloatArray, db: Double): FloatArray {
        val rms = sqrt(x.sumOf { it.toDouble() * it } / x.size)
        return FloatArray(x.size) { (x[it] / rms * amp(db)).toFloat() }
    }

    /** Pink noise by Paul Kellet's filter on white noise. */
    private fun pink(fs: Int, seconds: Double, db: Double, seed: Long = 1): FloatArray {
        val r = Random(seed)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        val x = FloatArray((fs * seconds).toInt()) {
            val w = r.nextGaussian()
            b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520; b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980
            val v = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
            v.toFloat()
        }
        return rmsTo(x, db)
    }

    private fun kicks(fs: Int, seconds: Double, rateHz: Double = 2.0, db: Double = -6.0): FloatArray {
        val x = FloatArray((fs * seconds).toInt())
        var start = 0.0
        while (start < seconds) {
            val i0 = (start * fs).toInt()
            for (k in 0 until (0.25 * fs).toInt()) {
                if (i0 + k >= x.size) break
                val t = k.toDouble() / fs
                x[i0 + k] += (amp(db) * sin(2 * PI * 60 * t) * exp(-t / 0.06)).toFloat()
            }
            start += 1 / rateHz
        }
        return x
    }

    /** A two-pole formant resonator (Klatt), unity gain at DC. */
    private fun resonate(x: DoubleArray, fs: Int, hz: Double, bw: Double): DoubleArray {
        val t = 1.0 / fs
        val c = -exp(-2 * PI * bw * t)
        val b = 2 * exp(-PI * bw * t) * cos(2 * PI * hz * t)
        val a = 1 - b - c
        val y = DoubleArray(x.size)
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val v = a * x[i] + b * y1 + c * y2
            y[i] = v
            y2 = y1
            y1 = v
        }
        return y
    }

    private fun vowel(fs: Int, f0: Double, formants: DoubleArray, seconds: Double, db: Double): FloatArray {
        val count = (fs * seconds).toInt()
        var src = DoubleArray(count)
        var k = 0.0
        while (k < count) {
            src[k.toInt()] = 1.0
            k += fs / f0
        }
        val a = exp(-2 * PI * 100 / fs)
        repeat(2) {
            var acc = 0.0
            src = DoubleArray(count) { i -> acc = (1 - a) * src[i] + a * acc; acc }
        }
        src = DoubleArray(count) { i -> src[i] - if (i > 0) src[i - 1] else 0.0 }
        var y = src
        val all = formants + doubleArrayOf(3500.0, 4500.0)
        val widths = doubleArrayOf(80.0, 100.0, 150.0, 250.0, 300.0)
        for (i in all.indices) y = resonate(y, fs, all[i], widths[i])
        return rmsTo(FloatArray(count) { y[it].toFloat() }, db)
    }

    private fun edged(x: FloatArray, fs: Int): FloatArray {
        val ramp = (0.01 * fs).toInt()
        for (i in 0 until ramp) {
            val e = (0.5 - 0.5 * cos(PI * i / ramp)).toFloat()
            x[i] *= e
            x[x.size - 1 - i] *= e
        }
        return x
    }

    private data class Mark(val from: Double, val to: Double, val index: Int)

    private fun syllables(fs: Int, f0: Double, seq: List<DoubleArray>, db: Double = -24.0): Pair<FloatArray, List<Mark>> {
        var x = FloatArray(0)
        val marks = ArrayList<Mark>()
        var t = 0.0
        seq.forEachIndexed { i, fm ->
            x += edged(vowel(fs, f0, fm, 0.18, db), fs) + FloatArray((0.07 * fs).toInt())
            marks += Mark(t, t + 0.18, i)
            t += 0.25
        }
        return x to marks
    }

    /** /s/: white noise through a 4th order Butterworth high-pass at 4 kHz. */
    private fun hiss(fs: Int, seconds: Double, db: Double, seed: Long = 7): FloatArray {
        val r = Random(seed)
        var x = DoubleArray((fs * seconds).toInt()) { r.nextGaussian() }
        for (q in doubleArrayOf(0.5412, 1.3066)) {
            val w = 2 * PI * 4000 / fs
            val alpha = sin(w) / (2 * q)
            val cw = cos(w)
            val a0 = 1 + alpha
            val b0 = (1 + cw) / 2 / a0
            val b1 = -(1 + cw) / a0
            val b2 = (1 + cw) / 2 / a0
            val a1 = -2 * cw / a0
            val a2 = (1 - alpha) / a0
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            x = DoubleArray(x.size) { i ->
                val v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x[i]; y2 = y1; y1 = v
                v
            }
        }
        return edged(rmsTo(FloatArray(x.size) { x[it].toFloat() }, db), fs)
    }

    private val men = mapOf(
        "f0" to doubleArrayOf(129.5),
        "aa" to doubleArrayOf(720.0, 1096.0, 2435.0),
        "iy" to doubleArrayOf(265.0, 2295.0, 2900.0),
        "uw" to doubleArrayOf(312.5, 900.0, 2190.0),
    )
    private val women = mapOf(
        "f0" to doubleArrayOf(222.0),
        "aa" to doubleArrayOf(850.0, 1225.0, 2800.0),
        "iy" to doubleArrayOf(308.5, 2780.0, 3300.0),
        "uw" to doubleArrayOf(380.0, 957.5, 2645.0),
    )

    // ---- tests

    @Test
    fun bandEdgesFollowTheErbScale() {
        val e = YtSpectrum.edges(48000)
        val expected = doubleArrayOf(
            50.0, 124.3, 218.5, 337.7, 488.7, 680.0, 922.3, 1229.1, 1617.7,
            2110.0, 2733.4, 3523.0, 4523.1, 5789.9, 7394.2, 9426.3, 12000.0,
        )
        for (i in expected.indices) assertEquals("edge $i", expected[i], e[i], 0.15)
        val e44 = YtSpectrum.edges(44100)
        for (i in expected.indices) assertEquals("edge $i at 44.1 kHz", e[i], e44[i], 1e-9)
    }

    @Test
    fun fullScaleSineReadsZeroDecibels() {
        for (fs in intArrayOf(44100, 48000)) {
            val spectrum = YtSpectrum(fs)
            val x = sine(fs, 1000.0, n.toDouble() / fs, 0.0)
            spectrum.analyse(x, 0, FloatArray(bands))
            val b = bandOf(YtSpectrum.edges(fs), 1000.0)
            assertEquals("1 kHz at full scale, fs $fs", 0.0, spectrum.levelDb[b], 0.1)
        }
    }

    @Test
    fun sweepLightsTheRightBand() {
        for (fs in intArrayOf(44100, 48000)) {
            val (x, f) = sweep(fs, 40.0, 12000.0, 12.0, -20.0)
            val frames = run(x, fs)
            val edges = YtSpectrum.edges(fs)
            var exact = 0
            var near = 0
            var count = 0
            var far = 0.0
            frames.forEachIndexed { i, v ->
                val c = i * hop
                if (c >= x.size || c < fs * 0.2) return@forEachIndexed
                val hz = f[c]
                if (hz < edges[0] || hz > edges[bands]) return@forEachIndexed
                val b = bandOf(edges, hz)
                val top = v.indices.maxBy { v[it] }
                count++
                if (top == b) exact++
                if (abs(top - b) <= 1) near++
                far += v.indices.filter { abs(it - b) >= 2 }.maxOf { v[it] }
            }
            println("sweep fs $fs: loudest band right ${100 * exact / count}%, within one ${100 * near / count}%, far bands ${far / count}")
            assertTrue("loudest band right in >= 85% (fs $fs)", exact >= 0.85 * count)
            assertTrue("loudest band within one always (fs $fs)", near == count)
            assertTrue("bands two or more away stay small (fs $fs)", far / count <= 0.05)
        }
    }

    @Test
    fun twoTonesStaySeparate() {
        val fs = 48000
        val edges = YtSpectrum.edges(fs)
        for ((f1, f2) in listOf(120.0 to 480.0, 250.0 to 1000.0, 1000.0 to 3000.0, 2000.0 to 6000.0)) {
            val a = sine(fs, f1, 3.0, -20.0)
            val b = sine(fs, f2, 3.0, -20.0, 0.3)
            val frames = run(FloatArray(a.size) { a[it] + b[it] }, fs)
            val v = mean(frames, fs, 2.0, 3.0)
            val b1 = bandOf(edges, f1)
            val b2 = bandOf(edges, f2)
            val valley = (b1 + 1 until b2).minOf { v[it] }
            println("two tones $f1 + $f2: ${v[b1]}, ${v[b2]}, valley $valley")
            assertTrue("$f1 Hz shows", v[b1] >= 0.9f)
            assertTrue("$f2 Hz shows", v[b2] >= 0.9f)
            assertTrue("the bands between stay low", valley <= 0.1f)
        }
    }

    @Test
    fun vowelsShowTheirFormantsAndEachSyllable() {
        val fs = 48000
        val edges = YtSpectrum.edges(fs)
        var formantsFound = 0
        var formantsTotal = 0
        for (voice in listOf(men, women)) {
            val seq = List(4) { listOf(voice["aa"]!!, voice["iy"]!!, voice["uw"]!!) }.flatten()
            val (x, marks) = syllables(fs, voice["f0"]!![0], seq)
            val frames = run(x, fs)
            val speech = (0 until bands).filter { edges[it] >= 100 && edges[it + 1] <= 4000 }
            val depths = ArrayList<Float>()
            val profiles = Array(3) { FloatArray(bands) }
            for (m in marks.drop(3)) {
                val on = mean(frames, fs, m.from + 0.05, m.to - 0.03)
                val off = mean(frames, fs, m.to + 0.03, m.to + 0.07)
                depths += speech.maxOf { on[it] - off[it] }
                for (b in 0 until bands) profiles[m.index % 3][b] += on[b] / 3
            }
            val depth = depths.average()
            println("syllable depth ${depth}")
            assertTrue("each syllable shows: depth $depth", depth >= 0.5)
            listOf("aa", "iy", "uw").forEachIndexed { i, name ->
                val p = profiles[i]
                val peaks = (0 until bands).filter {
                    p[it] >= p[max(it - 1, 0)] && p[it] >= p[minOf(it + 1, bands - 1)] && p[it] > 0.3f
                }
                for (hz in voice[name]!!.take(2)) {
                    val b = bandOf(edges, hz)
                    formantsTotal++
                    if (peaks.any { abs(it - b) <= 1 }) formantsFound++
                }
            }
        }
        println("formants found $formantsFound of $formantsTotal")
        assertTrue("F1 and F2 show as peaks", formantsFound >= 10)
    }

    @Test
    fun hissLightsTheHighBands() {
        val fs = 48000
        val edges = YtSpectrum.edges(fs)
        var x = FloatArray(0)
        val marks = ArrayList<Pair<Double, Boolean>>()
        var t = 0.0
        repeat(6) { k ->
            x += vowel(fs, men["f0"]!![0], if (k % 2 == 0) men["aa"]!! else men["iy"]!!, 0.18, -24.0)
            marks += t to false
            x += hiss(fs, 0.12, -32.0)
            marks += (t + 0.18) to true
            t += 0.30
        }
        val frames = run(x, fs)
        val high = (0 until bands).filter { edges[it] >= 4500 }
        var duringHiss = 0.0
        var duringVowel = 0.0
        var hisses = 0
        var vowels = 0
        for ((from, isHiss) in marks.drop(2)) {
            val len = if (isHiss) 0.12 else 0.18
            val m = mean(frames, fs, from + 0.04, from + len - 0.03)
            val level = high.map { m[it].toDouble() }.average()
            if (isHiss) { duringHiss += level; hisses++ } else { duringVowel += level; vowels++ }
        }
        val s = duringHiss / hisses
        val v = duringVowel / vowels
        println("high bands: hiss $s, vowel $v")
        assertTrue("a hiss lifts the high bands well above a vowel", s - v >= 0.5)
    }

    @Test
    fun speechOverMusicStillMoves() {
        val fs = 48000
        val seq = List(8) { listOf(men["aa"]!!, men["iy"]!!, men["uw"]!!) }.flatten()
        val (speech, marks) = syllables(fs, men["f0"]!![0], seq, -26.0)
        val pinkBed = pink(fs, speech.size.toDouble() / fs, -36.0)
        val drums = kicks(fs, speech.size.toDouble() / fs)
        val x = FloatArray(speech.size) { speech[it] + pinkBed[it] + drums[it] }
        val frames = run(x, fs)
        // as in the prototype: judged where the speech is at least 6 dB above the music
        val speechDb = ArrayList<DoubleArray>()
        val bedDb = ArrayList<DoubleArray>()
        run(speech, fs, speechDb)
        run(FloatArray(speech.size) { pinkBed[it] + drums[it] }, fs, bedDb)
        val sums = DoubleArray(bands)
        var count = 0
        speechDb.indices.forEach { i ->
            val t = i.toDouble() * hop / fs
            if (marks.any { t > it.from + 0.05 && t < it.to - 0.03 }) {
                for (b in 0 until bands) sums[b] += speechDb[i][b] - bedDb[i][b]
                count++
            }
        }
        val speechBands = (0 until bands).filter { sums[it] / count >= 6.0 }
        println("speech-dominant bands $speechBands")
        val depths = marks.drop(4).map { m ->
            val on = mean(frames, fs, m.from + 0.05, m.to - 0.03)
            val off = mean(frames, fs, m.to + 0.03, m.to + 0.07)
            speechBands.map { (on[it] - off[it]).toDouble() }.average()
        }
        println("speech over music depth ${depths.average()}")
        assertTrue("speech over music still moves", depths.average() >= 0.2)
    }

    @Test
    fun quietSpeechAfterLoudBassShowsSoon() {
        val fs = 48000
        val seq = List(10) { listOf(men["aa"]!!, men["iy"]!!, men["uw"]!!) }.flatten()
        val (speech, marks) = syllables(fs, men["f0"]!![0], seq, -35.0)
        val bass = sine(fs, 60.0, 3.0, -3.0)
        val noise = pink(fs, 3.0, -12.0)
        val x = FloatArray(bass.size) { bass[it] + noise[it] } + speech
        val frames = run(x, fs)
        val edges = YtSpectrum.edges(fs)
        val speechBands = (0 until bands).filter { edges[it] >= 300 && edges[it + 1] <= 4000 }
        val depths = marks.map { Mark(it.from + 3, it.to + 3, it.index) }
            .filter { it.from >= 4.5 && it.from < 6.0 }
            .map { m ->
                val on = mean(frames, fs, m.from + 0.05, m.to - 0.03)
                val off = mean(frames, fs, m.to + 0.03, m.to + 0.07)
                speechBands.map { (on[it] - off[it]).toDouble() }.average()
            }
        println("speech 1.5-3 s after loud bass: depth ${depths.average()}")
        assertTrue("quiet speech shows 1.5 to 3 s after loud bass", depths.average() >= 0.2)
    }

    @Test
    fun silenceShowsNothing() {
        val fs = 48000
        val zero = run(FloatArray(2 * fs), fs)
        assertEquals("digital silence", 0f, zero.maxOf { it.max() }, 0f)
        val r = Random(3)
        val dither = run(FloatArray(2 * fs) { (amp(-90.0) * r.nextGaussian()).toFloat() }, fs)
        assertEquals("dither at -90 dBFS", 0f, dither.maxOf { it.max() }, 0f)
        val room = run(pink(fs, 4.0, -60.0), fs)
        val roomMax = room.maxOf { it.max() }
        val roomLast = room.takeLast(fs / hop).maxOf { it.max() }
        println("room tone -60 dBFS: max $roomMax, last second $roomLast")
        // the prototype's mark was 0.4 with FFT-shaped pink noise; Kellet's filter gives a little
        // more bass, so the steady state is held to 0.4 and the first moments to 0.45
        assertTrue("room tone stays small", roomLast <= 0.4f && roomMax <= 0.45f)
    }

    @Test
    fun sameMomentLooksTheSameAt44And48Kilohertz() {
        val profiles = intArrayOf(44100, 48000).map { fs ->
            val (x, _) = sweep(fs, 40.0, 12000.0, 12.0, -20.0)
            val frames = run(x, fs)
            (50 until 1150).map { sampleAt(frames, it / 100.0, fs) }
        }
        var sum = 0.0
        var count = 0
        for (i in profiles[0].indices) for (b in 0 until bands) {
            sum += abs(profiles[0][i][b] - profiles[1][i][b])
            count++
        }
        println("44.1 vs 48 kHz mean difference ${sum / count}")
        assertTrue("sample rate does not change the picture", sum / count <= 0.01)
    }

    @Test
    fun sameSoundSamePictureEveryTime() {
        val fs = 44100
        val (x, _) = syllables(fs, men["f0"]!![0], listOf(men["aa"]!!, men["iy"]!!, men["uw"]!!))
        val a = run(x, fs)
        val b = run(x, fs)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertTrue("frame $i", a[i].contentEquals(b[i]))
    }

    @Test
    fun anOnsetShowsInTime() {
        val fs = 48000
        val x = FloatArray(fs) + sine(fs, 1000.0, 1.0, -20.0)
        val frames = run(x, fs)
        val b = bandOf(YtSpectrum.edges(fs), 1000.0)
        var half = Double.NaN
        var most = Double.NaN
        var t = 0.9
        while (t < 1.2) {
            val v = sampleAt(frames, t, fs)[b]
            if (half.isNaN() && v >= 0.5f) half = (t - 1) * 1000
            if (most.isNaN() && v >= 0.9f) most = (t - 1) * 1000
            t += 0.0005
        }
        println("onset: half at $half ms, 90% at $most ms")
        assertTrue("half way within 15 ms of the onset", abs(half) <= 15)
        assertTrue("90% within 30 ms after the onset", most <= 30)
    }
}
