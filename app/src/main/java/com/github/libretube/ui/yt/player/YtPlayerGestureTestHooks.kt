package com.github.libretube.ui.yt.player

import android.os.Bundle
import android.util.Log
import android.view.View
import com.github.libretube.R
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.yt.YtTestHandler
import com.github.libretube.ui.yt.YtTestHooks
import kotlinx.coroutines.delay

/**
 * YT (package C): the emulator start parameters of the player gesture checks
 * (scripts/gestures/c.sh), read by YtTestHooks once the test video plays.
 *
 * - `--ez yt_controls_test true`: the controls stay up (until the next touch), so the check can
 *   find the time bar and scrub it before they hide. The log line gives the bar's place on the
 *   screen ("bar=left top right bottom"): a dump of the screen waits for it to be still, which
 *   the clock of a playing video never is.
 * - `--el yt_back_test <ms>`: a while after the start, the video jumps back by that much, as a
 *   double tap does (a plain seek, without a reason), for the rewind memory's chip.
 */
object YtPlayerGestureTestHooks : YtTestHandler {
    private const val TAG = "YtTest"
    private const val EXTRA_CONTROLS = "yt_controls_test"
    private const val EXTRA_BACK = "yt_back_test"

    // the shared seek settles first, as a double tap comes some time after a seek
    private const val BACK_DELAY_MS = 1_500L

    // the controls are laid out a frame or two after they go up
    private const val BAR_DELAY_MS = 300L

    override val keys = listOf(EXTRA_CONTROLS, EXTRA_BACK)

    override suspend fun test(host: AbstractPlayerHostActivity, extras: Bundle) {
        val back = extras.getLong(EXTRA_BACK, 0L)
        if (back > 0L) {
            val player = YtTestHooks.awaitPlayer(host)
            if (player == null) {
                Log.w(TAG, "back test: no player")
            } else {
                delay(BACK_DELAY_MS)
                val from = player.currentPosition
                val to = (from - back).coerceAtLeast(0L)
                player.seekTo(to)
                Log.i(TAG, "back test from=$from to=$to")
            }
        }
        if (extras.getBoolean(EXTRA_CONTROLS, false)) {
            val shown = host.runOnPlayerFragment {
                showControlsPermanently(true)
                true
            }
            delay(BAR_DELAY_MS)
            val bar = host.findViewById<View>(R.id.exo_progress)
                ?.takeIf { it.isShown && it.width > 0 && it.height > 0 }
                ?.let { view ->
                    val at = IntArray(2)
                    view.getLocationOnScreen(at)
                    " bar=${at[0]} ${at[1]} ${at[0] + view.width} ${at[1] + view.height}"
                }
                .orEmpty()
            Log.i(TAG, "controls test shown=$shown$bar")
        }
    }
}
