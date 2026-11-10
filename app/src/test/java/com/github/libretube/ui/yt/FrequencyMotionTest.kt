package com.github.libretube.ui.yt

import com.github.libretube.ui.yt.FrequencyMotionMath as F
import com.github.libretube.ui.yt.FrequencyPulseMath as M
import com.github.libretube.ui.yt.sound.YtSoundShaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

/**
 * YT: Frequency's motion language: clearly visible motion (stretch, springs, morph, bars), the
 * brightness channels still at most 3 swings a second, and the tier picked per phone.
 */
class FrequencyMotionTest {
    private val frame = 1f / 60f

    // -- a beat through the whole chain -----------------------------------------------------

    /** A kick drum at [bpm]: bass jumps to 0.95 for 60 ms, else 0.75 (compressed pop music). */
    private fun bassAt(t: Float, bpm: Float = 120f): Float {
        val beat = 60f / bpm
        return if ((t % beat) < 0.06f) 0.95f else 0.75f
    }

    /**
     * The shaper (engine side) and the pulse's motion path for [seconds] of a beat; returns the
     * beat channel, and the scale of a button (growth 18 %, beat + bounce) into [scales].
     */
    private fun motionBeat(seconds: Float, dt: Float, scales: MutableList<Float> = ArrayList()): List<Float> {
        val shaper = YtSoundShaper()
        val stretch = FrequencyStretch()
        val kick = FrequencyKick()
        val spring = FrequencySpring()
        var beat = 0f
        val out = ArrayList<Float>()
        var t = 0f
        while (t < seconds) {
            val b = bassAt(t)
            shaper.step(dt, true, 0.8f, b, 0.6f, 0.5f, if ((t % 0.5f) < dt) 0.9f else 0f)
            val m = shaper.motion
            beat = M.follow(beat, stretch.step(F.beatTarget(m[YtSoundShaper.LEVEL], m[YtSoundShaper.BASS]), dt), dt, 0.03f, 0.16f, 14f)
            val hit = kick.step(m[YtSoundShaper.PUNCH], dt)
            if (hit > 0f) spring.kick(hit)
            spring.step(dt)
            out += beat
            scales += F.scale(beat, spring.x, 0.18f)
            t += dt
        }
        return out
    }

    @Test
    fun `a compressed beat now moves the shapes clearly`() {
        val scales = ArrayList<Float>()
        val beat = motionBeat(6f, frame, scales).drop(180)
        val swing = beat.max() - beat.min()
        assertTrue("beat swing $swing", swing >= 0.4f)
        // a button with its growth (18 %): at least 8 % bigger on the hits than between them
        val shown = scales.drop(180)
        val big = shown.max() - shown.min()
        assertTrue("scale swing $big (beat ${beat.min()}..${beat.max()})", big >= 0.08f)
        // the hits bounce: the punch kicks the spring on (nearly) every beat
        assertTrue("peak scale ${shown.max()}", shown.max() >= 1.08f)
        // and the old way (guarded level/bass, absolute, 6 % at most) hardly moved
        val shaper = YtSoundShaper()
        var energy = 0f
        val old = ArrayList<Float>()
        var t = 0f
        while (t < 6f) {
            shaper.step(frame, true, 0.8f, bassAt(t), 0.6f, 0.5f, 0f)
            energy = M.follow(energy, M.energyTarget(shaper.out[YtSoundShaper.LEVEL], shaper.out[YtSoundShaper.BASS]), frame)
            if (t > 3f) old += M.breathScale(energy)
            t += frame
        }
        assertTrue("old swing ${old.max() - old.min()} vs new $big", old.max() - old.min() < big / 2f)
    }

    @Test
    fun `stretch rests in silence and keeps a steady value calm`() {
        val s = FrequencyStretch()
        repeat(200) { assertEquals(0f, s.step(0f, frame), 0f) }
        val steady = FrequencyStretch()
        var v = 0f
        repeat(600) { v = steady.step(0.6f, frame) }
        // a steady sound sits still (no pumping), somewhere sensible
        val again = steady.step(0.6f, frame)
        assertEquals(v, again, 1e-4f)
        assertTrue(v in 0.1f..1f)
        for (x in listOf(Float.NaN, -3f, 7f)) assertTrue(steady.step(x, frame) in 0f..1f)
    }

