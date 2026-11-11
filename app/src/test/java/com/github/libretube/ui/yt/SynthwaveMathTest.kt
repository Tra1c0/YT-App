package com.github.libretube.ui.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import com.github.libretube.ui.yt.SynthwaveMath as S

/**
 * YT: the pure maths of Synthwave's scene: the floor's perspective and its seamless drive, the
 * sun's slits, the ridges, the stars (twinkle well under 3 Hz), how the sound moves the scene,
 * the parallax, the mini player's landing and the detail per tier.
 */
class SynthwaveMathTest {
    private val eps = 1e-4f

    @Test
    fun `the floor's rows lie in perspective and drive without a seam`() {
        // the nearest row sits at the bottom at rest, the far ones crowd towards the horizon
        assertEquals(1f, S.rowAt(0, 0f), eps)
        for (k in 1 until 20) assertTrue("row $k above row ${k - 1}", S.rowAt(k, 0f) < S.rowAt(k - 1, 0f))
        // the gaps shrink with the distance (perspective)
        for (k in 1 until 18) {
            val near = S.rowAt(k - 1, 0f) - S.rowAt(k, 0f)
            val far = S.rowAt(k, 0f) - S.rowAt(k + 1, 0f)
            assertTrue(far < near)
        }
        // driving moves every row towards the viewer
        for (k in 0 until 15) assertTrue(S.rowAt(k, 0.3f) > S.rowAt(k, 0.1f))
        // one full row of drive puts each row where the next one was: the loop has no seam
        for (k in 1 until 15) assertEquals(S.rowAt(k - 1, 0f), S.rowAt(k, 1f), eps)
        // rows past the viewer are below the screen
        assertTrue(S.rowAt(0, 0.5f) > 1f)
        assertTrue(S.rowAt(-5, 0f) > 1f)
    }

    @Test
    fun `the rows fade into the haze at the horizon`() {
        assertEquals(0f, S.rowAlpha(0f), eps)
        assertEquals(1f, S.rowAlpha(1f), eps)
        assertTrue(S.rowAlpha(0.1f) < S.rowAlpha(0.3f))
    }

    @Test
    fun `the floor's lines run to the vanishing point`() {
        for (j in -5..5) {
            assertEquals(200f, S.columnX(j, 0f, 200f, 80f), eps)
            assertEquals(200f + j * 80f, S.columnX(j, 1f, 200f, 80f), eps)
        }
    }

    @Test
    fun `the slits scroll down, grow from nothing and never overlap`() {
        for (n in listOf(4, 6, 7)) {
            for (i in 0 until n) {
                // a whole slit of scroll puts slit i where slit i+1 was
                if (i < n - 1) assertEquals(S.slitTop(i + 1, n, 0f), S.slitTop(i, n, 1f - 1e-6f), 1e-3f)
                // further down as the phase grows (until it wraps)
                val a = S.slitTop(i, n, 0.1f)
                val b = S.slitTop(i, n, 0.2f)
                if (S.slitPosition(i, n, 0.2f) > S.slitPosition(i, n, 0.1f)) assertTrue(b > a)
            }
            // a slit appears with no height and is thickest where it leaves at the bottom
            assertEquals(0f, S.slitHeight(0, n, 0f), eps)
            assertTrue(S.slitHeight(n - 1, n, 0.999f) > S.SLIT_MAX * 0.99f)
            // sorted by position, each slit ends before the next one begins
            for (step in 0 until 50) {
                val phase = step / 50f
                val slits = (0 until n).map { S.slitTop(it, n, phase) to S.slitHeight(it, n, phase) }.sortedBy { it.first }
                for (k in 1 until slits.size) {
                    assertTrue("n $n phase $phase", slits[k - 1].first + slits[k - 1].second <= slits[k].first + 1e-4f)
                }
                for ((top, tall) in slits) {
                    assertTrue(top >= S.SLIT_START - eps && top <= 1f + eps)
                    assertTrue(tall >= 0f && tall <= S.SLIT_MAX + eps)
                }
            }
        }
    }

