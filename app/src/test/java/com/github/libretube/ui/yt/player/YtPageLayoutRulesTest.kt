package com.github.libretube.ui.yt.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtPageLayoutRulesTest {
    private val portrait = Configuration.ORIENTATION_PORTRAIT
    private val landscape = Configuration.ORIENTATION_LANDSCAPE

    private fun rebuild(
        orientation: Int,
        layout: Int,
        requested: Int = ActivityInfo.SCREEN_ORIENTATION_SENSOR,
        fullscreen: Boolean = false,
        rebuilding: Boolean = false,
        floating: Boolean = false,
    ) = YtPageLayoutRules.rebuild(fullscreen, orientation, layout, requested, rebuilding, floating)

    @Test
    fun fullscreenLeftTurnedRebuildsThePage() {
        // the reported case: auto fullscreen (sensor), the portrait page left fullscreen in landscape
        assertTrue(rebuild(landscape, portrait))
        // the "auto" orientation setting
        assertTrue(rebuild(landscape, portrait, ActivityInfo.SCREEN_ORIENTATION_USER))
        // and turned upright again, the landscape page is rebuilt for portrait
        assertTrue(rebuild(portrait, landscape))
    }

    @Test
    fun theSettingDoesNotLockWhatTheSensorTurns() {
        // upstream skipped the rebuild for the "portrait" setting even while auto fullscreen
        // had the activity on the sensor: the page stayed in the other layout
        assertTrue(rebuild(landscape, portrait, ActivityInfo.SCREEN_ORIENTATION_SENSOR))
    }

    @Test
    fun aLockTowardsTheOtherOrientationWaitsForTheTurn() {
        // fullscreen left with the "portrait" setting: the screen is about to turn upright
        assertFalse(rebuild(landscape, portrait, ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT))
        // locked to where the screen already is: the page has to match it
        assertTrue(rebuild(landscape, portrait, ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE))
    }

    @Test
    fun noRebuildWhenNothingIsWrongOrItCannotRun() {
        assertFalse(rebuild(portrait, portrait))
        assertFalse(rebuild(landscape, landscape))
        assertFalse(rebuild(landscape, portrait, fullscreen = true))
        assertFalse(rebuild(landscape, portrait, rebuilding = true))
        assertFalse(rebuild(landscape, portrait, floating = true))
        assertFalse(rebuild(Configuration.ORIENTATION_UNDEFINED, portrait))
    }

    @Test
    fun locks() {
        assertEquals(portrait, YtPageLayoutRules.lockedTo(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT))
        assertEquals(landscape, YtPageLayoutRules.lockedTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))
        assertNull(YtPageLayoutRules.lockedTo(ActivityInfo.SCREEN_ORIENTATION_USER))
        assertNull(YtPageLayoutRules.lockedTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))
    }

    @Test
    fun theWindowedPageTheUserAskedForStaysWindowed() {
        assertTrue(YtPageLayoutRules.fullscreenAtStart(autoFullscreen = true, landscape = true, docked = false, windowed = false))
        assertFalse(YtPageLayoutRules.fullscreenAtStart(autoFullscreen = true, landscape = true, docked = false, windowed = true))
        assertFalse(YtPageLayoutRules.fullscreenAtStart(autoFullscreen = true, landscape = true, docked = true, windowed = false))
        assertFalse(YtPageLayoutRules.fullscreenAtStart(autoFullscreen = false, landscape = true, docked = false, windowed = false))
        assertFalse(YtPageLayoutRules.fullscreenAtStart(autoFullscreen = true, landscape = false, docked = false, windowed = false))
    }

    private fun onChange(
        orientation: Int,
        heard: Int?,
        windowed: Boolean = false,
        autoFullscreen: Boolean = true,
        docked: Boolean = false,
    ) = YtPageLayoutRules.autoFullscreenOnChange(autoFullscreen, docked, orientation, heard, windowed)

    @Test
    fun autoFullscreenFollowsTurnsOnly() {
        assertEquals(YtPageLayoutRules.AutoFullscreen.ENTER, onChange(landscape, heard = portrait))
        assertEquals(YtPageLayoutRules.AutoFullscreen.LEAVE, onChange(portrait, heard = landscape))
        // the reported case: another configuration change in landscape (size, keyboard, theme)
        // sent the windowed page back into fullscreen
        assertEquals(YtPageLayoutRules.AutoFullscreen.NONE, onChange(landscape, heard = landscape))
        assertEquals(YtPageLayoutRules.AutoFullscreen.NONE, onChange(portrait, heard = portrait))
    }

    @Test
    fun autoFullscreenLeavesTheChosenAndTheDockedPageAlone() {
        assertEquals(YtPageLayoutRules.AutoFullscreen.NONE, onChange(landscape, heard = portrait, windowed = true))
        assertEquals(YtPageLayoutRules.AutoFullscreen.NONE, onChange(landscape, heard = portrait, docked = true))
        assertEquals(YtPageLayoutRules.AutoFullscreen.NONE, onChange(portrait, heard = landscape, docked = true))
        assertEquals(YtPageLayoutRules.AutoFullscreen.NONE, onChange(landscape, heard = portrait, autoFullscreen = false))
        assertEquals(YtPageLayoutRules.AutoFullscreen.NONE, onChange(portrait, heard = landscape, autoFullscreen = false))
    }

    @Test
    fun theWindowedChoiceLastsUntilTheScreenTurns() {
        // left in landscape: kept; left upright (the screen turns there next): nothing to keep
        assertTrue(YtPageLayoutRules.windowedAfter(false, landscape, leftFullscreen = true))
        assertFalse(YtPageLayoutRules.windowedAfter(false, portrait, leftFullscreen = true))
        // kept while the screen stays in landscape, over any rebuild
        assertTrue(YtPageLayoutRules.windowedAfter(true, landscape))
        // ended by a turn away from landscape
        assertFalse(YtPageLayoutRules.windowedAfter(true, portrait))
        assertFalse(YtPageLayoutRules.windowedAfter(true, Configuration.ORIENTATION_UNDEFINED))
        assertFalse(YtPageLayoutRules.windowedAfter(false, landscape))
    }

    /** The page as it hears configurations, with the rules in the order PlayerFragment applies them. */
    private class Page(var layout: Int, var heard: Int, var windowed: Boolean = false) {
        var fullscreen = false
        var rebuilds = 0

        fun start(orientation: Int, docked: Boolean = false) {
            layout = orientation
            heard = orientation
            windowed = YtPageLayoutRules.windowedAfter(windowed, orientation)
            if (YtPageLayoutRules.fullscreenAtStart(true, orientation == Configuration.ORIENTATION_LANDSCAPE, docked, windowed)) fullscreen = true
        }

        fun hear(orientation: Int) {
            val before = heard
            heard = orientation
            windowed = YtPageLayoutRules.windowedAfter(windowed, orientation)
            when (YtPageLayoutRules.autoFullscreenOnChange(true, false, orientation, before, windowed)) {
                YtPageLayoutRules.AutoFullscreen.ENTER -> fullscreen = true
                YtPageLayoutRules.AutoFullscreen.LEAVE -> fullscreen = false
                YtPageLayoutRules.AutoFullscreen.NONE -> Unit
            }
            rebuildIfNeeded()
        }

        /** Any user exit (button, swipe down, back, predictive back, hold) goes through here. */
        fun leaveFullscreen() {
            fullscreen = false
            windowed = YtPageLayoutRules.windowedAfter(windowed, heard, leftFullscreen = true)
            rebuildIfNeeded()
        }

        private fun rebuildIfNeeded() {
            if (YtPageLayoutRules.rebuild(fullscreen, heard, layout, ActivityInfo.SCREEN_ORIENTATION_SENSOR, false, false)) {
                rebuilds++
                start(heard)
            }
        }
    }

    @Test
    fun leavingFullscreenInLandscapeLandsInTheWindowedLandscapePageAtOnce() {
        val page = Page(layout = portrait, heard = portrait)
        page.hear(landscape)
        assertTrue(page.fullscreen)
        assertEquals(portrait, page.layout)

        // first attempt: rebuilt for landscape and windowed
        page.leaveFullscreen()
        assertFalse(page.fullscreen)
        assertEquals(landscape, page.layout)
        assertEquals(1, page.rebuilds)

        // configuration changes that are no turn (the reported scroll) keep it windowed
        page.hear(landscape)
        page.hear(landscape)
        assertFalse(page.fullscreen)
        assertEquals(1, page.rebuilds)

        // turned upright and back: auto fullscreen again
        page.hear(portrait)
        assertFalse(page.fullscreen)
        assertEquals(portrait, page.layout)
        page.hear(landscape)
        assertTrue(page.fullscreen)

        // asked for fullscreen, left it again: windowed again
        page.leaveFullscreen()
        assertFalse(page.fullscreen)
        assertTrue(page.windowed)
        assertEquals(landscape, page.layout)
    }
}