    @Test
    fun `kick fires on a rise and not twice within its refractory time`() {
        val k = FrequencyKick()
        assertEquals(0f, k.step(0f, frame), 0f)
        assertEquals(0.8f, k.step(0.8f, frame), 1e-6f)
        // falling and rising again at once: too soon
        k.step(0.3f, frame)
        assertEquals(0f, k.step(0.9f, frame), 0f)
        // later it may fire again
        repeat(10) { k.step(0.1f, frame) }
        assertTrue(k.step(0.7f, frame) > 0f)
        // too weak a rise does nothing
        val weak = FrequencyKick()
        weak.step(0.1f, frame)
        assertEquals(0f, weak.step(0.15f, frame), 0f)
    }

    // -- springs ----------------------------------------------------------------------------

    private fun kicked(fps: Int): Triple<Float, Float, Float> {
        val s = FrequencySpring()
        val dt = 1f / fps
        s.kick(1f)
        var peak = 0f
        var low = 0f
        var settled = 10f
        var t = 0f
        while (t < 3f) {
            s.step(dt)
            t += dt
            peak = maxOf(peak, s.x)
            low = minOf(low, s.x)
            if (s.resting && settled > 9f && t > 0.1f) settled = t
        }
        return Triple(peak, low, settled)
    }

    @Test
    fun `a kicked spring jumps, overshoots and settles at any frame rate`() {
        val (peak60, low60, settled60) = kicked(60)
        assertTrue("peak $peak60", peak60 in 0.8f..1.15f)
        assertTrue("overshoot $low60", low60 < -0.15f)
        assertTrue("settles in $settled60 s", settled60 < 2f)
        for (fps in listOf(30, 90, 120, 144)) {
            val (peak, low, _) = kicked(fps)
            assertEquals("peak at $fps", peak60, peak, 0.08f)
            assertEquals("overshoot at $fps", low60, low, 0.08f)
        }
    }

    @Test
    fun `later elements bounce later`() {
        val s = FrequencySpring()
        s.kick(1f)
        var peakNow = 0f
        var peakLate = 0f
        var tNow = 0f
        var tLate = 0f
        var t = 0f
        while (t < 1f) {
            s.step(frame)
            t += frame
            if (s.at(0f) > peakNow) {
                peakNow = s.at(0f)
                tNow = t
            }
            val late = s.at(0.135f)
            if (late > peakLate) {
                peakLate = late
                tLate = t
            }
        }
        assertEquals(peakNow, peakLate, 0.05f)
        assertEquals(0.135f, tLate - tNow, 2 * frame)
    }

    @Test
    fun `spring stays bounded under a storm of kicks`() {
        val s = FrequencySpring()
        repeat(600) {
            s.kick(1f)
            s.step(frame)
            assertTrue(s.x in -FrequencySpring.MAX_DOWN..FrequencySpring.MAX_UP)
        }
        s.step(Float.NaN)
        assertFalse(s.x.isNaN())
    }

    // -- shine ------------------------------------------------------------------------------

    @Test
    fun `sweeps never come faster than one per min gap`() {
        for (fps in listOf(30, 60, 120)) {
            val dt = 1f / fps
            val shine = FrequencyShine()
            val starts = ArrayList<Float>()
            var t = 0f
            while (t < 10f) {
                // a trigger every single frame
                if (shine.step(1f, dt)) starts += t
                t += dt
            }
            for (i in 1 until starts.size) assertTrue(starts[i] - starts[i - 1] >= FrequencyShine.MIN_GAP_S - 1e-4f)
            // so any spot lights at most ~2.2 times a second: below the 3 flashes of WCAG 2.3.1
            assertTrue(starts.size / 10f <= 1f / FrequencyShine.MIN_GAP_S + 0.1f)
            assertTrue(1f / FrequencyShine.MIN_GAP_S < 3f)
        }
    }

