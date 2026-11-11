package com.github.libretube.ui.yt

import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.mini.YtMiniDesigns
import com.github.libretube.ui.yt.mini.YtMiniFrequencyTextures
import com.github.libretube.ui.yt.mini.YtMiniSpecs
import com.github.libretube.ui.yt.mini.YtMiniSynthwaveTextures
import com.github.libretube.ui.yt.mini.YtMoment
import com.github.libretube.ui.yt.search.YtSearchKind
import com.github.libretube.ui.yt.search.YtSearchPalette
import com.github.libretube.ui.yt.shorts.YtShortsLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * YT: every design is complete in the registries that tell the designs apart, so a new design
 * (Frequency, the 13th, Synthwave, the 14th) cannot silently fall back to another one's look.
 */
class YtDesignRegistryTest {
    private val designs = YtDesign.entries.filter { it != YtDesign.STANDARD }

    @Test
    fun `every design has its own palette and signature`() {
        assertNull(YtDesign.STANDARD.palette)
        val signatures = designs.map { design ->
            val palette = assertNotNullAnd("$design palette", design.palette)
            palette.signature
        }
        assertEquals("one signature per design", signatures.size, signatures.toSet().size)
        assertEquals("every signature is used", YtSignature.entries.toSet(), signatures.toSet())
    }

    @Test
    fun `every design has its own mini player`() {
        for (design in YtDesign.entries) {
            assertEquals("$design mini player", design, YtMiniDesigns.of(design).design)
        }
    }

    @Test
    fun `every design has a bar with tabs, an overlay and a timeline colour`() {
        for (design in designs) {
            assertTrue("$design nav items", YtNav.items(design).isNotEmpty())
            assertNotNull("$design overlay", design.overlay)
            assertNotNull("$design timeline colour", design.timelineColor)
            assertNotNull("$design home label", design.navigationLabel(R.id.homeFragment))
        }
    }

    @Test
    fun `every design has a search of its own`() {
        for (design in designs) {
            val kind = YtSearchKind.valueOf(design.palette!!.signature.name)
            assertNotNull(YtSearchPalette.inks(kind))
        }
        assertEquals(YtSignature.entries.map { it.name }.toSet(), YtSearchKind.entries.map { it.name }.toSet())
    }

    @Test
    fun `every design has its own Shorts look`() {
        val names = YtDesign.entries.map { YtShortsLook.of(it).page.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(YtShortsLook.of(YtDesign.FREQUENCY).page.name.endsWith("frequency"))
        assertTrue(YtShortsLook.of(YtDesign.SYNTHWAVE).page.name.endsWith("synthwave"))
    }

    @Test
    fun `Frequency has its own mini player feel`() {
        for (moment in YtMoment.entries) {
            assertTrue(moment.name, YtMiniFrequencyTextures.of(moment).name.endsWith("frequency"))
        }
    }

    @Test
    fun `Synthwave has its own mini player feel and measures`() {
        for (moment in YtMoment.entries) {
            assertTrue(moment.name, YtMiniSynthwaveTextures.of(moment).name.endsWith("synthwave"))
        }
        assertEquals(YtMiniSpecs.SYNTHWAVE, YtMiniDesigns.of(YtDesign.SYNTHWAVE).spec)
    }

    @Test
    fun `Synthwave has Shorts, Abos and Du as tabs and is sound-reactive`() {
        val ids = YtNav.items(YtDesign.SYNTHWAVE).map { it.id }
        assertEquals(listOf(R.id.homeFragment, R.id.trendsFragment, R.id.subscriptionsFragment, R.id.libraryFragment), ids)
        assertTrue(YtDesign.SYNTHWAVE.hasShortsTab)
        assertEquals(R.string.yt_nav_shorts, YtDesign.SYNTHWAVE.navigationLabel(R.id.trendsFragment))
        assertTrue(YtDesign.SYNTHWAVE.soundReactive)
        assertTrue(YtDesign.FREQUENCY.soundReactive)
        for (design in YtDesign.entries - YtDesign.SYNTHWAVE - YtDesign.FREQUENCY) assertTrue(design.name, !design.soundReactive)
        assertEquals("synthwave", YtDesign.SYNTHWAVE.key)
        assertEquals("SYNTHWAVE", YtDesign.SYNTHWAVE.wordmarkHtml("#000000"))
    }

    @Test
    fun `Frequency has Shorts, Abos and Du as tabs`() {
        val ids = YtNav.items(YtDesign.FREQUENCY).map { it.id }
        assertEquals(listOf(R.id.homeFragment, R.id.trendsFragment, R.id.subscriptionsFragment, R.id.libraryFragment), ids)
        assertTrue(YtDesign.FREQUENCY.hasShortsTab)
        assertEquals(R.string.yt_nav_shorts, YtDesign.FREQUENCY.navigationLabel(R.id.trendsFragment))
    }

    @Test
    fun `the design picker lists every design`() {
        val file = listOf(File("src/main/res/values/yt_arrays.xml"), File("app/src/main/res/values/yt_arrays.xml")).first { it.isFile }
        val xml = file.readText()
        fun items(name: String): List<String> {
            val start = xml.indexOf("name=\"$name\"")
            val end = xml.indexOf("</string-array>", start)
            return Regex("<item>([^<]*)</item>").findAll(xml.substring(start, end)).map { it.groupValues[1] }.toList()
        }
        val values = items("yt_designs_values")
        assertEquals(YtDesign.entries.map { it.key }, values)
        assertEquals(values.size, items("yt_designs").size)
    }

    private fun <T> assertNotNullAnd(message: String, value: T?): T {
        assertNotNull(message, value)
        return value!!
    }
}