    @Test
    fun `the ridges stay in range, rise to the back and peak right of the middle`() {
        val lines = 9
        for (line in 0 until lines) {
            for (i in 0..100) {
                val h = S.ridge(i / 100f, line, lines)
                assertTrue(h in 0f..1f)
                assertEquals(h, S.ridge(i / 100f, line, lines), 0f)
            }
        }
        fun mean(line: Int) = (0..100).map { S.ridge(it / 100f, line, lines) }.average()
        assertTrue("the far line is higher than the near one", mean(0) > mean(lines - 1))
        val far = (0..100).maxByOrNull { S.ridge(it / 100f, 0, lines) }!! / 100f
        assertTrue("the main peak is right of the middle: $far", far in 0.5f..0.7f)
        // never flat zero across the width: the range spans the horizon
        assertTrue((0..100).all { S.ridge(it / 100f, 0, lines) > 0.04f })
    }

    @Test
    fun `stars are stable, spread and twinkle slowly`() {
        val xs = (0 until 400).map { S.hash(it, 0) }
        assertTrue(xs.all { it >= 0f && it < 1f })
        assertEquals(0.5, xs.average(), 0.06)
        assertEquals(S.hash(17, 3), S.hash(17, 3), 0f)
        for (i in 0 until SynthwaveTiers.MAX_STARS) {
            val hz = S.twinkleHz(i)
            assertTrue("star $i twinkles at $hz Hz", hz >= S.TWINKLE_MIN_HZ && hz <= S.TWINKLE_MAX_HZ)
            assertTrue(hz < 3f)
            for (t in 0 until 40) {
                val light = S.starLight(i, t * 0.25f, S.TWINKLE_HIGH)
                assertTrue(light in 0f..1f)
            }
            // without depth a star does not twinkle
            assertEquals(S.starLight(i, 0f, 0f), S.starLight(i, 7.3f, 0f), eps)
        }
    }

    @Test
    fun `the sound moves the scene subtly and within bounds`() {
        // the floor drives calmly in silence, a little faster with the bass, never wild
        assertEquals(S.DRIVE_CALM, S.driveSpeed(0f), eps)
        assertTrue(S.driveSpeed(0.5f) > S.driveSpeed(0f))
        assertEquals(S.DRIVE_CALM + S.DRIVE_BASS, S.driveSpeed(5f), eps)
        assertEquals(S.DRIVE_CALM, S.driveSpeed(Float.NaN), eps)
        assertTrue(S.driveSpeed(1f) / S.driveSpeed(0f) < 2.5f)
        // the halo glows gently, between its rest and its peak
        assertEquals(S.GLOW_REST, S.sunGlow(0f), eps)
        assertEquals(S.GLOW_PEAK, S.sunGlow(1f), eps)
        assertEquals(S.GLOW_PEAK, S.sunGlow(3f), eps)
        assertTrue(S.GLOW_PEAK - S.GLOW_REST <= 0.25f)
        // the ridges stretch by a few per cent at most
        assertEquals(0f, S.ridgeSway(0f), eps)
        assertEquals(S.SWAY_MAX, S.ridgeSway(2f), eps)
        assertTrue(S.SWAY_MAX <= 0.1f)
        // the far ridge lines take the bass, the near ones higher bands, always in range
        for (lines in listOf(5, 6, 9)) {
            assertEquals(0, S.ridgeBand(0, lines, 16))
            for (line in 0 until lines) assertTrue(S.ridgeBand(line, lines, 16) in 0 until 16)
            assertTrue(S.ridgeBand(lines - 1, lines, 16) >= S.ridgeBand(0, lines, 16))
        }
        // the highs deepen the twinkle, from rest to its most
        assertEquals(S.TWINKLE_REST, S.twinkleDepth(0f), eps)
        assertEquals(S.TWINKLE_HIGH, S.twinkleDepth(1f), eps)
    }