    @Test
    fun `a sweep runs across each element, one after another, soft at both ends`() {
        val shine = FrequencyShine()
        assertEquals(-1f, shine.positionFor(0), 0f)
        shine.step(1f, frame)
        var firstSeen = -1
        var lastSeen = -1
        var i = 0
        while (shine.active) {
            shine.step(0f, frame)
            val a = shine.positionFor(0)
            val b = shine.positionFor(3)
            if (a >= 0f && firstSeen < 0) firstSeen = i
            if (b >= 0f && lastSeen < 0) lastSeen = i
            assertTrue(shine.alphaAt(a) in 0f..FrequencyShine.ALPHA_MAX)
            i++
        }
        assertTrue("element 3 later than element 0", lastSeen > firstSeen)
        assertEquals(0f, shine.alphaAt(0f), 1e-6f)
        assertEquals(0f, shine.alphaAt(1f), 1e-5f)
        assertEquals(0f, shine.alphaAt(-1f), 0f)
    }

    // -- morph ------------------------------------------------------------------------------

    @Test
    fun `every shape stays inside its circle and has a body`() {
        for (shape in 0 until FrequencyMorph.SHAPES) {
            for (deg in 0..359) {
                val a = (deg * PI / 180).toFloat()
                val r = FrequencyMorph.radius(shape, a, 9, 0.12f, 1.3f, 0.4f)
                assertTrue("shape $shape at $deg: $r", r in 0.7f..1.0001f)
            }
        }
        // the cookie is the cookie of FrequencyPulseMath, the circle is round
        assertEquals(M.cookieRadius(0.7f, 9, 0.1f, 0.2f), FrequencyMorph.radius(FrequencyMorph.COOKIE, 0.7f, 9, 0.1f, 0f, 0.2f), 1e-6f)
        assertEquals(1f, FrequencyMorph.radius(FrequencyMorph.CIRCLE, 2f, 9, 0.1f, 0f, 0f), 0f)
        // squircle corners touch the circle, its sides sit inside
        assertEquals(1f, FrequencyMorph.radius(FrequencyMorph.SQUIRCLE, (PI / 4).toFloat(), 9, 0f, 0f, 0f), 1e-4f)
        assertTrue(FrequencyMorph.radius(FrequencyMorph.SQUIRCLE, 0f, 9, 0f, 0f, 0f) < 0.9f)
    }

    @Test
    fun `the morph holds each shape, then glides into the next without a jump`() {
        assertEquals(0f, FrequencyMorph.progress(1f), 0f)
        assertEquals(0f, FrequencyMorph.progress(1f + FrequencyMorph.HOLD * 0.9f), 0f)
        assertEquals(1f, FrequencyMorph.progress(1.99999f), 1e-3f)
        assertEquals(FrequencyMorph.COOKIE, FrequencyMorph.from(1.5f))
        assertEquals(FrequencyMorph.BLOB, FrequencyMorph.to(1.5f))
        assertEquals(FrequencyMorph.CIRCLE, FrequencyMorph.to(3.5f))
        assertEquals(FrequencyMorph.SQUIRCLE, FrequencyMorph.from(-0.5f))
        // continuous: a tiny step of the phase moves every point only a little, also across
        // the change from one pair of shapes to the next
        var phase = 0f
        while (phase < 8f) {
            for (deg in 0 until 360 step 15) {
                val a = (deg * PI / 180).toFloat()
                val r0 = FrequencyMorph.radiusAt(phase, a, 8, 0.1f, 0.5f, 0.2f)
                val r1 = FrequencyMorph.radiusAt(phase + 0.002f, a, 8, 0.1f, 0.5f, 0.2f)
                assertTrue("jump at $phase/$deg: ${abs(r1 - r0)}", abs(r1 - r0) < 0.01f)
            }
            phase += 0.002f
        }
        assertEquals(0f, FrequencyMorph.progress(Float.NaN), 0f)
    }

    @Test
    fun `morph and turn stand still in silence and speed up with the beat`() {
        assertEquals(0f, F.morphSpeed(false, 1f, 1f), 0f)
        assertEquals(0f, F.turnSpeed(false, 1f), 0f)
        assertTrue(F.morphSpeed(true, 1f, 0f) > F.morphSpeed(true, 0f, 0f))
        assertTrue(F.turnSpeed(true, 1f) > 4 * F.turnSpeed(true, 0f))
        // a full morph cycle (4 shapes) at full beat takes a few seconds: alive, not frantic
        val cycle = FrequencyMorph.SHAPES / F.morphSpeed(true, 1f, 0f)
        assertTrue("cycle $cycle s", cycle in 4f..12f)
    }

