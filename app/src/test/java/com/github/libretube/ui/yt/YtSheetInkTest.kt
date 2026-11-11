package com.github.libretube.ui.yt

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.github.libretube.helpers.YtDesignDark
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YT: the words of the sheets and overlays read on the surfaces they stand on (at least 4.5:1),
 * in every design, light and dark: Feed-Regler, "Warum sehe ich das?", quick sheet, account
 * sheet (and their rows), the undo pill and time box card.
 */
class YtSheetInkTest {
    /** Palette and whether it is a light design's dark mode (ytDayNight then picks night). */
    private val palettes = listOf(
        Triple("YouTube", YtPalettes.YouTube, false),
        Triple("Movo", YtPalettes.Movo, false),
        Triple("After", YtPalettes.After, false),
        Triple("Side", YtPalettes.Side, false),
        Triple("Clay", YtPalettes.Clay, false),
        Triple("ClayDark", YtPalettes.ClayDark, true),
        Triple("Editorial", YtPalettes.Editorial, false),
        Triple("EditorialDark", YtPalettes.EditorialDark, true),
        Triple("Glass", YtPalettes.Glass, false),
        Triple("GlassDark", YtPalettes.GlassDark, true),
        Triple("Pop", YtPalettes.Pop, false),
        Triple("PopDark", YtPalettes.PopDark, true),
        Triple("Shapes", YtPalettes.Shapes, false),
        Triple("Frost", YtPalettes.Frost, false),
        Triple("Kiesel", YtPalettes.Kiesel, false),
        Triple("KieselDark", YtPalettes.KieselDark, true),
        Triple("Frequency", YtPalettes.Frequency, false),
        Triple("Synthwave", YtPalettes.Synthwave, false),
    )

    @After
    fun day() = YtDesignDark.update(designLight = false, night = false)

    private fun each(block: (String, YtPalette) -> Unit) {
        for ((name, palette, night) in palettes) {
            YtDesignDark.update(designLight = night, night = night)
            block(name, palette)
        }
    }

    private fun reads(what: String, fg: Color, back: Int) {
        val ratio = YtContrast.ratio(fg.toArgb(), back)
        assertTrue("$what: %.2f".format(ratio), ratio >= YtContrast.BODY)
    }

    private fun inkReads(what: String, ink: YtInk, backs: IntArray) {
        for (back in backs) {
            reads("$what text", ink.text, back)
            reads("$what secondary", ink.textSecondary, back)
        }
    }

    @Test
    fun `Feed-Regler and the why card read on their sheet`() = each { name, p ->
        val sheet = ytFillSheetBack(p)
        inkReads("$name feed sheet", YtInk.on(p, sheet), intArrayOf(sheet))
        // the honesty note on its accent tint
        val note = YtContrast.over(p.accent.copy(alpha = 0.12f).toArgb(), sheet)
        inkReads("$name feed note", YtInk.on(p, note), intArrayOf(note))
        // a channel's card and the chips on the sheet and on that card
        val card = YtContrast.over(p.raised.copy(alpha = 0.6f).toArgb(), sheet)
        inkReads("$name channel card", YtInk.on(p, card), intArrayOf(card))
        for ((where, under) in listOf("sheet" to sheet, "card" to card)) {
            val chip = YtContrast.over(p.raised.toArgb(), under)
            reads("$name chip on $where", YtInk.on(p, chip).text, chip)
        }
        // the word fields
        val field = YtContrast.over(p.raised.toArgb(), sheet)
        inkReads("$name field", YtInk.on(p, field), intArrayOf(field))
    }

    @Test
    fun `quick and account sheet read on the sheet and on their rows`() = each { name, p ->
        for (square in listOf(false, true)) {
            val sheet = ytScrimSheetBack(p, square)
            inkReads("$name sheet square=$square", YtInk.on(p, sheet), intArrayOf(sheet))
            val tones = ytSurfaceTones(p, sheet)
            inkReads("$name row square=$square", YtInk.onAll(p, tones), tones)
        }
    }

    @Test
    fun `undo pill and time box card read on their tone`() = each { name, p ->
        val look = pillLook(p)
        if (look.tone == Color.Unspecified) return@each
        val tone = look.tone.compositeOver(Color.Black).toArgb()
        reads("$name pill text", look.text, tone)
        val actionBack = look.actionFill?.compositeOver(look.tone)?.toArgb() ?: tone
        reads("$name pill action", look.action, actionBack)
    }

    @Test
    fun `Formen's light sheets get dark type, its dark rows keep light type`() {
        val p = YtPalettes.Shapes
        val sheet = YtInk.on(p, ytFillSheetBack(p))
        assertTrue(YtContrast.luminance(sheet.text.toArgb()) < 0.2)
        val account = YtInk.on(p, ytScrimSheetBack(p, square = false))
        assertTrue(YtContrast.luminance(account.text.toArgb()) < 0.2)
        val rows = YtInk.onAll(p, ytSurfaceTones(p, ytScrimSheetBack(p, square = false)))
        assertTrue(rows.text == p.text)
    }

    @Test
    fun `a colour that already reads is kept`() {
        val p = YtPalettes.YouTube
        val ink = YtInk.on(p, ytFillSheetBack(p))
        assertTrue(ink.text == p.text && ink.textSecondary == p.textSecondary)
    }
}
