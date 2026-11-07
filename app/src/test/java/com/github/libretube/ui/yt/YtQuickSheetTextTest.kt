package com.github.libretube.ui.yt

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test

/** YT: the title and channel of the quick sheet read on the sheet's own background in every design. */
class YtQuickSheetTextTest {
    private val palettes = mapOf(
        "YouTube" to YtPalettes.YouTube,
        "Movo" to YtPalettes.Movo,
        "After" to YtPalettes.After,
        "Side" to YtPalettes.Side,
        "Clay" to YtPalettes.Clay,
        "ClayDark" to YtPalettes.ClayDark,
        "Editorial" to YtPalettes.Editorial,
        "EditorialDark" to YtPalettes.EditorialDark,
        "Glass" to YtPalettes.Glass,
        "GlassDark" to YtPalettes.GlassDark,
        "Pop" to YtPalettes.Pop,
        "PopDark" to YtPalettes.PopDark,
        "Shapes" to YtPalettes.Shapes,
        "Frost" to YtPalettes.Frost,
        "Kiesel" to YtPalettes.Kiesel,
        "KieselDark" to YtPalettes.KieselDark,
    )

    @Test
    fun `title and channel read on the sheet in every design, light and dark`() {
        for ((name, p) in palettes) {
            for (square in listOf(false, true)) {
                val back = quickSheetBackground(p, square)
                val (title, channel) = quickSheetText(p, square)
                val t = YtContrast.ratio(title.toArgb(), back)
                val c = YtContrast.ratio(channel.toArgb(), back)
                assertTrue("$name title %.2f".format(t), t >= YtContrast.BODY)
                assertTrue("$name channel %.2f".format(c), c >= YtContrast.BODY)
            }
        }
    }

    @Test
    fun `Formen's light sheet gets dark type`() {
        val (title, _) = quickSheetText(YtPalettes.Shapes, square = false)
        assertTrue(YtContrast.luminance(title.toArgb()) < 0.2)
    }
}
