package com.github.libretube.ui.yt.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class YtResponsiveTest {
    private val node = YtNode(
        "n", width = YtSize.fixed(100f),
        overrides = listOf(
            YtOverride(width = YtWidthClass.EXPANDED, orientation = YtOrientation.LANDSCAPE, patch = YtPatch(width = YtSize.fixed(300f))),
            YtOverride(orientation = YtOrientation.LANDSCAPE, patch = YtPatch(width = YtSize.fixed(200f), visible = false)),
            YtOverride(minFontScale = 1.3f, patch = YtPatch(textSize = 18f)),
        ),
        text = YtTextSpec(),
        states = mapOf(YtStates.SELECTED to YtPatch(width = YtSize.fixed(999f))),
    )

    @Test
    fun `width classes and orientation`() {
        assertEquals(YtWidthClass.COMPACT, YtEnv(393f, 852f).widthClass)
        assertEquals(YtWidthClass.MEDIUM, YtEnv(700f, 900f).widthClass)
        assertEquals(YtWidthClass.EXPANDED, YtEnv(900f, 400f).widthClass)
        assertEquals(YtOrientation.LANDSCAPE, YtEnv(900f, 400f).orientation)
        assertEquals(YtOrientation.PORTRAIT, YtEnv.PHONE.orientation)
    }

    @Test
    fun `nothing applies on a phone in portrait`() {
        assertSame(node.copy().width, YtResponsive.resolve(node, YtEnv.PHONE).width)
        assertEquals(100f, YtResponsive.resolve(node, YtEnv.PHONE).width.value)
    }

    @Test
    fun `the more specific override wins`() {
        val r = YtResponsive.resolve(node, YtEnv(1000f, 600f))
        assertEquals(300f, r.width.value)
        // the less specific one still applies where the other says nothing
        assertEquals(false, r.visible)
        assertEquals(200f, YtResponsive.resolve(node, YtEnv(700f, 500f)).width.value)
    }

    @Test
    fun `font scale`() {
        assertEquals(0f, YtResponsive.resolve(node, YtEnv.PHONE.copy(fontScale = 1.2f)).text!!.size)
        assertEquals(18f, YtResponsive.resolve(node, YtEnv.PHONE.copy(fontScale = 1.3f)).text!!.size)
    }

    @Test
    fun `a state patch comes last`() {
        assertEquals(999f, YtResponsive.resolve(node, YtEnv(1000f, 600f), setOf(YtStates.SELECTED)).width.value)
    }

    @Test
    fun `an edit in a breakpoint goes into its override`() {
        val cond = YtOverride(width = YtWidthClass.MEDIUM)
        val once = YtResponsive.withOverride(node, cond) { it.copy(gap = 4f) }
        val twice = YtResponsive.withOverride(once, cond) { it.copy(columns = 3) }
        assertEquals(4, twice.overrides.size)
        val o = twice.overrides.last()
        assertEquals(4f, o.patch.gap)
        assertEquals(3, o.patch.columns)
        assertEquals(3, YtResponsive.resolve(twice, YtEnv(700f, 900f)).layout.columns)
    }
}
