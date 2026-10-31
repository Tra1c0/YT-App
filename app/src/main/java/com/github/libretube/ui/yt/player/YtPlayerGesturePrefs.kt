package com.github.libretube.ui.yt.player

import com.github.libretube.helpers.PreferenceHelper

/** YT: the switches of the player gestures (R.xml.yt_r12_c_prefs), read when a gesture begins. */
object YtPlayerGesturePrefs {
    const val MAGNIFIER = "yt_scrub_magnifier"
    const val LIVE_PICTURE = "yt_scrub_live_picture"
    const val RETURN_CHIP = "yt_return_chip"

    /** The time magnifier: finer the higher the finger above the time bar. */
    val magnifier: Boolean get() = PreferenceHelper.getBoolean(MAGNIFIER, true)

    /** While the magnifier is fine, the video itself shows the position. */
    val livePicture: Boolean get() = PreferenceHelper.getBoolean(LIVE_PICTURE, true)

    /** After a jump back, the chip that returns to where it started. */
    val returnChip: Boolean get() = PreferenceHelper.getBoolean(RETURN_CHIP, true)
}
