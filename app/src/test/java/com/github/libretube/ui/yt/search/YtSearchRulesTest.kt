package com.github.libretube.ui.yt.search

import com.github.libretube.ui.yt.YtContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class YtSearchRulesTest {
    @Test
    fun `an empty or blank field searches for nothing`() {
        assertNull(YtSearchRules.query(""))
        assertNull(YtSearchRules.query("   "))
        assertEquals("lofi", YtSearchRules.query("  lofi "))
        assertFalse(YtSearchRules.fetches(" ", true))
        assertFalse(YtSearchRules.fetches("lofi", false))
        assertTrue(YtSearchRules.fetches("lofi", true))
    }

    @Test
    fun `history for an empty field is the newest, without doubles`() {
        val all = (1..20).map { "q$it" } + listOf("Q1", " ")
        val shown = YtSearchRules.history(all, "")
        assertEquals(YtSearchRules.HISTORY_EMPTY, shown.size)
        assertEquals("q1", shown.first())
        assertFalse(shown.any { it.isBlank() })
    }

    @Test
    fun `history while typing starts with the text, ignoring case`() {
        val all = listOf("Lofi beats", "lofi girl", "jazz", "LOFI 24/7", "lo", "lofi a", "lofi b", "lofi c")
        val shown = YtSearchRules.history(all, " lofi")
        assertEquals(listOf("Lofi beats", "lofi girl", "LOFI 24/7", "lofi a", "lofi b"), shown)
    }

    @Test
    fun `suggestions follow the history and skip what it already has`() {
        val merged = YtSearchRules.merge(listOf("lofi girl"), listOf("Lofi Girl", "lofi beats", "", "lofi jazz"))
        assertEquals(
            listOf(YtSearchItem("lofi girl", true), YtSearchItem("lofi beats", false), YtSearchItem("lofi jazz", false)),
            merged,
        )
        val many = YtSearchRules.merge(emptyList(), (1..30).map { "s$it" })
        assertEquals(YtSearchRules.SUGGESTIONS, many.size)
    }

    @Test
    fun `editorial numbers with two digits`() {
        assertEquals("01", YtSearchRules.number(0))
        assertEquals("10", YtSearchRules.number(9))
        assertEquals("120", YtSearchRules.number(119))
    }

    @Test
    fun `only real filters other than all go to the results`() {
        assertNull(YtSearchRules.filterFor(null))
        assertNull(YtSearchRules.filterFor("all"))
        assertNull(YtSearchRules.filterFor("bogus"))
        assertEquals("channels", YtSearchRules.filterFor("channels"))
    }

    @Test
    fun `phases and stagger stay in range`() {
        assertEquals(0f, YtSearchRules.phase(0.2f, 0.4f, 0.8f), 0f)
        assertEquals(0.5f, YtSearchRules.phase(0.6f, 0.4f, 0.8f), 1e-4f)
        assertEquals(1f, YtSearchRules.phase(1.3f, 0.4f, 0.8f), 0f)
        assertEquals(1f, YtSearchRules.phase(0.5f, 0.5f, 0.5f), 0f)
        assertEquals(0, YtSearchRules.stagger(0, 30, 200))
        assertEquals(200, YtSearchRules.stagger(50, 30, 200))
    }

    @Test
    fun `the pebbles fall and rest`() {
        assertEquals(0f, YtSearchRules.bounce(0f), 1e-4f)
        assertEquals(1f, YtSearchRules.bounce(1f), 1e-4f)
        assertEquals(1f, YtSearchRules.bounce(2f), 1e-4f)
        for (i in 0..100) {
            val v = YtSearchRules.bounce(i / 100f)
            assertTrue(v >= 0f && v <= 1.0001f)
        }
    }

    @Test
    fun `the field starts as large as its button`() {
        assertEquals(0.125f, YtSearchRules.startScale(40f, 320f), 1e-4f)
        assertEquals(1f, YtSearchRules.startScale(40f, 0f), 0f)
        assertEquals(1f, YtSearchRules.startScale(400f, 320f), 0f)
        assertEquals(0.5f, YtSearchRules.pivot(150f, 100f, 100f), 1e-4f)
        assertEquals(4f, YtSearchRules.pivot(5000f, 100f, 100f), 0f)
        assertEquals(1.5f, YtSearchRules.pivot(250f, 100f, 100f), 1e-4f)
    }

    @Test
    fun `the cookie becomes the field`() {
        val hw = 160f
        val hh = 24f
        // a cookie as wide as the field is high
        for (i in 0 until 36) {
            val a = 2 * PI * i / 36
            val r = YtSearchRules.morphRadius(a, hw, hh, 9, 0.07f, 0f)
            assertTrue(r <= hh + 1e-3f && r >= hh * 0.85f)
        }
        // the field: right through the middle of each side
        assertEquals(hw, YtSearchRules.morphRadius(0.0, hw, hh, 9, 0.07f, 1f), 0.5f)
        assertEquals(hh, YtSearchRules.morphRadius(PI / 2, hw, hh, 9, 0.07f, 1f), 0.5f)
        val pts = YtSearchRules.morphPoints(2 * hw, 2 * hh, 9, 0.07f, 1f)
        for (j in pts.indices step 2) {
            assertTrue(pts[j] >= -0.5f && pts[j] <= 2 * hw + 0.5f)
            assertTrue(pts[j + 1] >= -0.5f && pts[j + 1] <= 2 * hh + 0.5f)
        }
    }

    @Test
    fun `a tap counts as the origin for a short while`() {
        assertTrue(YtSearchRules.originFresh(1000, 1200))
        assertFalse(YtSearchRules.originFresh(0, 1200))
        assertFalse(YtSearchRules.originFresh(1000, 1000 + YtSearchRules.ORIGIN_MAX_AGE_MS + 1))
        assertFalse(YtSearchRules.originFresh(2000, 1000))
    }

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun reads(what: String, fg: Int, bg: Int) {
        val ratio = YtContrast.ratio(fg, bg)
        assertTrue("$what: %.2f".format(ratio), ratio >= YtContrast.BODY)
    }

    @Test
    fun `every text of every design reads on a black and on a white screen`() {
        for (kind in YtSearchKind.values()) {
            val inks = YtSearchPalette.inks(kind)
            for (screen in listOf(black, white)) {
                val base = YtContrast.over(inks.scrim, screen)
                val field = YtContrast.over(inks.field, base)
                reads("$kind field", inks.fieldText, field)
                reads("$kind hint", inks.fieldHint, field)
                val panel = YtContrast.over(inks.panel, base)
                reads("$kind row", inks.rowText, panel)
                reads("$kind secondary", inks.rowSecondary, panel)
                val chip = YtContrast.over(inks.chip, panel)
                reads("$kind chip", inks.chipText, chip)
                for (i in inks.tiles.indices) {
                    val fill = YtSearchPalette.tile(kind, i)
                    reads("$kind tile $i", YtSearchPalette.onFill(fill), YtContrast.over(fill, panel))
                }
            }
        }
    }

    @Test
    fun `tiles cycle`() {
        val a = YtSearchPalette.tile(YtSearchKind.MOVO, 0)
        assertEquals(a, YtSearchPalette.tile(YtSearchKind.MOVO, 4))
        assertEquals(YtSearchPalette.inks(YtSearchKind.GLASS).chip, YtSearchPalette.tile(YtSearchKind.GLASS, 3))
        assertTrue(abs(YtSearchPalette.onFill(white)) >= 0)
        assertEquals(0xFF0E0E10.toInt(), YtSearchPalette.onFill(white))
        assertEquals(white, YtSearchPalette.onFill(black))
    }
}
