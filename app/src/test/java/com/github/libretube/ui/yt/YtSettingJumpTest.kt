package com.github.libretube.ui.yt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtSettingJumpTest {
    @Test
    fun `pulse fades in, holds and fades out`() {
        assertEquals(0f, YtSettingJump.alphaAt(-1, true), 0f)
        assertEquals(0f, YtSettingJump.alphaAt(0, true), 0.001f)
        val rising = YtSettingJump.alphaAt(100, true)
        assertTrue(rising > 0f && rising < 1f)
        assertTrue(YtSettingJump.alphaAt(150, true) > rising)
        assertEquals(1f, YtSettingJump.alphaAt(200, true), 0f)
        assertEquals(1f, YtSettingJump.alphaAt(999, true), 0f)
        val falling = YtSettingJump.alphaAt(1300, true)
        assertTrue(falling > 0f && falling < 1f)
        assertTrue(YtSettingJump.alphaAt(1500, true) < falling)
        assertEquals(0f, YtSettingJump.alphaAt(1600, true), 0f)
        assertEquals(1600L, YtSettingJump.durationMs(true))
    }

    @Test
    fun `motion off is a plain tint without a fade`() {
        assertEquals(1f, YtSettingJump.alphaAt(0, false), 0f)
        assertEquals(1f, YtSettingJump.alphaAt(1199, false), 0f)
        assertEquals(0f, YtSettingJump.alphaAt(1200, false), 0f)
        assertEquals(1200L, YtSettingJump.durationMs(false))
    }

    @Test
    fun `the key is used once and not after a restore`() {
        assertEquals("yt_loud", YtSettingJump.keyToUse("yt_loud", restored = false))
        assertNull(YtSettingJump.keyToUse("yt_loud", restored = true))
        assertNull(YtSettingJump.keyToUse(null, restored = false))
        assertNull(YtSettingJump.keyToUse(" ", restored = false))
    }

    @Test
    fun `a hidden row falls back to the nearest shown parent`() {
        assertEquals(0, YtSettingJump.scrollTarget(listOf(true, true)))
        assertEquals(1, YtSettingJump.scrollTarget(listOf(false, true)))
        assertEquals(2, YtSettingJump.scrollTarget(listOf(true, false, true)))
        assertNull(YtSettingJump.scrollTarget(listOf(true, false)))
        assertNull(YtSettingJump.scrollTarget(emptyList()))
    }

    @Test
    fun `every setting of the guide exists in the settings xml`() {
        val dir = listOf(File("src/main/res/xml"), File("app/src/main/res/xml")).first { it.isDirectory }
        val xml = dir.listFiles()!!.filter { it.extension == "xml" }.joinToString("\n") { it.readText() }
        val items = YtGuide.sections.flatMap { it.items }
        val settings = items.mapNotNull { it.setting }
        assertTrue(settings.size >= 25)
        for (item in items) {
            val key = item.setting ?: continue
            assertTrue("${item.key}: $key", Regex("""(app|android):key="${Regex.escape(key)}"""").containsMatchIn(xml))
            assertTrue("${item.key} needs a settings screen", item.target?.isScreen == true)
        }
    }
}
