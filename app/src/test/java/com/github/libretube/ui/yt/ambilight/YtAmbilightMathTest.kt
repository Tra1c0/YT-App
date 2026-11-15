package com.github.libretube.ui.yt.ambilight

import com.github.libretube.ui.yt.YtContrast
import com.github.libretube.ui.yt.ambilight.YtAmbilightZones as Z
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.sqrt

class YtAmbilightMathTest {
    private val w = 48
    private val h = 27
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFE02020.toInt()
    private val blue = 0xFF2040E0.toInt()
    private val green = 0xFF20C040.toInt()
    private val grey = 0xFF808080.toInt()

    private fun frame(fill: Int) = IntArray(w * h) { fill }

    private fun IntArray.rect(l: Int, t: Int, r: Int, b: Int, color: Int) = apply {
        for (y in t until b) for (x in l until r) this[y * w + x] = color
    }

    private fun area(pixels: IntArray) = IntArray(4).also { YtAmbilightMath.activeArea(pixels, w, h, it) }

    /** The targets (Oklab + presence) of a frame, letterbox cut first. */
    private fun targets(pixels: IntArray): FloatArray {
        val raw = FloatArray(Z.COUNT * Z.RAW)
        val out = FloatArray(Z.COUNT * Z.CHANNELS)
        YtAmbilightMath.zones(pixels, w, area(pixels), raw, FloatArray(YtAmbilightMath.SUMS_SIZE))
        YtAmbilightMath.targets(raw, out, FloatArray(3))
        return out
    }

    /** Hue angle (degrees) of a zone's Oklab colour. */
    private fun hue(t: FloatArray, zone: Int): Double {
        val o = zone * Z.CHANNELS
        return Math.toDegrees(atan2(t[o + 2].toDouble(), t[o + 1].toDouble()))
    }

    private fun chroma(t: FloatArray, zone: Int): Float {
        val o = zone * Z.CHANNELS
        return sqrt(t[o + 1] * t[o + 1] + t[o + 2] * t[o + 2])
    }

    // -- letterbox ------------------------------------------------------------------------------

    @Test
    fun `a full picture has no bars`() {
        assertArrayEquals(intArrayOf(0, 0, w, h), area(frame(grey)))
    }

    @Test
    fun `letterbox bars above and below are cut`() {
        val f = frame(black).rect(0, 4, w, h - 4, green)
        assertArrayEquals(intArrayOf(0, 4, w, h - 4), area(f))
    }

    @Test
    fun `pillarbox bars beside are cut`() {
        val f = frame(black).rect(7, 0, w - 7, h, red)
        assertArrayEquals(intArrayOf(7, 0, w - 7, h), area(f))
    }

    @Test
    fun `a subtitle line inside the bottom bar does not end the bar`() {
        val f = frame(black).rect(0, 5, w, h - 5, blue)
        // one light row of letters inside the bottom bar, 2 rows above its outer end
        for (x in 10 until 38 step 2) f[(h - 3) * w + x] = white
        assertArrayEquals(intArrayOf(0, 5, w, h - 5), area(f))
    }

    @Test
    fun `a dark sky on one side only is picture, not a bar`() {
        val f = frame(green).rect(0, 0, w, 7, black)
        assertArrayEquals(intArrayOf(0, 0, w, h), area(f))
    }

    @Test
    fun `a black frame is taken whole and glows little`() {
        val f = frame(black)
        assertArrayEquals(intArrayOf(0, 0, w, h), area(f))
        val t = targets(f)
        for (z in 0 until Z.COUNT) assertEquals(0f, t[z * Z.CHANNELS + 3], 0.01f)
    }

    // -- zones ------------------------------------------------------------------------------------

    @Test
    fun `each edge zone takes the colour of its edge`() {
        // red top band, blue bottom band, grey middle
        val f = frame(grey).rect(0, 0, w, 8, red).rect(0, h - 8, w, h, blue)
        val t = targets(f)
        val redHue = hue(t, Z.TOP)
        val blueHue = hue(t, Z.BOTTOM)
        assertTrue("top is red (hue $redHue)", redHue in 0.0..60.0)
        assertTrue("bottom is blue (hue $blueHue)", blueHue in -120.0..-60.0)
        assertTrue(hue(t, Z.TOP_LEFT) in 0.0..60.0 && hue(t, Z.TOP_RIGHT) in 0.0..60.0)
        assertTrue(hue(t, Z.BOTTOM_LEFT) in -120.0..-60.0 && hue(t, Z.BOTTOM_RIGHT) in -120.0..-60.0)
        // the sides are mostly grey
        assertTrue(chroma(t, Z.LEFT) < chroma(t, Z.TOP))
    }

