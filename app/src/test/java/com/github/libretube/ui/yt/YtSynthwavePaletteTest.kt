package com.github.libretube.ui.yt

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.mini.YtMiniSynthwave
import com.github.libretube.ui.yt.search.YtSearchKind
import com.github.libretube.ui.yt.search.YtSearchPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YT: Synthwave's palette reads everywhere (4.5:1): every text role on every surface, the dark
 * ink on every neon, white on the live badge; and the words on the dark glass of the home's
 * cards over the brightest spots of the scene behind them (the sun's top, its halo, the
 * horizon), the words of the shared screens on the scene under its night veil, the nav bar, the
 * mini player, the swipe word and the search.
 */
class YtSynthwavePaletteTest {
    private val p = YtPalettes.Synthwave
    private val c = SynthwaveColors

    private fun reads(what: String, fg: Color, back: Int, min: Double = YtContrast.BODY) {
        val ratio = YtContrast.ratio(fg.toArgb(), back)
        assertTrue("$what: %.2f".format(ratio), ratio >= min)
    }

    private fun on(top: Color, under: Color): Int = YtContrast.over(top.toArgb(), under.toArgb())

    /** Every colour the scene can show at full strength (the halo at its peak over the sky). */
    private val sceneSpots: List<Pair<String, Int>> = buildList {
        add("sun top" to c.sunTop.toArgb())
        add("sun middle" to c.sunMid.toArgb())
        add("sun low" to c.sunLow.toArgb())
        add("sun bottom" to c.sunBottom.toArgb())
        add("halo at its peak" to on(c.halo.copy(alpha = c.HALO_CORE), c.skyLow))
        add("horizon line" to on(c.horizon.copy(alpha = 0.85f), Color(on(c.haze.copy(alpha = c.HAZE_PEAK), c.floorTop))))
        add("grid line" to on(c.grid.copy(alpha = 0.85f), c.floorTop))
        add("ridge line" to on(c.ridgeNear, c.ridgeBodyNear))
        add("star" to c.star.toArgb())
        add("sky low" to c.skyLow.toArgb())
    }

    @Test
    fun `it is the dark design Synthwave`() {
        assertEquals(YtSignature.SYNTHWAVE, p.signature)
        assertFalse(p.light)
        assertFalse(YtDesign.SYNTHWAVE.light)
        assertEquals(p, YtDesign.SYNTHWAVE.palette)
        assertTrue(YtDesign.SYNTHWAVE.labeledNavigation)
        assertEquals(YtFonts.geist, p.font)
        assertEquals(YtSurfaceStyle.SYNTHWAVE, p.surfaceStyle)
    }

    @Test
    fun `every text role reads on every surface`() {
        for ((name, bg) in listOf("background" to p.background, "surface" to p.surface, "raised" to p.raised)) {
            val back = on(bg, p.background)
            reads("text on $name", p.text, back)
            reads("secondary on $name", p.textSecondary, back)
            reads("accent as type on $name", p.accent, back)
            reads("accentText on $name", p.accentText, back)
            reads("cyan as type on $name", p.lime, back)
        }
        reads("onAccent on the magenta", p.onAccent, p.accent.toArgb())
        reads("white on the live badge", Color.White, p.live.toArgb())
    }

    @Test
    fun `the dark ink reads on every neon`() {
        for (i in 0 until 4) reads("ink on tag $i", p.ink, synthwaveTag(p, i).toArgb())
        reads("ink on cyan", p.ink, p.lime.toArgb())
        reads("ink on violet", p.ink, p.lavender.toArgb())
        reads("ink on orange", p.ink, p.pink.toArgb())
        reads("onAccent on the seen badge", p.onAccent, p.accent.toArgb())
    }