    // -- mapping ----------------------------------------------------------------------------

    @Test
    fun `scale is clearly visible but bounded`() {
        assertEquals(1f, F.scale(0f, 0f, 0.14f), 0f)
        assertTrue(F.scale(1f, 1f, 0.14f) >= 1.13f)
        assertTrue(F.scale(1f, 1.2f, F.SCALE_MAX) <= 1f + F.SCALE_MAX + 1e-6f)
        val wild = listOf(Float.NaN, -9f, -0.6f, 0f, 0.5f, 1f, 3f, Float.POSITIVE_INFINITY)
        for (b in wild) for (bounce in wild) for (a in wild) {
            val s = F.scale(b, bounce, a)
            assertTrue("scale $b $bounce $a = $s", s in (1f - F.SCALE_MAX / 2f)..(1f + F.SCALE_MAX))
        }
        // the tier's amplitude makes it smaller, never bigger
        assertTrue(F.scale(1f, 1f, 0.14f, 0.7f) < F.scale(1f, 1f, 0.14f, 1f))
    }

    @Test
    fun `bars rest in a calm arch and reach full height on loud parts`() {
        val silent = FloatArray(16)
        val loud = FloatArray(16) { 1f }
        for (count in listOf(8, 12, 16)) {
            for (i in 0 until count) {
                assertEquals(F.barRest(i, count), F.bar(silent, i, count), 1e-6f)
                assertEquals(1f, F.bar(loud, i, count), 1e-6f)
            }
            // the arch: higher in the middle than at the ends
            assertTrue(F.barRest(count / 2, count) > F.barRest(0, count))
        }
        // half loud already reads clearly (lifted)
        val half = FloatArray(16) { 0.5f }
        assertTrue(F.bar(half, 3, 8) > 0.7f)
        // every band lands in one bar
        val one = FloatArray(16).also { it[15] = 1f }
        assertTrue(F.bar(one, 7, 8) > F.bar(one, 0, 8))
        for (x in listOf(Float.NaN, -1f, 5f)) assertTrue(F.bar(FloatArray(16) { x }, 2, 8) in 0f..1f)
    }

    @Test
    fun `blobs merge with the bass and split with the mids`() {
        assertTrue(F.blobDistance(1f, 0f) < F.blobDistance(0f, 0f))
        assertTrue(F.blobDistance(0f, 1f) > F.blobDistance(0f, 0f))
        for (p in listOf(Float.NaN, -1f, 0f, 1f, 4f)) assertTrue(F.blobDistance(p, p) in 0.08f..0.46f)
    }

    // -- brightness stays rate-safe ---------------------------------------------------------

    /** Times of turning points of a zigzag with [swing] (as YtSoundAnalysisTest counts flashes). */
    private fun turns(values: List<Float>, dt: Float, swing: Float = 0.1f): List<Float> {
        val out = ArrayList<Float>()
        var dir = 0
        var extreme = values.first()
        var extremeTime = 0f
        val start = values.first()
        for ((i, v) in values.withIndex()) {
            val t = i * dt
            if (dir == 0) {
                if (abs(v - start) >= swing) {
                    dir = if (v > start) 1 else -1
                    extreme = v
                    extremeTime = t
                }
            } else if ((v - extreme) * dir >= 0f) {
                extreme = v
                extremeTime = t
            } else if (abs(extreme - v) >= swing) {
                out += extremeTime
                dir = -dir
                extreme = v
                extremeTime = t
            }
        }
        return out
    }