    @Test
    fun `the zones are measured inside the letterbox, not on the bars`() {
        val f = frame(black).rect(0, 4, w, h - 4, green)
        val t = targets(f)
        for (z in 0 until Z.COUNT) {
            assertTrue("zone $z is present", t[z * Z.CHANNELS + 3] > 0.9f)
            assertTrue("zone $z is green", hue(t, z) in 110.0..170.0)
        }
    }

    @Test
    fun `black and near-white pixels count little, colour counts more`() {
        // the top band: half black, a few white subtitle-like pixels, the rest red
        val f = frame(grey).rect(0, 0, w, 8, red).rect(16, 0, 32, 4, black)
        for (x in 16 until 32) f[5 * w + x] = white
        val t = targets(f)
        val o = Z.TOP * Z.CHANNELS
        // still clearly red and fairly bright, not a dark muddy pink
        assertTrue(hue(t, Z.TOP) in 0.0..60.0)
        assertTrue("lightness ${t[o]}", t[o] > 0.5f)
        assertTrue(chroma(t, Z.TOP) > 0.15f)
    }

    @Test
    fun `greys stay grey, colours are boosted but capped`() {
        val t = targets(frame(grey))
        for (z in 0 until Z.COUNT) assertTrue(chroma(t, z) < 0.01f)
        val vivid = targets(frame(0xFFFF00FF.toInt()))
        for (z in 0 until Z.COUNT) assertTrue(chroma(vivid, z) <= YtAmbilightMath.CHROMA_MAX + 1e-4f)
    }

    @Test
    fun `oklab round trip gives the colour back`() {
        val lab = FloatArray(3)
        val scratch = FloatArray(3)
        for (c in intArrayOf(red, blue, green, grey, white, 0xFF123456.toInt())) {
            YtAmbilightMath.oklab(
                YtAmbilightMath.linear(c shr 16), YtAmbilightMath.linear(c shr 8), YtAmbilightMath.linear(c), lab,
            )
            val back = YtAmbilightMath.argb(lab[0], lab[1], lab[2], scratch)
            for (shift in intArrayOf(16, 8, 0)) {
                val d = kotlin.math.abs(((c shr shift) and 0xFF) - ((back shr shift) and 0xFF))
                assertTrue("channel off by $d", d <= 1)
            }
        }
    }

    @Test
    fun `the glow is luminous on dark backgrounds and pale on light ones`() {
        val scratch = FloatArray(3)
        val dark = YtAmbilightMath.glow(0.25f, 0.1f, 0.05f, lightBack = false, scratch = scratch)
        val light = YtAmbilightMath.glow(0.25f, 0.1f, 0.05f, lightBack = true, scratch = scratch)
        assertTrue(YtContrast.luminance(dark) > 0.05)
        assertTrue(YtContrast.luminance(light) > 0.5)
    }

    // -- smoothing --------------------------------------------------------------------------------

    private fun displayOf(lightness: Float, presence: Float) = FloatArray(Z.DISPLAY * Z.CHANNELS) { i ->
        when (i % Z.CHANNELS) {
            0 -> lightness
            3 -> presence
            else -> 0f
        }
    }

    @Test
    fun `the glow fades in and glides, never jumps`() {
        val s = YtAmbilightSmoother()
        val target = displayOf(0.6f, 1f)
        s.step(target, 0.033f, 0.55f)
        // colours primed, presence rising from 0
        assertEquals(0.6f, s.value[0], 1e-5f)
        assertTrue(s.value[3] in 0.01f..0.2f)
        val next = displayOf(0.2f, 1f)
        val step = s.step(next, 0.033f, 0.55f)
        assertTrue("one frame moves little ($step)", step < 0.08f)
        assertTrue("lightness glides (${s.value[0]})", s.value[0] in 0.55f..0.6f)
        repeat(200) { s.step(next, 0.033f, 0.55f) }
        assertEquals(0.2f, s.value[0], 1e-3f)
    }

