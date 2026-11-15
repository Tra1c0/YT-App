package com.github.libretube.ui.yt.ambilight

import com.github.libretube.helpers.YtDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtAmbilightRulesTest {
    private val playing = YtAmbilightRateInput(
        playing = true,
        place = YtAmbilightPlace.OPEN,
        motion = true,
        powerSave = false,
        thermal = 0,
        activity = 0f,
        costMs = 2f,
    )

    private fun hz(input: YtAmbilightRateInput) = YtAmbilightRules.rateHz(input)

    @Test
    fun `the rate follows the picture between 4 and 12 Hz`() {
        assertEquals(4f, hz(playing), 0f)
        assertEquals(12f, hz(playing.copy(activity = 1f)), 0f)
        val mid = hz(playing.copy(activity = YtAmbilightRules.BUSY / 2))
        assertTrue(mid > 4f && mid < 12f)
        assertEquals(250L, YtAmbilightRules.intervalMs(playing))
    }

    @Test
    fun `paused, hidden, power saver and a critical phone do not sample`() {
        assertEquals(0f, hz(playing.copy(playing = false)), 0f)
        assertEquals(0f, hz(playing.copy(place = YtAmbilightPlace.HIDDEN)), 0f)
        assertEquals(0f, hz(playing.copy(powerSave = true)), 0f)
        assertEquals(0f, hz(playing.copy(thermal = YtAmbilightRules.THERMAL_STOP)), 0f)
        assertEquals(0L, YtAmbilightRules.intervalMs(playing.copy(playing = false)))
    }

    @Test
    fun `docked, warm, costly or without animations it samples less`() {
        val busy = playing.copy(activity = 1f)
        assertTrue(hz(busy.copy(place = YtAmbilightPlace.DOCKED)) <= 6f)
        assertTrue(hz(busy.copy(motion = false)) <= 3f)
        assertTrue(hz(busy.copy(thermal = 1)) <= 8f)
        assertTrue(hz(busy.copy(thermal = 2)) <= 4f)
        assertTrue(hz(busy.copy(thermal = 3)) <= 2f)
        assertTrue(hz(busy.copy(costMs = 10f)) <= 6f)
        assertTrue(hz(busy.copy(costMs = 20f)) <= 3f)
        // never more than the top rate
        assertTrue(hz(busy) <= YtAmbilightRules.MAX_HZ)
    }

    private val on = YtAmbilightSettings(enabled = true)

    @Test
    fun `it samples only where something shows the colours`() {
        val classic = YtAmbilightProfile.of(YtDesign.STANDARD)
        val design = YtAmbilightProfile.of(YtDesign.YOUTUBE)
        // off: never
        assertFalse(YtAmbilightRules.shows(design, YtAmbilightSettings(), YtAmbilightPlace.OPEN, true))
        // the page: always (the glow under the player)
        assertTrue(YtAmbilightRules.shows(classic, on, YtAmbilightPlace.OPEN, false))
        // docked: the classic look has no halo, the designs do
        assertFalse(YtAmbilightRules.shows(classic, on, YtAmbilightPlace.DOCKED, false))
        assertTrue(YtAmbilightRules.shows(design, on, YtAmbilightPlace.DOCKED, false))
        // fullscreen: only bars can glow
        assertFalse(YtAmbilightRules.shows(design, on, YtAmbilightPlace.FULLSCREEN, false))
        assertTrue(YtAmbilightRules.shows(design, on, YtAmbilightPlace.FULLSCREEN, true))
        assertFalse(YtAmbilightRules.shows(design, on.copy(bars = false), YtAmbilightPlace.FULLSCREEN, true))
        assertFalse(YtAmbilightRules.shows(design, on, YtAmbilightPlace.HIDDEN, true))
    }

    @Test
    fun `every design has a profile and the classic one glows behind the player only`() {
        for (design in YtDesign.entries) {
            val p = YtAmbilightProfile.of(design)
            assertTrue("$design glows under the player", p.page > 0f)
            assertTrue("$design stays subtle", p.page <= 0.8f && p.wash <= 0.1f && p.edges <= 0.5f)
            assertEquals("$design pulses only if it follows the sound", design.soundReactive, p.beat)
        }
        val classic = YtAmbilightProfile.of(YtDesign.STANDARD)
        assertEquals(0f, classic.wash + classic.edges + classic.nav + classic.halo, 0f)
        assertFalse(classic.glass)
        assertTrue(YtAmbilightProfile.of(YtDesign.GLASS).glass)
    }

    @Test
    fun `the beat is subtle and needs its switch, the design, motion and playback`() {
        val synth = YtAmbilightProfile.of(YtDesign.SYNTHWAVE)
        assertTrue(YtAmbilightRules.beats(synth, on, motion = true, playing = true))
        assertFalse(YtAmbilightRules.beats(synth, on.copy(beat = false), motion = true, playing = true))
        assertFalse(YtAmbilightRules.beats(synth, on, motion = false, playing = true))
        assertFalse(YtAmbilightRules.beats(synth, on, motion = true, playing = false))
        assertFalse(YtAmbilightRules.beats(YtAmbilightProfile.of(YtDesign.CLAY), on, motion = true, playing = true))
        assertEquals(1f, YtAmbilightRules.beatGain(0f), 0f)
        assertTrue(YtAmbilightRules.beatGain(5f) <= 1f + YtAmbilightRules.BEAT_DEPTH)
    }

    @Test
    fun `the copy gives way to the fallback after failures, slowly for no data`() {
        assertFalse(YtAmbilightRules.useFallback(failures = 2, noData = 0))
        assertTrue(YtAmbilightRules.useFallback(failures = YtAmbilightRules.MAX_FAILURES, noData = 0))
        assertFalse(YtAmbilightRules.useFallback(failures = 0, noData = 5))
        assertTrue(YtAmbilightRules.useFallback(failures = 0, noData = YtAmbilightRules.MAX_NO_DATA))
    }

    @Test
    fun `the strength slider maps to a factor around 1`() {
        assertEquals(1f, YtAmbilightSettings.intensityOf(60f), 1e-6f)
        assertEquals(10f / 60f, YtAmbilightSettings.intensityOf(0f), 1e-6f)
        assertEquals(100f / 60f, YtAmbilightSettings.intensityOf(500f), 1e-6f)
        assertEquals(1f, YtAmbilightSettings.intensityOf(Float.NaN), 1e-6f)
    }
}
