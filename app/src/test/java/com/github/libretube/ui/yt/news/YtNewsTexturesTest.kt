package com.github.libretube.ui.yt.news

import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNewsTexturesTest {
    private val designs = YtDesign.entries.filter { it != YtDesign.STANDARD }

    @Test
    fun everyDesignHasItsOwnFeelForEveryMoment() {
        for (design in designs) {
            for (moment in YtNewsHaptic.entries) {
                val texture = YtNewsTextures.of(design, moment)
                assertTrue("$design $moment", texture.steps.isNotEmpty())
                assertEquals("news_${moment.name.lowercase()}_${design.key}", texture.name)
                assertEquals(moment.fallback, texture.fallback)
                assertTrue("$design $moment", texture.steps.all { it.scale in 0f..1f && it.delayMs in 0..200 })
            }
            // the moments of one design feel different from each other
            val feels = YtNewsHaptic.entries.map { YtNewsTextures.of(design, it).steps }
            assertEquals("$design", feels.size, feels.toSet().size)
        }
    }

    @Test
    fun theDesignsDoNotShareOneFeel() {
        for (moment in listOf(YtNewsHaptic.OPEN, YtNewsHaptic.QUEUE)) {
            val feels = designs.map { YtNewsTextures.of(it, moment).steps }
            assertTrue("$moment", feels.toSet().size >= designs.size - 3)
        }
    }

    @Test
    fun everyMomentFallsBackToTheSystem() {
        for (moment in YtNewsHaptic.entries) {
            assertNotEquals(YtFeedback.NO_HAPTICS, moment.fallback)
            // before Android 11 the constant plays, mapped to one Android 8 knows
            val old = YtFeedback.compat(moment.fallback, 26)
            assertTrue("$moment: $old", old in listOf(YtFeedback.CLOCK_TICK, YtFeedback.CONTEXT_CLICK, YtFeedback.LONG_PRESS))
            for (design in YtDesign.entries) assertFalse(YtNewsTextures.of(design, moment).composableOn(29))
        }
    }

    @Test
    fun theClassicDesignOnlyUsesTheSystemFeedback() {
        for (moment in YtNewsHaptic.entries) {
            val texture = YtNewsTextures.of(YtDesign.STANDARD, moment)
            assertTrue(texture.steps.isEmpty())
            assertFalse(texture.composableOn(36))
        }
    }
}