    @Test
    fun `the glide does not depend on the frame rate`() {
        val a = YtAmbilightSmoother()
        val b = YtAmbilightSmoother()
        val start = displayOf(0.8f, 1f)
        a.snap(start)
        b.snap(start)
        val target = displayOf(0.2f, 1f)
        repeat(30) { a.step(target, 1f / 30f, 0.5f) }
        repeat(120) { b.step(target, 1f / 120f, 0.5f) }
        assertEquals(a.value[0], b.value[0], 1e-3f)
    }

    // -- scene cuts -------------------------------------------------------------------------------

    @Test
    fun `a cut is found, a still scene is not, and cuts do not race`() {
        val scene = YtAmbilightScene()
        val redScene = targets(frame(red))
        val blueScene = targets(frame(blue))
        assertTrue("the first sample starts a scene", scene.update(redScene, 0f))
        assertFalse(scene.update(redScene, 1f))
        assertFalse(scene.update(redScene, 1.2f))
        assertEquals(YtAmbilightScene.TAU, scene.tau(1.2f), 0f)
        assertTrue(scene.update(blueScene, 2f))
        assertEquals(YtAmbilightScene.TAU_CUT, scene.tau(2.1f), 0f)
        assertEquals(YtAmbilightScene.TAU, scene.tau(2f + YtAmbilightScene.FAST_S + 0.01f), 0f)
        // a strobe: the next cut within half a second counts once
        assertFalse(scene.update(redScene, 2.2f))
        assertTrue(scene.update(blueScene, 2.8f))
    }

    @Test
    fun `a busy picture raises the activity`() {
        val scene = YtAmbilightScene()
        val a = targets(frame(red))
        val b = targets(frame(blue))
        scene.update(a, 0f)
        repeat(10) { scene.update(a, it * 0.1f + 0.1f) }
        val still = scene.activity
        repeat(10) { scene.update(if (it % 2 == 0) b else a, 2f + it * 0.1f) }
        assertTrue(still < 0.01f)
        assertTrue(scene.activity > YtAmbilightRules.BUSY)
    }

    // -- contrast ---------------------------------------------------------------------------------

    @Test
    fun `a bright tint gives way so white text keeps 4,5 to 1`() {
        val back = 0xFF101014.toInt()
        val yellow = 0xFFFFE040.toInt()
        val alpha = YtAmbilightMath.maxAlpha(white, back, yellow, 0.9f)
        assertTrue(alpha < 0.9f)
        val tinted = YtContrast.mix(back, yellow, alpha.toDouble())
        assertTrue(YtContrast.ratio(white, tinted) >= YtContrast.BODY - 0.01)
    }

    @Test
    fun `a dark tint may glow fully`() {
        val back = 0xFF101014.toInt()
        val deepBlue = 0xFF1A2A6A.toInt()
        assertEquals(0.6f, YtAmbilightMath.maxAlpha(white, back, deepBlue, 0.6f), 0f)
    }

    @Test
    fun `text that read less than 4,5 never reads worse`() {
        val back = 0xFF101014.toInt()
        val weak = 0xFF5A5A66.toInt()
        val before = YtContrast.ratio(weak, back)
        assertTrue(before < YtContrast.BODY)
        val tint = 0xFF707070.toInt()
        val alpha = YtAmbilightMath.maxAlpha(weak, back, tint, 0.9f)
        assertTrue(YtContrast.ratio(weak, YtContrast.mix(back, tint, alpha.toDouble())) >= before - 0.01)
    }

    @Test
    fun `dark text on a light design keeps its contrast under a pale glow`() {
        val back = 0xFFF5D8C7.toInt() // Clay peach
        val ink = 0xFF3B2A28.toInt()
        val glow = YtAmbilightMath.glow(0.7f, 0.15f, 0.1f, lightBack = true, scratch = FloatArray(3))
        val alpha = YtAmbilightMath.maxAlpha(ink, back, glow, 0.9f)
        assertTrue(YtContrast.ratio(ink, YtContrast.mix(back, glow, alpha.toDouble())) >= YtContrast.BODY - 0.01)
    }

    @Test
    fun `layers split the cap so together they never exceed it`() {
        val cap = 0.5f
        val each = YtAmbilightMath.perLayer(cap, 3)
        val together = 1f - (1f - each) * (1f - each) * (1f - each)
        assertEquals(cap, together, 1e-4f)
        assertEquals(cap, YtAmbilightMath.perLayer(cap, 1), 0f)
    }
}
