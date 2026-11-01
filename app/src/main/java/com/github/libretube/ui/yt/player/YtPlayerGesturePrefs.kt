package com.github.libretube.ui.yt.player

import com.github.libretube.helpers.PreferenceHelper

/** YT: the switches of the player gestures (R.xml.yt_r12_c_prefs), read when a gesture begins. */
object YtPlayerGesturePrefs {
    const val MAGNIFIER = "yt_scrub_magnifier"
    const val LIVE_PICTURE = "yt_scrub_live_picture"
    const val RETURN_CHIP = "yt_return_chip"
    const val LOOP_HOLD = "yt_loop_hold"
    const val HOLD = "yt_hold"
    const val HOLD_UP = "yt_hold_up"
    const val HOLD_DOWN = "yt_hold_down"
    const val HOLD_LEFT = "yt_hold_left"
    const val HOLD_RIGHT = "yt_hold_right"

    /** The time magnifier: finer the higher the finger above the time bar. */
    val magnifier: Boolean get() = PreferenceHelper.getBoolean(MAGNIFIER, true)

    /** While the magnifier is fine, the video itself shows the position. */
    val livePicture: Boolean get() = PreferenceHelper.getBoolean(LIVE_PICTURE, true)

    /** After a jump back, the chip that returns to where it started. */
    val returnChip: Boolean get() = PreferenceHelper.getBoolean(RETURN_CHIP, true)

    /** A long press on the time bar marks an explanation loop. */
    val loopHold: Boolean get() = PreferenceHelper.getBoolean(LOOP_HOLD, true)

    /** Hold and drag on the video runs the action set for the direction. */
    val hold: Boolean get() = PreferenceHelper.getBoolean(HOLD, true)

    /** What hold and drag towards [dir] does. */
    fun holdAction(dir: YtHoldDir): YtHoldAction {
        val key = when (dir) {
            YtHoldDir.UP -> HOLD_UP
            YtHoldDir.DOWN -> HOLD_DOWN
            YtHoldDir.LEFT -> HOLD_LEFT
            YtHoldDir.RIGHT -> HOLD_RIGHT
        }
        return YtHoldAction.of(PreferenceHelper.getString(key, YtHoldAction.default(dir).value), dir)
    }
}