    @Test
    fun `the glow (guarded, stretched, slew-limited) never flashes more than 3 times a second`() {
        for (fps in listOf(30, 60, 120)) {
            val dt = 1f / fps
            val rnd = Random(fps)
            val shaper = YtSoundShaper()
            val stretch = FrequencyStretch(mix = 0.5f)
            var glow = 0f
            var shimmer = 0f
            val glows = ArrayList<Float>()
            val shimmers = ArrayList<Float>()
            val shine = FrequencyShine()
            repeat(fps * 20) {
                shaper.step(dt, true, rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
                glow = M.follow(glow, M.glowTarget(stretch.step(shaper.out[YtSoundShaper.LEVEL], dt)), dt)
                shine.step(rnd.nextFloat(), dt)
                shimmer = M.follow(shimmer, maxOf(shaper.out[YtSoundShaper.HIGH], if (shine.active) shine.strength else 0f), dt)
                glows += M.glowAlpha(glow)
                shimmers += M.shimmerAlpha(shimmer)
            }
            for ((name, values) in listOf("glow" to glows, "shimmer" to shimmers)) {
                val t = turns(values, dt)
                for (i in t.indices) {
                    val inWindow = t.count { it >= t[i] && it < t[i] + 1f }
                    assertTrue("$name fps=$fps: $inWindow turns in 1 s", inWindow <= 7)
                }
            }
        }
    }

    // -- tiers ------------------------------------------------------------------------------

    private val flagship = FrequencyDevice(refreshHz = 120f, sdk = 36, lowRam = false, totalRamMb = 12_000, cores = 8, powerSave = false, thermal = 0)

    @Test
    fun `a flagship gets everything at the display rate`() {
        val c = FrequencyTiers.pick(flagship)
        assertEquals(FrequencyTier.HIGH, c.tier)
        assertTrue(c.shader)
        assertEquals(1, c.frameDivider)
        assertEquals(120f, c.stepsPerSecond(120f), 0f)
        // Android 12: no RuntimeShader, still the high tier with gradient blobs
        val s31 = FrequencyTiers.pick(flagship.copy(sdk = 31))
        assertEquals(FrequencyTier.HIGH, s31.tier)
        assertFalse(s31.shader)
    }

    @Test
    fun `middle phones get fewer effects at most 60 a second`() {
        val c = FrequencyTiers.pick(flagship.copy(totalRamMb = 4_000))
        assertEquals(FrequencyTier.MID, c.tier)
        assertFalse(c.shader)
        assertEquals(2, c.frameDivider)
        assertEquals(1, FrequencyTiers.pick(flagship.copy(totalRamMb = 4_000, refreshHz = 60f)).frameDivider)
        assertEquals(FrequencyTier.MID, FrequencyTiers.pick(flagship.copy(thermal = FrequencyTiers.THERMAL_MODERATE)).tier)
        assertEquals(FrequencyTier.MID, FrequencyTiers.pick(flagship.copy(cores = 4)).tier)
        assertEquals(FrequencyTier.MID, FrequencyTiers.pick(flagship.copy(refreshHz = 90f, totalRamMb = 4_000)).let { it.tier })
        assertEquals(2, FrequencyTiers.pick(flagship.copy(refreshHz = 90f, totalRamMb = 4_000)).frameDivider)
    }

    @Test
    fun `power saver, heat and small phones get the basics at half rate`() {
        for (d in listOf(
            flagship.copy(powerSave = true),
            flagship.copy(thermal = FrequencyTiers.THERMAL_SEVERE),
            flagship.copy(lowRam = true),
            flagship.copy(totalRamMb = 2_000),
        )) {
            val c = FrequencyTiers.pick(d)
            assertEquals(d.toString(), FrequencyTier.LOW, c.tier)
            assertFalse(c.shader)
            assertEquals(30f, c.stepsPerSecond(d.refreshHz), 0.5f)
        }
        assertEquals(30f, FrequencyTiers.pick(flagship.copy(powerSave = true, refreshHz = 60f)).stepsPerSecond(60f), 0.5f)
        val low = FrequencyTier.LOW
        assertEquals(0, low.blobs)
        assertFalse(low.morph || low.bounce || low.shine || low.backgroundWave)
        assertTrue(low.bars > 0)
    }

    @Test
    fun `an unknown refresh rate or RAM size does not break the pick`() {
        val c = FrequencyTiers.pick(flagship.copy(refreshHz = Float.NaN, totalRamMb = 0, cores = 0))
        assertEquals(FrequencyTier.HIGH, c.tier)
        assertEquals(1, c.frameDivider)
        assertTrue(FrequencyTier.HIGH.blobs > FrequencyTier.MID.blobs)
        assertTrue(FrequencyTier.HIGH.bars > FrequencyTier.MID.bars && FrequencyTier.MID.bars > FrequencyTier.LOW.bars)
    }
}
