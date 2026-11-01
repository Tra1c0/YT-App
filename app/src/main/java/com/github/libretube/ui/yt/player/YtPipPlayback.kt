package com.github.libretube.ui.yt.player

import androidx.media3.common.Player

/** PiP follows the user's playback intent across temporary buffering. */
internal object YtPipPlayback {
    fun active(playWhenReady: Boolean, state: Int): Boolean = playWhenReady &&
        (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
}
