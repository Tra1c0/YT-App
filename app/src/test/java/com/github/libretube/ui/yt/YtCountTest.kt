package com.github.libretube.ui.yt

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class YtCountTest {
    private val deUnits = YtCountUnits("~%sk", "~%s Mio.", "~%s Mrd.")
    private val enUnits = YtCountUnits("~%sk", "~%sM", "~%sB")
    private fun de(n: Long) = YtCountFormat.format(n, Locale.GERMANY, deUnits)
    private fun en(n: Long) = YtCountFormat.format(n, Locale.US, enUnits)

    @Test
    fun `0 to 999 exactly`() {
        assertEquals("0", de(0))
        assertEquals("7", de(7))
        assertEquals("999", de(999))
        assertEquals("0", de(-1))
    }

    @Test
    fun `thousands with one decimal below ten`() {
        assertEquals("~1k", de(1_000))
        assertEquals("~1,5k", de(1_549))
        assertEquals("~1.5k", en(1_549))
        assertEquals("~1,9k", de(1_999))
        assertEquals("~2k", de(2_000))
        assertEquals("~2k", de(2_099))
        assertEquals("~9,9k", de(9_999))
    }

    @Test
    fun `whole thousands from ten`() {
        assertEquals("~10k", de(10_000))
        assertEquals("~27k", de(27_400))
        assertEquals("~327k", de(327_000))
        assertEquals("~971k", en(971_000))
        assertEquals("~999k", de(999_999))
    }

    @Test
    fun `millions and billions`() {
        assertEquals("~1 Mio.", de(1_000_000))
        assertEquals("~1,2 Mio.", de(1_230_000))
        assertEquals("~1.2M", en(1_230_000))
        assertEquals("~27 Mio.", de(27_900_000))
        assertEquals("~999 Mio.", de(999_999_999))
        assertEquals("~1,1 Mrd.", de(1_100_000_000))
        assertEquals("~1.1B", en(1_100_000_000))
        assertEquals("~15B", en(15_000_000_000))
    }
}
