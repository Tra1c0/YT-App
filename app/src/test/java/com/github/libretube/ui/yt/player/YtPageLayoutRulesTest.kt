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
        assertTrue(YtPageLayoutRules.fullscreenAtStart(autoFullscreen = true, landscape = true, docked = false, leftFullscreen = false))
        assertFalse(YtPageLayoutRules.fullscreenAtStart(autoFullscreen = true, landscape = true, docked = false, leftFullscreen = true))
        assertFalse(YtPageLayoutRules.fullscreenAtStart(autoFullscreen = true, landscape = true, docked = true, leftFullscreen = false))
        assertFalse(YtPageLayoutRules.fullscreenAtStart(autoFullscreen = false, landscape = true, docked = false, leftFullscreen = false))
        assertFalse(YtPageLayoutRules.fullscreenAtStart(autoFullscreen = true, landscape = false, docked = false, leftFullscreen = false))
    }
}
