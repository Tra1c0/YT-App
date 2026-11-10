package com.github.libretube.ui.yt

import androidx.compose.ui.graphics.toArgb
import com.github.libretube.ui.yt.FrequencyPillBars as B
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** YT: the equalizer on Frequency's subscribe pill (FrequencyPillBars). */
class FrequencyPillBarsTest {
    private val p = YtPalettes.Frequency

    @Test
    fun `the bar count follows the width within the tier`() {
        val slot = 15f // 5 dp at 3x
        assertEquals(0, B.count(0f, slot, 16))
        assertEquals(0, B.count(-4f, slot, 16))
        assertEquals(0, B.count(100f, 0f, 16))
        // a narrow pill still gets the fewest bars, a wide one at most the tier's limit
        assertEquals(B.MIN_BARS, B.count(40f, slot, 16))
        assertEquals(12, B.count(12 * slot + 3f, slot, 16))
        assertEquals(16, B.count(400f * 3f, slot, FrequencyTier.HIGH.bars))
        assertEquals(12, B.count(400f * 3f, slot, FrequencyTier.MID.bars))
        assertEquals(8, B.count(400f * 3f, slot, FrequencyTier.LOW.bars))
        // a typical pill (~88 dp between the insets) on phones of 2x..3.5x
        for (density in listOf(2f, 2.625f, 3f, 3.5f)) {
            val n = B.count(88f * density, B.SLOT_DP * density, FrequencyTier.HIGH.bars)
            assertTrue("bars at $density: $n", n in 10..16)
            assertTrue(B.count(88f * density, B.SLOT_DP * density, FrequencyTier.LOW.bars) < n)
        }
        assertTrue(B.count(88f * 3f, 15f, 30) <= B.MAX_BARS)
    }

    @Test
    fun `at rest the bars stand in a staggered arch, not flat`() {
        for (n in listOf(8, 10, 12, 16)) {
            val h = FloatArray(n) { B.rest(it, n) }
            for (v in h) assertTrue(v in B.REST_MIN..B.REST_MAX)
            assertTrue("range at $n", h.max() - h.min() >= 0.25f)
            val third = n / 3
            val ends = (h.take(third) + h.takeLast(third)).average()
            val middle = h.slice(third until n - third).average()
            assertTrue("arch at $n: $middle vs $ends", middle > ends + 0.1)
            // staggered: not a smooth hill, some neighbours go the other way
            var turns = 0
            for (i in 1 until n - 1) if ((h[i] - h[i - 1]) * (h[i + 1] - h[i]) < 0f) turns++
            assertTrue("stagger at $n: $turns", turns >= 2)
            // the silence of the pulse (bands 0, no bounce) is exactly this arch
            val out = FloatArray(B.MAX_BARS)
            B.heights(out, n, FloatArray(16), 1f)
            for (i in 0 until n) assertEquals(h[i], out[i], 1e-6f)
        }
    }

    @Test
    fun `heights follow the band and the spring, clamped`() {
        val rest = 0.4f
        assertEquals(rest, B.height(rest, 0f, 0f, 1f), 1e-6f)
        assertEquals(rest, B.height(rest, 1f, 1f, 0f), 1e-6f)
        assertTrue(B.height(rest, 0.5f, 0f, 1f) > rest)
        assertTrue(B.height(rest, 1f, 0f, 1f) > B.height(rest, 0.5f, 0f, 1f))
        assertTrue(B.height(rest, 0.5f, 0.5f, 1f) > B.height(rest, 0.5f, 0f, 1f))
        assertTrue(B.height(rest, 1f, 1.2f, 1f) <= 1f)
        // the spring's overshoot dips a bar a little below its rest, never further than MAX_DIP
        val dip = B.height(rest, 0f, -10f, 1f)
        assertTrue("dip $dip", dip < rest && dip >= rest * (1f - B.MAX_DIP))
        assertTrue(B.height(rest, 1f, 0f, FrequencyTier.LOW.amplitude) < B.height(rest, 1f, 0f, 1f))
        assertEquals(rest, B.height(rest, Float.NaN, Float.NaN, 1f), 1e-6f)
    }

    @Test
    fun `low bands drive the first bars, high bands the last`() {
        val n = 12
        val low = FloatArray(16).also { for (i in 0 until 4) it[i] = 1f }
        val out = FloatArray(n)
        B.heights(out, n, low, 1f)
        assertTrue(out[0] > B.rest(0, n) + 0.3f)
        assertEquals(B.rest(n - 1, n), out[n - 1], 1e-6f)
        val high = FloatArray(16).also { for (i in 12 until 16) it[i] = 1f }
        out.fill(0f)
        B.heights(out, n, high, 1f)
        assertEquals(B.rest(0, n), out[0], 1e-6f)
        assertTrue(out[n - 1] > B.rest(n - 1, n) + 0.3f)
    }

    @Test
    fun `right to left mirrors the bars`() {
        assertEquals(0, B.barAt(0, 10, rtl = false))
        assertEquals(9, B.barAt(0, 10, rtl = true))
        assertEquals(0, B.barAt(9, 10, rtl = true))
        assertEquals(0, B.springIndex(0, 10))
        assertEquals(B.SPRING_SPREAD, B.springIndex(9, 10))
        assertEquals(0, B.springIndex(0, 1))
    }

    @Test
    fun `the label reads on the bars in both states`() {
        val ink = p.ink.toArgb()
        val a = frequencyPillBarAlpha(p)
        // visible, and within the cap
        assertTrue("alpha $a", a >= 0.1f && a <= B.ALPHA_CAP)
        val coral = p.accent.toArgb()
        val violet = p.lavender.toArgb()
        for ((name, fill) in listOf("Abonnieren (coral)" to coral, "Abonniert (violet)" to violet)) {
            val bare = YtContrast.ratio(ink, fill)
            val worst = YtContrast.ratio(ink, B.under(ink, a, fill))
            println("pill bars: $name alpha %.2f: label %.2f:1 bare, %.2f:1 on a full bar".format(a, bare, worst))
            assertTrue("$name on a full bar: %.2f".format(worst), worst >= YtContrast.BODY)
        }
        // and on every colour of the change between them
        for (fill in frequencyPillFills(p)) assertTrue(YtContrast.ratio(ink, B.under(ink, a, fill)) >= YtContrast.BODY)
        // the next step up would break it on one of them, or the cap holds
        val next = a + 0.01f
        assertTrue(next > B.ALPHA_CAP || frequencyPillFills(p).any { YtContrast.ratio(ink, B.under(ink, next, it)) < YtContrast.BODY })
    }

    @Test
    fun `no alpha when the bare fill does not read`() {
        assertEquals(0f, B.safeAlpha(0xFF777777.toInt(), intArrayOf(0xFF7A7A7A.toInt())), 0f)
    }
}
