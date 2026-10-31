package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMiniTexturesTest {
    @Test
    fun everyMomentHasAFallback() {
        for (moment in YtMoment.entries) {
            val texture = YtMiniTextures.neutral(moment)
            assertNotEquals(moment.name, YtFeedback.NO_HAPTICS, texture.fallback)
            assertTrue(moment.name, texture.steps.isNotEmpty())
            // before Android 11 nothing composes: the constant plays, mapped to one Android 8 knows
            assertFalse(moment.name, texture.composableOn(29))
            val old = YtFeedback.compat(texture.fallback, 26)
            assertTrue("${moment.name}: $old", old in listOf(YtFeedback.CLOCK_TICK, YtFeedback.CONTEXT_CLICK, YtFeedback.LONG_PRESS))
        }
    }

    @Test
    fun theClassicDesignOnlyUsesTheSystemFeedback() {
        for (moment in YtMoment.entries) {
            val texture = YtMiniTextures.system(moment)
            assertTrue(texture.steps.isEmpty())
            assertFalse(texture.composableOn(36))
            assertEquals(YtMiniTextures.neutral(moment).fallback, texture.fallback)
        }
    }

    @Test
    fun androidElevenGetsTheConstantWhereAPrimitiveIsNewer() {
        // LOW_TICK and THUD came with Android 12
        assertFalse(YtMiniTextures.neutral(YtMoment.PEEK_START).composableOn(30))
        assertTrue(YtMiniTextures.neutral(YtMoment.PEEK_START).composableOn(31))
        assertTrue(YtMiniTextures.neutral(YtMoment.DOCK).composableOn(30))
    }

    @Test
    fun theLogNamesThePeek() {
        // scripts/gestures/a.sh looks for these in the YtHaptic log
        assertTrue(YtMiniTextures.neutral(YtMoment.PEEK_START).name.contains("peek"))
        assertTrue(YtMiniTextures.neutral(YtMoment.DOCK).name.contains("dock"))
        assertEquals(YtMoment.entries.size, YtMoment.entries.map { YtMiniTextures.neutral(it).name }.toSet().size)
    }
}
