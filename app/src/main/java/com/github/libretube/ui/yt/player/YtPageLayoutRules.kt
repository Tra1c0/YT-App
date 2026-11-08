package com.github.libretube.ui.yt.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * YT: whether the player page has to be built again for the screen's orientation, and whether a
 * page built in landscape goes into fullscreen on its own. Pure, so the JVM tests can check it.
 *
 * MainActivity handles rotations itself (configChanges), so a page keeps the layout file of the
 * orientation it was built in (layout/ or layout-land/fragment_player.xml) until PlayerFragment
 * recreates the activity. It did that only from onConfigurationChanged, so a page that changed
 * its orientation inside fullscreen (where it is not rebuilt) and then left fullscreen without a
 * further rotation (the swipe down, the button, back) stayed in the other orientation's layout:
 * in landscape the portrait page, its 16:9 video taller than the screen and the details out of
 * reach. Turned upright again it was the same, never rebuilt page, and the minimize drag on it
 * could not move its transition.
 */
object YtPageLayoutRules {
    /**
     * The orientation [requested] (an ActivityInfo.SCREEN_ORIENTATION_* value) locks the activity
     * to, as a Configuration.ORIENTATION_* value, or null when it follows the sensor or the user.
     */
    fun lockedTo(requested: Int): Int? = when (requested) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> Configuration.ORIENTATION_PORTRAIT

        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> Configuration.ORIENTATION_LANDSCAPE

        else -> null
    }

    /**
     * Whether the page (built for [layoutOrientation]) has to be built again now that the screen
     * shows [orientation]. Not in fullscreen (the video fills the screen in either layout), not
     * while a rebuild already runs or the video floats in YT's own window, and not while the
     * activity is locked to the other orientation: the system is about to turn the screen there,
     * and that turn is the next configuration change (upstream checked the orientation *setting*,
     * which auto fullscreen overrides with the sensor, so a page left fullscreen turned stayed in
     * the wrong layout for good).
     */
    fun rebuild(
        fullscreen: Boolean,
        orientation: Int,
        layoutOrientation: Int,
        requested: Int,
        rebuilding: Boolean,
        floating: Boolean,
    ): Boolean {
        if (fullscreen || rebuilding || floating) return false
        if (orientation != Configuration.ORIENTATION_PORTRAIT && orientation != Configuration.ORIENTATION_LANDSCAPE) return false
        if (orientation == layoutOrientation) return false
        val lock = lockedTo(requested) ?: return true
        return lock == orientation
    }

    /**
     * Whether a page built in landscape goes into fullscreen at once (auto fullscreen). Not for a
     * docked page, and not for the page rebuilt because the user just left fullscreen in
     * landscape ([leftFullscreen]): that is the windowed page they asked for.
     */
    fun fullscreenAtStart(autoFullscreen: Boolean, landscape: Boolean, docked: Boolean, leftFullscreen: Boolean): Boolean =
        autoFullscreen && landscape && !docked && !leftFullscreen
}
