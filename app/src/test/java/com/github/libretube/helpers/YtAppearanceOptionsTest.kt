package com.github.libretube.helpers

import com.github.libretube.constants.PreferenceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtAppearanceOptionsTest {
    @Test
    fun standardShowsEverything() {
        for (key in YtAppearanceOptions.STANDARD_ONLY + "yt_design_motion" + "yt_design") {
            assertTrue(key, YtAppearanceOptions.applies(key, "standard"))
        }
    }

    @Test
    fun designsHideModeAccentAndPure() {
        for (design in listOf("youtube", "clay", "glass", "kiesel")) {
            assertFalse(YtAppearanceOptions.applies(PreferenceKeys.THEME_MODE, design))
            assertFalse(YtAppearanceOptions.applies(PreferenceKeys.PURE_THEME, design))
            assertFalse(YtAppearanceOptions.applies(PreferenceKeys.ACCENT_COLOR, design))
            assertTrue(YtAppearanceOptions.applies("yt_design", design))
            assertTrue(YtAppearanceOptions.applies("yt_design_motion", design))
            assertTrue(YtAppearanceOptions.applies("yt_dynamic_player_colors", design))
        }
    }

    @Test
    fun materialYouOnlyWithDynamicColors() {
        val all = listOf("my", "pink", "red")
        assertEquals(all, YtAppearanceOptions.accentValues(all, dynamicColors = true))
        assertEquals(listOf("pink", "red"), YtAppearanceOptions.accentValues(all, dynamicColors = false))
    }

    @Test
    fun sameValueIsNoChange() {
        assertTrue(YtAppearanceOptions.isNoChange("clay", "clay"))
        assertFalse(YtAppearanceOptions.isNoChange("clay", "pop"))
        assertFalse(YtAppearanceOptions.isNoChange(null, "pop"))
        assertTrue(YtAppearanceOptions.isNoChange(true, true))
    }

    @Test
    fun refreshMarksOlderActivitiesStale() {
        val seen = YtThemeRefresh.generation
        assertFalse(YtThemeRefresh.isStale(seen))
        YtThemeRefresh.bump()
        assertTrue(YtThemeRefresh.isStale(seen))
        assertFalse(YtThemeRefresh.isStale(YtThemeRefresh.generation))
    }
}