    @Test
    fun `brightness from the sound changes at most three times a second`() {
        // the halo follows the guarded level through FrequencyPulseMath.follow: even a level
        // that flips every frame moves the light by at most MAX_SLEW a second
        val dt = 1f / 120f
        var glow = 0f
        var last = S.sunGlow(glow)
        var maxStep = 0f
        for (frame in 0 until 240) {
            val input = if (frame % 2 == 0) 1f else 0f
            glow = FrequencyPulseMath.follow(glow, input, dt)
            val now = S.sunGlow(glow)
            maxStep = maxOf(maxStep, abs(now - last))
            last = now
        }
        val perSecond = maxStep / dt / (S.GLOW_PEAK - S.GLOW_REST)
        assertTrue("full swings a second: $perSecond", perSecond <= FrequencyPulseMath.MAX_SLEW + 1e-3f)
        // a full swing up and back takes at least a third of a second
        assertTrue(2f / FrequencyPulseMath.MAX_SLEW >= 1f / 3f - 1e-3f)
    }

    @Test
    fun `the scene lifts a little with the scroll`() {
        assertEquals(0f, S.parallax(0f, 0.06f, 40f), eps)
        assertEquals(-6f, S.parallax(100f, 0.06f, 40f), eps)
        assertEquals(-40f, S.parallax(100000f, 0.06f, 40f), eps)
        assertEquals(0f, S.parallax(-50f, 0.06f, 40f), eps)
        assertEquals(2 * 300f + 25f, S.scrolled(2, 25, 300f), eps)
        assertEquals(0f, S.scrolled(-1, -4, 300f), eps)
    }

    @Test
    fun `the mini player lands like a bump and comes to rest`() {
        assertEquals(0f, S.bump(0f), eps)
        assertEquals(0f, S.bump(1f), eps)
        assertTrue("it goes down first", S.bump(0.15f) > 0f)
        assertTrue("then a little past rest", (0..100).any { S.bump(it / 100f) < 0f })
        for (i in 0..200) assertTrue(abs(S.bump(i / 200f)) <= 1f)
        assertTrue(S.LAND_MS in 300L..900L)
        assertEquals(-1f, S.edgeSweep(0f), eps)
        assertEquals(-1f, S.edgeSweep(1f), eps)
        assertTrue(S.edgeSweep(0.5f) in 0f..1f)
    }

    @Test
    fun `each tier gets less, and low is a still sky over a driving floor`() {
        val high = SynthwaveTiers.of(FrequencyTier.HIGH)
        val mid = SynthwaveTiers.of(FrequencyTier.MID)
        val low = SynthwaveTiers.of(FrequencyTier.LOW)
        for ((a, b) in listOf(high to mid, mid to low)) {
            assertTrue(a.stars > b.stars)
            assertTrue(a.rows > b.rows)
            assertTrue(a.columns > b.columns)
            assertTrue(a.ridges > b.ridges)
        }
        assertTrue(high.glowLines && high.sky && high.sound)
        assertTrue(mid.sky && mid.sound && !mid.glowLines)
        assertFalse(low.sky)
        assertFalse(low.sound)
        for (d in listOf(high, mid, low, SynthwaveTiers.STILL)) {
            assertTrue(d.stars <= SynthwaveTiers.MAX_STARS)
            assertTrue(d.ridges <= SynthwaveTiers.MAX_RIDGES)
            assertTrue(d.slits in 3..9)
        }
        // the still picture (animations off) is complete but moves nothing
        assertFalse(SynthwaveTiers.STILL.sky)
        assertFalse(SynthwaveTiers.STILL.sound)
        assertTrue(SynthwaveTiers.STILL.stars > low.stars)
    }

    @Test
    fun `the rates of the tiers`() {
        fun choice(hz: Float, powerSave: Boolean = false, ram: Long = 8_000, cores: Int = 8) = FrequencyTiers.pick(
            FrequencyDevice(refreshHz = hz, sdk = 34, lowRam = false, totalRamMb = ram, cores = cores, powerSave = powerSave, thermal = 0),
        )
        // high: the display's rate
        assertEquals(120f, choice(120f).stepsPerSecond(120f), 0.5f)
        // mid: at most 60 steps a second
        assertTrue(choice(120f, ram = 4_000).stepsPerSecond(120f) <= 60.5f)
        // power saver: the low tier at about 30
        val saver = choice(120f, powerSave = true)
        assertEquals(FrequencyTier.LOW, saver.tier)
        assertEquals(30f, saver.stepsPerSecond(120f), 0.5f)
        assertFalse(SynthwaveTiers.of(saver.tier).sky)
    }
}