    @Test
    fun `words on the home's glass read over the brightest spots of the scene`() {
        for ((spot, under) in sceneSpots) {
            for ((glass, tone) in listOf("glass top" to SynthwaveGlass.top, "glass bottom" to SynthwaveGlass.bottom, "glass pill" to SynthwaveGlass.pill)) {
                val back = YtContrast.over(tone.toArgb(), under)
                reads("text on $glass over $spot", p.text, back)
                reads("secondary on $glass over $spot", p.textSecondary, back)
                // the cyan kicker ("Weiterschauen") and the cyan chevrons
                reads("cyan on $glass over $spot", p.lime, back)
            }
            // the pinned filter band: the glass top at full strength
            reads("chip text on the band over $spot", p.text, YtContrast.over(SynthwaveGlass.top.toArgb(), under))
        }
    }

    @Test
    fun `the shared screens' words read on the scene under its veil`() {
        val veil = p.background.copy(alpha = SynthwaveColors.TAB_VEIL)
        for ((spot, under) in sceneSpots) {
            val back = YtContrast.over(veil.toArgb(), under)
            reads("text on the veiled $spot", p.text, back)
            reads("secondary on the veiled $spot", p.textSecondary, back)
            reads("accent as type on the veiled $spot", p.accent, back)
            // the cards of Abos and Du on the veiled scene
            val card = YtContrast.over(SynthwaveGlass.top.toArgb(), back)
            reads("text on a card over the veiled $spot", p.text, card)
            reads("secondary on a card over the veiled $spot", p.textSecondary, card)
        }
    }

    @Test
    fun `the surface tones are the glass`() {
        val tones = ytSurfaceTones(p, p.background.toArgb())
        assertEquals(YtContrast.over(SynthwaveGlass.top.toArgb(), p.background.toArgb()), tones[0])
        assertEquals(YtContrast.over(SynthwaveGlass.bottom.toArgb(), p.background.toArgb()), tones[1])
    }

    @Test
    fun `the nav bar's labels read over the scene`() {
        val bar = Color(0xF5120A28)
        for ((spot, under) in sceneSpots) {
            val back = YtContrast.over(bar.toArgb(), under)
            reads("label on the bar over $spot", p.text, back)
            reads("quiet label on the bar over $spot", p.textSecondary, back)
        }
    }

    @Test
    fun `the mini player's words read on its card`() {
        val m = YtMiniSynthwave
        for ((name, bg) in listOf("card" to m.card, "card top" to m.cardTop)) {
            reads("title on $name", m.title, bg.toArgb())
            reads("secondary on $name", m.secondary, bg.toArgb())
        }
    }

    @Test
    fun `the swipe word reads on the tinted night`() {
        for (field in listOf(p.lime, p.accent)) {
            val back = on(field.copy(alpha = SYNTHWAVE_SWIPE_TINT), c.skyTop)
            reads("swipe word on $field", SynthwaveSwipeWord, back)
        }
    }

    @Test
    fun `the search reads`() {
        val inks = YtSearchPalette.inks(YtSearchKind.SYNTHWAVE)
        val scrim = YtContrast.over(inks.scrim, p.background.toArgb())
        reads("field text", Color(inks.fieldText), inks.field)
        reads("field hint", Color(inks.fieldHint), inks.field)
        reads("row text", Color(inks.rowText), scrim)
        reads("row secondary", Color(inks.rowSecondary), scrim)
        reads("chip text", Color(inks.chipText), inks.chip)
        reads("chosen filter", Color(YtSearchPalette.onFill(inks.mark)), inks.mark)
    }

    @Test
    fun `the timeline is the magenta, and the halo stays inside its bounds`() {
        assertEquals(p.accent.toArgb(), YtDesign.SYNTHWAVE.timelineColor)
        assertTrue(SynthwaveMath.sunGlow(1f) * SynthwaveColors.HALO_CORE / SynthwaveMath.GLOW_PEAK <= SynthwaveColors.HALO_CORE + 1e-6f)
        assertEquals(c.sunTop, c.brightest)
    }
}
