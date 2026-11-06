package com.github.libretube.ui.yt.shorts

import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtContrast
import org.junit.Assert.assertTrue
import org.junit.Test

class YtShortsLookTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** The pills sit over the video: their text reads on the darkest and the brightest picture. */
    @Test
    fun `chip text reads over any picture in every design`() {
        for (design in YtDesign.entries) {
            val look = YtShortsLook.of(design)
            for (picture in listOf(black, white)) {
                val chip = YtContrast.over(look.chip, picture)
                val ratio = YtContrast.ratio(look.chipInk, chip)
                assertTrue("$design over ${Integer.toHexString(picture)}: $ratio", ratio >= YtContrast.BODY)
            }
        }
    }

    @Test
    fun `every design has its own page texture`() {
        val names = YtDesign.entries.map { YtShortsLook.of(it).page.name }
        assertTrue(names.toSet().size == names.size)
    }
}
