package com.github.libretube.ui.yt

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.mini.YtMiniFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YT: Frequency's palette reads everywhere (4.5:1): every text role on every surface, the night
 * ink on every shape colour, the coral's own ink, white on the live badge, and the text on the
 * background even where the aura is at its brightest.
 */
class YtFrequencyPaletteTest {
    private val p = YtPalettes.Frequency

    private fun reads(what: String, fg: Color, bg: Color, under: Color = p.background, min: Double = YtContrast.BODY) {
        val back = YtContrast.over(bg.toArgb(), under.toArgb())
        val ratio = YtContrast.ratio(fg.toArgb(), back)
        assertTrue("$what: %.2f".format(ratio), ratio >= min)
    }

    @Test
    fun `it is the dark design Frequency`() {
        assertEquals(YtSignature.FREQUENCY, p.signature)
        assertFalse(p.light)
        assertFalse(YtDesign.FREQUENCY.light)
        assertEquals(p, YtDesign.FREQUENCY.palette)
        assertTrue(YtDesign.FREQUENCY.labeledNavigation)
        assertEquals(YtFonts.geist, p.font)
    }

    @Test
    fun `every text role reads on every surface`() {
        for ((name, bg) in listOf("background" to p.background, "surface" to p.surface, "raised" to p.raised)) {
            reads("text on $name", p.text, bg)
            reads("secondary on $name", p.textSecondary, bg)
            reads("accent as type on $name", p.accent, bg)
            reads("accentText on $name", p.accentText, bg)
        }
        reads("onAccent on the coral", p.onAccent, p.accent)
        reads("white on the live badge", Color.White, p.live)
    }

    @Test
    fun `the night ink reads on every shape colour`() {
        for (i in 0 until 4) reads("ink on tag $i", p.ink, frequencyTag(p, i))
        reads("ink on aqua", p.ink, p.lime)
        reads("ink on violet", p.ink, p.lavender)
        reads("ink on amber", p.ink, p.pink)
    }

    @Test
    fun `text reads where the aura is brightest`() {
        // the aura's lights at their peak, stacked on the night (FrequencyPulse.frequencyAura)
        val a = FrequencyPulseMath.AURA_PEAK
        var bright = p.background.toArgb()
        bright = YtContrast.over(p.accent.copy(alpha = a * 0.7f).toArgb(), bright)
        bright = YtContrast.over(p.lavender.copy(alpha = a * 0.55f).toArgb(), bright)
        bright = YtContrast.over(p.lime.copy(alpha = a * 0.35f).toArgb(), bright)
        for ((name, fg) in listOf("text" to p.text, "secondary" to p.textSecondary)) {
            val ratio = YtContrast.ratio(fg.toArgb(), bright)
            assertTrue("$name on the aura: %.2f".format(ratio), ratio >= YtContrast.BODY)
        }
    }

    @Test
    fun `the mini player's words read on its card`() {
        val c = YtMiniFrequency.colors
        for ((name, bg) in listOf("card" to c.card, "cardTop" to c.cardTop, "row" to c.row, "rowActive" to c.rowActive, "pill" to c.pill)) {
            reads("title on $name", c.title, bg)
            reads("secondary on $name", c.secondary, bg)
            reads("coral label on $name", p.accent, bg)
        }
        reads("words on the coral pill", c.onAccent, p.accent)
    }

    @Test
    fun `the timeline is the coral`() {
        assertEquals(p.accent.toArgb(), YtDesign.FREQUENCY.timelineColor)
    }
}
