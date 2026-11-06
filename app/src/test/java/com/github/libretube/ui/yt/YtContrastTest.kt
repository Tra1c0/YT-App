package com.github.libretube.ui.yt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtContrastTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `black on white is 21 to 1`() {
        assertEquals(21.0, YtContrast.ratio(black, white), 0.01)
        assertEquals(1.0, YtContrast.ratio(white, white), 0.001)
    }

    @Test
    fun `a colour that reads stays as it is`() {
        val ink = 0xFF0F2B2E.toInt() // Glas text on its mint
        val mint = 0xFFD1EAE3.toInt()
        assertEquals(ink, YtContrast.readable(ink, mint))
    }

    /** The pairs that did not read in the chooser of build check-64, and what they become. */
    @Test
    fun `the weak pairs of the light designs reach 4,5 to 1`() {
        val pairs = listOf(
            0xFF8E7A78.toInt() to 0xFFF5D8C7.toInt(), // Clay grey on peach
            0xFF5C7C7C.toInt() to 0xFFD1EAE3.toInt(), // Glas grey on mint
            0xFF7A6A8F.toInt() to 0xFFE6E5FD.toInt(), // Pop grey on lavender
            0xFF7A7672.toInt() to 0xFFE9E5E0.toInt(), // Kiesel grey on stone
            0x24FFFFFF to 0xFFFFFFFF.toInt(), // Editorial white on white (the old minute buttons)
            0xFFFDE682.toInt() to 0xFFE6E5FD.toInt(), // Pop lime words on lavender
            0xFFD5E0DA.toInt() to 0xFF6F8A80.toInt(), // Frost pale grey on sage
        )
        for ((fg, bg) in pairs) {
            val fixed = YtContrast.readable(fg, bg)
            assertTrue("%08x on %08x".format(fixed, bg), YtContrast.ratio(fixed, bg) >= YtContrast.BODY)
        }
    }

    @Test
    fun `translucent words count as composited`() {
        // white at 14 % over white is white
        assertEquals(1.0, YtContrast.ratio(0x24FFFFFF, white), 0.001)
        val over = YtContrast.over(0x80000000.toInt(), white)
        assertEquals(0xFF7F7F7F.toInt(), over)
    }

    @Test
    fun `dark grounds push words towards white`() {
        val fixed = YtContrast.readable(0xFF555555.toInt(), 0xFF101014.toInt())
        assertTrue(YtContrast.luminance(fixed) > YtContrast.luminance(0xFF555555.toInt()))
        assertTrue(YtContrast.ratio(fixed, 0xFF101014.toInt()) >= 4.5)
    }
}
