package com.github.libretube.services

import android.os.Bundle
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.github.libretube.ui.yt.YtTestHooks

/**
 * YT (package C): the live picture while scrubbing with the time magnifier. The page's time bar
 * seeks the controller along with the thumb; the player here runs in scrubbing mode meanwhile
 * (ExoPlayer.setScrubbingModeEnabled: many quick exact seeks, no sound, playback held). The page
 * repeats [SCRUB_ON] every second while it scrubs; when that stops (the page died mid-scrub), the
 * watchdog ends scrubbing mode, so playback never stays held.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object YtScrubModule : YtServiceModule {
    const val SCRUB_ON = "scrub_on"
    const val SCRUB_OFF = "scrub_off"

    private const val WATCHDOG_MS = 2_500L

    private var held: ExoPlayer? = null
    private var handler: Handler? = null
    private val watchdog = Runnable { off("watchdog") }

    override fun command(service: AbstractPlayerService, exo: ExoPlayer?, args: Bundle): Boolean {
        when (args.getString(YtServiceModules.KEY)) {
            SCRUB_ON -> {
                val player = exo ?: return true
                if (held !== player) off("player changed")
                held = player
                if (!player.isScrubbingModeEnabled) {
                    player.setScrubbingModeEnabled(true)
                    YtTestHooks.log("YtScrub") { "scrubbing mode on" }
                }
                handler?.removeCallbacks(watchdog)
                handler?.postDelayed(watchdog, WATCHDOG_MS)
            }

            SCRUB_OFF -> off("scrub end")
            else -> return false
        }
        return true
    }

    override fun onPlayer(exo: ExoPlayer, handler: Handler) {
        // a new service's player: whatever the old one held is not ours to keep
        if (held !== exo) off("new player")
        this.handler = handler
    }

    override fun onNavigate(videoId: String) = off("next video")

    override fun release(exo: ExoPlayer) {
        if (held !== exo) return
        off("release")
    }

    private fun off(why: String) {
        handler?.removeCallbacks(watchdog)
        val player = held ?: return
        held = null
        runCatching {
            if (player.isScrubbingModeEnabled) {
                player.setScrubbingModeEnabled(false)
                YtTestHooks.log("YtScrub") { "scrubbing mode off ($why)" }
            }
        }
    }
}
