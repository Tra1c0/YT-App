package com.github.libretube.ui.yt.sound

import com.github.libretube.helpers.YtAdaptiveRange
import com.github.libretube.helpers.YtLoudnessMeter
import com.github.libretube.helpers.YtOnsetDetector
import com.github.libretube.helpers.YtSoundFeatures
import com.github.libretube.helpers.YtSpectrum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class YtSoundAnalysisTest {

    // ---- envelopes -------------------------------------------------------------------------

    @Test
    fun envelopeRisesFasterThanItFalls() {
        val env = YtEnvelope(attackS = 0.03f, releaseS = 0.2f, easeS = 0.03f)
        val dt = 1 / 60f
        var t = 0f
        while (t < 0.1f) { env.step(1f, dt); t += dt }
        val risen = env.value
        assertTrue("rises most of the way in 100 ms: $risen", risen > 0.75f)
        t = 0f
        while (t < 0.1f) { env.step(0f, dt); t += dt }
        val fallen = risen - env.value
        assertTrue("falls less in the same time: $fallen < $risen", fallen < risen * 0.6f)
        repeat(300) { env.step(0f, dt) }
        assertTrue(env.value < 0.001f)
    }

    @Test
    fun envelopeIsFrameRateIndependent() {
        val a = YtEnvelope(0.04f, 0.2f, 0.04f)
        val b = YtEnvelope(0.04f, 0.2f, 0.04f)
        // 0.25 s up, 0.25 s down, at 60 and 120 fps
        repeat(15) { a.step(1f, 1 / 60f) }
        repeat(30) { b.step(1f, 1 / 120f) }
        assertEquals(a.value, b.value, 0.03f)
        repeat(15) { a.step(0f, 1 / 60f) }
        repeat(30) { b.step(0f, 1 / 120f) }
        assertEquals(a.value, b.value, 0.03f)
    }

    // ---- adaptive normalisation ------------------------------------------------------------

    /** Feeds a level that swings ±[depth] dB around [centre] at 2 Hz for [seconds]; min/max of the last 4 s. */
    private fun swing(range: YtAdaptiveRange, centre: Double, depth: Double, seconds: Double = 20.0): Pair<Float, Float> {
        val dt = 512.0 / 44100
        var t = 0.0
        var lo = 1f
        var hi = 0f
        while (t < seconds) {
            val x = range.map(centre + depth * sin(2 * PI * 2 * t), dt)
            if (t > seconds - 4) {
                lo = minOf(lo, x)
                hi = maxOf(hi, x)
            }
            t += dt
        }
        return lo to hi
    }

    @Test
    fun quietAndLoudSoundUseTheSameRange() {
        val (quietLo, quietHi) = swing(YtAdaptiveRange(18.0, 40.0, -50.0), centre = -42.0, depth = 8.0)
        val (loudLo, loudHi) = swing(YtAdaptiveRange(18.0, 40.0, -50.0), centre = -14.0, depth = 8.0)
        assertTrue("quiet reaches the top: $quietHi", quietHi > 0.9f)
        assertTrue("loud reaches the top: $loudHi", loudHi > 0.9f)
        assertEquals(quietLo, loudLo, 0.05f)
        assertEquals(quietHi, loudHi, 0.05f)
        // a 16 dB swing is less than the minimum span (18 dB): never the whole range
        assertTrue("dips stay above zero: $loudLo", loudLo > 0.0f)
    }

    @Test
    fun steadyToneDoesNotSwing() {
        val (lo, hi) = swing(YtAdaptiveRange(18.0, 40.0, -50.0), centre = -20.0, depth = 0.5)
        assertTrue("a ±0.5 dB tone moves little: $lo..$hi", hi - lo < 0.08f)
    }

    @Test
    fun nearSilenceIsNotBlownUp() {
        val range = YtAdaptiveRange(18.0, 40.0, ceilingMinDb = -50.0)
        var x = 0f
        repeat(2000) { x = range.map(-75.0, 0.0116) }
        assertTrue("hiss at -75 dB stays at zero: $x", x < 0.01f)
    }

    @Test
    fun rangeFollowsAQuieterVideo() {
        val range = YtAdaptiveRange(18.0, 40.0, -50.0)
        val dt = 512.0 / 44100
        repeat((10 / dt).toInt()) { range.map(-10.0, dt) }
        var x = 0f
        repeat((12 / dt).toInt()) { x = range.map(-30.0, dt) }
        assertTrue("20 dB quieter is back near the top after 12 s: $x", x > 0.8f)
    }

    // ---- transient detector ----------------------------------------------------------------

    @Test
    fun onsetFiresOnAHitButNotOnSteadySound() {
        val det = YtOnsetDetector(16)
        val rnd = Random(3)
        val db = DoubleArray(16)
        val dt = 512.0 / 44100
        var steadyMax = 0f
        repeat(300) {
            for (b in 0 until 16) db[b] = -30.0 + rnd.nextDouble(-1.0, 1.0)
            val o = det.step(db, true, dt)
            if (it > 50) steadyMax = maxOf(steadyMax, o)
        }
        assertTrue("steady noise gives no onset: $steadyMax", steadyMax < 0.2f)
        // a hit: +18 dB in all bands
        var hit = 0f
        repeat(3) {
            for (b in 0 until 16) db[b] = -12.0
            hit = maxOf(hit, det.step(db, true, dt))
        }
        assertTrue("a hit gives a strong onset: $hit", hit > 0.8f)
    }

    @Test
    fun noOnsetRightAfterARestartOrInSilence() {
        val det = YtOnsetDetector(16)
        val quiet = DoubleArray(16) { -40.0 }
        val loud = DoubleArray(16) { -10.0 }
        repeat(100) { det.step(quiet, true, 0.0116) }
        det.restart()
        // after a seek the sound is suddenly louder: not a hit of the music
        assertEquals(0f, det.step(loud, true, 0.0116))
        assertEquals(0f, det.step(loud, true, 0.0116))
        assertEquals(0f, det.step(loud, true, 0.0116), 0.01f)
        assertEquals(0f, det.step(quiet, false, 0.0116))
    }

    // ---- features from real spectra --------------------------------------------------------

    private fun featuresOf(hz: Double, amplitude: Double, frames: Int = 120): FloatArray {
        val rate = 44100
        val spectrum = YtSpectrum(rate)
        val features = YtSoundFeatures(rate)
        val ring = FloatArray(YtSpectrum.N)
        val levels = FloatArray(YtSpectrum.BANDS)
        val out = FloatArray(YtSoundFeatures.COUNT)
        var n = 0L
        repeat(frames) {
            repeat(YtSpectrum.HOP) {
                ring[(n % YtSpectrum.N).toInt()] = (amplitude * sin(2 * PI * hz * n / rate)).toFloat()
                n++
            }
            spectrum.analyse(ring, (n % YtSpectrum.N).toInt(), levels)
            features.analyse(spectrum.bandPower, spectrum.frameSeconds, out)
        }
        return out
    }

    @Test
    fun bandsGoToTheirGroups() {
        val bass = featuresOf(80.0, 0.3)
        assertTrue("80 Hz is bass: ${bass.toList()}", bass[YtSoundFeatures.BASS] > 0.5f && bass[YtSoundFeatures.HIGH] < 0.1f)
        val mid = featuresOf(800.0, 0.3)
        assertTrue("800 Hz is mid: ${mid.toList()}", mid[YtSoundFeatures.MID] > 0.5f && mid[YtSoundFeatures.BASS] < 0.1f)
        val high = featuresOf(5000.0, 0.3)
        assertTrue("5 kHz is high: ${high.toList()}", high[YtSoundFeatures.HIGH] > 0.5f && high[YtSoundFeatures.BASS] < 0.1f)
        assertEquals(1f, high[YtSoundFeatures.AUDIBLE])
        val silent = featuresOf(1000.0, 0.0)
        assertEquals(0f, silent[YtSoundFeatures.AUDIBLE])
        assertEquals(0f, silent[YtSoundFeatures.LEVEL])
    }

    @Test
    fun groupsSplitAt250And2000Hz() {
        val features = YtSoundFeatures(44100)
        val edges = YtSpectrum.edges(44100)
        for (b in 0 until YtSpectrum.BANDS) {
            val centre = YtSpectrum.erbToHz((YtSpectrum.erb(edges[b]) + YtSpectrum.erb(edges[b + 1])) / 2)
            val expected = if (centre < 250) 0 else if (centre < 2000) 1 else 2
            assertEquals("band $b at $centre Hz", expected, features.groupOf(b))
        }
    }

    @Test
    fun kWeightingMatchesBs1770() {
        fun db(hz: Double) = 10 * log10(YtLoudnessMeter.kWeightPower(hz, 48000.0))
        // the K curve: about +0.7 dB at 1 kHz, +4 dB in the treble, falling off below 100 Hz
        assertEquals(0.7, db(1000.0), 0.3)
        assertEquals(4.0, db(10000.0), 0.4)
        assertTrue(db(30.0) < -1.0)
    }

    // ---- punch and rate limits -------------------------------------------------------------

    @Test
    fun punchDecaysWithin250ms() {
        val punch = YtPunch()
        val dt = 1 / 60f
        var peak = 0f
        punch.step(1f, dt)
        var t = dt
        while (t < 0.05f) { peak = maxOf(peak, punch.step(0f, dt)); t += dt }
        assertTrue("fast attack: $peak", peak > 0.7f)
        while (t < 0.25f) { punch.step(0f, dt); t += dt }
        assertTrue("decayed: ${punch.value}", punch.value < 0.1f)
    }

    @Test
    fun fullPunchesAtMostEvery330ms() {
        val punch = YtPunch()
        val dt = 1 / 120f
        var t = 0f
        var lastFull = -1f
        var wasHigh = false
        var count = 0
        // an onset in every frame for 5 s, the worst case
        while (t < 5f) {
            val v = punch.step(1f, dt)
            val high = v > 0.9f
            if (high && !wasHigh) {
                if (lastFull >= 0f) assertTrue("full punches ${t - lastFull} s apart", t - lastFull >= 0.33f - 1e-3f)
                lastFull = t
                count++
            }
            wasHigh = high
            t += dt
        }
        assertTrue("at most about 3 per second: $count", count <= 16)
    }

    /**
     * Turning points of [values] (sampled every [dt]) as a zigzag with [swing]: returns the time
     * of each confirmed extreme (the last moment it was held).
     */
    private fun turningPoints(values: List<Float>, dt: Float, swing: Float = YtSwingGuard.SWING): List<Float> {
        val turns = mutableListOf<Float>()
        var dir = 0
        var extreme = values.first()
        var extremeTime = 0f
        var start = values.first()
        for ((i, v) in values.withIndex()) {
            val t = i * dt
            when (dir) {
                0 -> if (abs(v - start) >= swing) {
                    dir = if (v > start) 1 else -1
                    extreme = v
                    extremeTime = t
                }
                else -> {
                    if ((v - extreme) * dir >= 0f) {
                        extreme = v
                        extremeTime = t
                    } else if (abs(extreme - v) >= swing) {
                        turns += extremeTime
                        dir = -dir
                        extreme = v
                        extremeTime = t
                    }
                }
            }
        }
        return turns
    }

    private fun assertNoFastFlashes(values: List<Float>, dt: Float, what: String) {
        val turns = turningPoints(values, dt)
        for (i in 1 until turns.size) {
            val gap = turns[i] - turns[i - 1]
            // each transition of ≥ 0.1 lasts at least 1/6 s: a flash (two of them) ≥ 1/3 s
            assertTrue("$what: turns ${turns[i - 1]} → ${turns[i]} only $gap s apart", gap >= 1 / 6f - 1e-4f)
        }
        // and in any 1 s window at most 3 flashes = 6 transitions (7 turning points)
        for (i in turns.indices) {
            val inWindow = turns.count { it >= turns[i] && it < turns[i] + 1f }
            assertTrue("$what: $inWindow turns within 1 s from ${turns[i]}", inWindow <= 7)
        }
    }

    @Test
    fun guardStopsFastSwingsAtAnyFrameRate() {
        for (fps in listOf(30, 60, 90, 120)) {
            val dt = 1f / fps
            for (seed in 1..6) {
                val rnd = Random(seed)
                val guard = YtSwingGuard()
                val out = ArrayList<Float>()
                repeat(fps * 20) {
                    // worst cases: random jumps, square waves of 5..15 Hz
                    val x = if (seed % 2 == 0) {
                        rnd.nextFloat()
                    } else {
                        val hz = 5 + seed
                        if (sin(2 * PI * hz * it * dt) > 0) 1f else 0f
                    }
                    out += guard.step(x, dt)
                }
                assertNoFastFlashes(out, dt, "guard fps=$fps seed=$seed")
            }
        }
    }

    @Test
    fun guardLetsSlowMovesThroughUnchanged() {
        val guard = YtSwingGuard()
        val dt = 1 / 60f
        var maxError = 0f
        repeat(600) {
            // a 1 Hz sine 0..1: turns are 0.5 s apart
            val x = (0.5 + 0.5 * sin(2 * PI * it * dt)).toFloat()
            val y = guard.step(x, dt)
            if (it > 60) maxError = maxOf(maxError, abs(x - y))
        }
        assertTrue("slow motion passes: max error $maxError", maxError < 0.05f)
    }

    @Test
    fun shaperOutputHasNoFastFlashes() {
        for (fps in listOf(30, 60, 120)) {
            val dt = 1f / fps
            val rnd = Random(fps)
            val shaper = YtSoundShaper()
            val fields = List(YtSoundShaper.FIELDS) { ArrayList<Float>() }
            repeat(fps * 20) {
                // hostile input: every value jumps at random each frame, onsets all the time
                shaper.step(dt, true, rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
                for (f in 0 until YtSoundShaper.FIELDS) fields[f] += shaper.out[f]
            }
            for (f in 0 until YtSoundShaper.FIELDS) assertNoFastFlashes(fields[f], dt, "field $f fps=$fps")
        }
    }

    @Test
    fun shaperFadesToSilentWhenStopped() {
        val shaper = YtSoundShaper()
        val dt = 1 / 60f
        repeat(120) { shaper.step(dt, true, 0.8f, 0.7f, 0.6f, 0.5f, 0f) }
        assertTrue(shaper.playing)
        assertTrue(shaper.out[YtSoundShaper.LEVEL] > 0.6f)
        var t = 0f
        var halfway = 1f
        while (!shaper.settled && t < 2f) {
            shaper.step(dt, false, 0f, 0f, 0f, 0f, 0f)
            t += dt
            if (abs(t - 0.15f) < dt / 2) halfway = shaper.out[YtSoundShaper.LEVEL]
        }
        assertTrue("fades, not cuts: $halfway at 150 ms", halfway > 0.05f && halfway < 0.6f)
        assertTrue("silent after $t s", shaper.settled && t <= 0.5f)
        assertEquals(false, shaper.playing)
        assertEquals(YtSoundFrame.SILENT, shaper.frame(YtSoundFrame(0.1f, 0f, 0f, 0f, 0f, true)))
    }

    @Test
    fun shaperFadesInWithoutAJump() {
        val shaper = YtSoundShaper()
        val dt = 1 / 60f
        var last = 0f
        var biggest = 0f
        repeat(60) {
            shaper.step(dt, true, 1f, 1f, 1f, 1f, 0f)
            val v = shaper.out[YtSoundShaper.LEVEL]
            biggest = maxOf(biggest, v - last)
            last = v
        }
        assertTrue("no step larger than 0.15 per frame: $biggest", biggest < 0.15f)
        assertTrue(last > 0.9f)
    }

    @Test
    fun playingNeedsSound() {
        val shaper = YtSoundShaper()
        val dt = 1 / 60f
        repeat(120) { shaper.step(dt, true, 0f, 0f, 0f, 0f, 0f) }
        assertEquals("active but silent", false, shaper.playing)
        repeat(60) { shaper.step(dt, true, 0.5f, 0f, 0f, 0f, 0f) }
        assertEquals(true, shaper.playing)
    }

    @Test
    fun motionValuesFollowFasterThanTheGuardedOnes() {
        // a square wave of 6 Hz on the bass: the guard holds it to ≤ 3 swings a second, the
        // motion values (before the guard) show every hit
        val shaper = YtSoundShaper()
        val dt = 1 / 120f
        val guarded = ArrayList<Float>()
        val motion = ArrayList<Float>()
        repeat(120 * 6) {
            val on = sin(2 * PI * 6 * it * dt) > 0
            shaper.step(dt, true, 0.7f, if (on) 1f else 0.2f, 0.5f, 0.5f, 0f)
            if (it > 120) {
                guarded += shaper.out[YtSoundShaper.BASS]
                motion += shaper.motion[YtSoundShaper.BASS]
            }
        }
        val fast = turningPoints(motion, dt).size
        val slow = turningPoints(guarded, dt).size
        assertTrue("motion turns $fast, guarded $slow", fast > slow)
        assertNoFastFlashes(guarded, dt, "guarded bass")
        // both rest after a stop
        repeat(120) { shaper.step(dt, false, 0f, 0f, 0f, 0f, 0f) }
        assertTrue(shaper.settled)
        assertTrue(shaper.motion.all { it == 0f })
    }

    @Test
    fun bandShaperRisesQuicklyAndRestsInSilence() {
        val bands = YtBandShaper(16)
        val loud = FloatArray(16) { 0.9f }
        val dt = 1 / 60f
        repeat(12) { bands.step(dt, true, loud, 1f) }
        assertTrue("risen to ${bands.out[3]} in 200 ms", bands.out[3] > 0.8f)
        repeat(60) { bands.step(dt, false, loud, 0f) }
        assertTrue(bands.settled)
        // values outside 0..1 and a short input array stay safe
        bands.step(dt, true, floatArrayOf(5f, -2f, Float.NaN), 1f)
        assertTrue(bands.out.all { it in 0f..1f })
    }

    @Test
    fun statsCountFramesAndKeepTheLoudestOfTenSeconds() {
        val stats = YtSoundStats()
        assertEquals(0, stats.fps())
        assertEquals(0f, stats.max(0), 0f)
        val second = 1_000_000_000L
        val start = 100 * second
        // 60 frames a second for 12 s; a loud bass only in the first second
        for (s in 0 until 12) {
            for (f in 0 until 60) {
                val t = start + s * second + f * second / 60
                stats.frame(t, 0.5f, if (s == 0) 1f else 0.3f, 0.2f, 0.1f * (s % 3))
            }
        }
        assertEquals(60, stats.fps())
        assertEquals(0.5f, stats.max(0), 0f)
        assertEquals("the loud second is older than 10 s", 0.3f, stats.max(1), 0f)
        assertEquals(0.2f, stats.max(3), 1e-6f)
    }
}
