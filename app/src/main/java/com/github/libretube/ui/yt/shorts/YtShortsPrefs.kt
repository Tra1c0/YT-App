package com.github.libretube.ui.yt.shorts

import com.github.libretube.helpers.PreferenceHelper

/**
 * YT: the settings of the Shorts view (res/xml/yt_r12_shorts_prefs.xml), read where they are
 * used, so a change applies the next time the view opens or the next short starts.
 */
object YtShortsPrefs {
    /** Shorts open in the vertical Shorts view; off, they open in the video player as before. */
    const val KEY_VIEW = "yt_shorts_view"

    /** Autoscroll: "off" or the plays of a short before the view scrolls on ([YtShortsAutoscroll]). */
    const val KEY_AUTOSCROLL = "yt_shorts_autoscroll"

    /** The last number of plays that was on, for the quick toggle in the view. */
    private const val KEY_AUTOSCROLL_LAST = "yt_shorts_autoscroll_last"

    /** A thin progress line stays at the bottom while the timeline is hidden. */
    const val KEY_LINE = "yt_shorts_progress_line"

    val viewEnabled: Boolean get() = PreferenceHelper.getBoolean(KEY_VIEW, true)

    val line: Boolean get() = PreferenceHelper.getBoolean(KEY_LINE, true)

    var autoscroll: Int
        get() = YtShortsAutoscroll.parse(
            PreferenceHelper.getString(KEY_AUTOSCROLL, YtShortsAutoscroll.format(YtShortsAutoscroll.DEFAULT)),
        )
        set(value) {
            if (value > 0) PreferenceHelper.putInt(KEY_AUTOSCROLL_LAST, value)
            PreferenceHelper.putString(KEY_AUTOSCROLL, YtShortsAutoscroll.format(value))
        }

    /** The quick toggle: off, or back to the number of plays that was on last. */
    fun toggleAutoscroll(): Int {
        val current = autoscroll
        val next = if (current > 0) {
            // the settings screen sets the value without the setter: keep it for the way back
            PreferenceHelper.putInt(KEY_AUTOSCROLL_LAST, current)
            0
        } else {
            PreferenceHelper.getInt(KEY_AUTOSCROLL_LAST, YtShortsAutoscroll.DEFAULT)
                .takeIf { it in YtShortsAutoscroll.CHOICES && it > 0 } ?: YtShortsAutoscroll.DEFAULT
        }
        autoscroll = next
        return next
    }
}
