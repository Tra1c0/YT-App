package com.github.libretube.ui.yt

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.github.libretube.helpers.YtAppearanceOptions
import com.github.libretube.helpers.YtDesign
import com.github.libretube.helpers.YtDesignDark
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** YT: the dark mode of the light designs: the palettes read and the mode is chosen right. */
class YtNightPaletteTest {
    private val nights = mapOf(
        YtDesign.CLAY to YtPalettes.ClayDark,
        YtDesign.EDITORIAL to YtPalettes.EditorialDark,
        YtDesign.GLASS to YtPalettes.GlassDark,
        YtDesign.POP to YtPalettes.PopDark,
        YtDesign.KIESEL to YtPalettes.KieselDark,
    )

    @After
    fun day() = YtDesignDark.update(designLight = false, night = false)

    private fun reads(what: String, fg: Color, bg: Color, under: Color = bg) {
        val back = YtContrast.over(bg.toArgb(), under.toArgb())
        val ratio = YtContrast.ratio(fg.toArgb(), back)
        assertTrue("$what: %.2f".format(ratio), ratio >= YtContrast.BODY)
    }

    @Test
    fun `every text role reads on every surface at night`() {
        for ((design, p) in nights) {
            assertFalse("$design is dark", p.light)
            for ((name, bg) in listOf("background" to p.background, "surface" to p.surface, "raised" to p.raised)) {
                reads("$design text on $name", p.text, bg, p.background)
                reads("$design secondary on $name", p.textSecondary, bg, p.background)
            }
            reads("$design onAccent", p.onAccent, p.accent)
            reads("$design ink on lime", p.ink, p.lime, p.background)
            reads("$design ink on lavender", p.ink, p.lavender, p.background)
        }
    }

    @Test
    fun `only the light designs have a night, and only when it is on`() {
        val light = YtDesign.entries.filter { it.light }.toSet()
        assertEquals(nights.keys, light)
        YtDesignDark.update(designLight = true, night = false)
        assertEquals(YtPalettes.Clay, YtDesign.CLAY.palette)
        YtDesignDark.update(designLight = true, night = true)
        for ((design, p) in nights) assertEquals(p, design.palette)
        // the dark-only designs never change
        assertEquals(YtPalettes.YouTube, YtDesign.YOUTUBE.palette)
        YtDesignDark.update(designLight = false, night = true)
        assertFalse(YtDesignDark.active)
        assertEquals(YtPalettes.Clay, YtDesign.CLAY.palette)
    }

    @Test
    fun `day and night colours follow the mode`() {
        val day = Color(0xFFFFFFFF)
        val night = Color(0xFF000000)
        assertEquals(day, ytDayNight(day, night))
        YtDesignDark.update(designLight = true, night = true)
        assertEquals(night, ytDayNight(day, night))
        // Kiesel keeps black type on its pastels at night, light type on its cards
        assertEquals(KieselBlack, kieselInkOn(KieselLavender))
        assertEquals(KieselInk, kieselInkOn(KieselPanelCard))
        // Editorial's blue as type is lifted until it reads on the black paper
        reads("editorial accent as type", YtPalettes.EditorialDark.accentText, YtPalettes.EditorialDark.background)
    }

    @Test
    fun `theme mode letters`() {
        assertEquals("D", YtDesignDark.themeMode(designLight = false, choice = "L"))
        assertEquals("L", YtDesignDark.themeMode(designLight = true, choice = null))
        assertEquals("L", YtDesignDark.themeMode(designLight = true, choice = "L"))
        assertEquals("D", YtDesignDark.themeMode(designLight = true, choice = "D"))
        assertEquals("A", YtDesignDark.themeMode(designLight = true, choice = "A"))
        assertEquals("L", YtDesignDark.themeMode(designLight = true, choice = "x"))
    }

    @Test
    fun `the light or dark row shows only for light designs`() {
        assertTrue(YtAppearanceOptions.applies(YtAppearanceOptions.DESIGN_DARK, "clay", designLight = true))
        assertFalse(YtAppearanceOptions.applies(YtAppearanceOptions.DESIGN_DARK, "youtube", designLight = false))
        assertFalse(YtAppearanceOptions.applies(YtAppearanceOptions.DESIGN_DARK, "standard", designLight = false))
        assertEquals(YtDesignDark.PREF_KEY, YtAppearanceOptions.DESIGN_DARK)
    }
}
