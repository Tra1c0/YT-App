package com.github.libretube.ui.yt.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.os.bundleOf
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.services.YtScrubModule
import com.github.libretube.services.YtServiceModules
import com.github.libretube.ui.yt.YtPlaybackClock
import com.github.libretube.ui.yt.YtSeekReason
import com.github.libretube.ui.yt.YtTestHooks

/**
 * YT: the live picture of the time magnifier. While it is fine, the video itself seeks along
 * with the thumb (at most every [THROTTLE_MS]), with the service's player in scrubbing mode
 * (YtScrubModule: fast seeks, no sound). Online only inside what is loaded, so a scrub does not
 * fetch new pieces of the video for every frame it passes; offline anywhere. One scrub at a time,
 * main thread.
 */
class YtScrubLive {
    private val handler = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var on = false
    private var seeked = false
    private var pending = -1L
    private var sent = -1L
    private var lastSeekAt = 0L
    private var windowStart = 0L
    private var windowEnd = Long.MAX_VALUE

    private val flush = Runnable { seekNow() }

    // the service turns scrubbing mode off by itself when this stops coming (a page that died)
    private val keepAlive = object : Runnable {
        override fun run() {
            if (!on) return
            command(YtScrubModule.SCRUB_ON)
            handler.postDelayed(this, KEEP_ALIVE_MS)
        }
    }

    /** A scrub begins; the video is at [originMs]. [anywhere]: offline, everything is at hand. */
    fun begin(player: Player?, originMs: Long, anywhere: Boolean) {
        end(null, originMs, seekBack = false)
        this.player = player
        seeked = false
        pending = -1L
        sent = -1L
        if (anywhere || player == null) {
            windowStart = 0L
            windowEnd = Long.MAX_VALUE
        } else {
            // what the player keeps behind the position (PlayerHelper's back buffer) and has loaded ahead
            windowStart = (originMs - BACK_BUFFER_MS).coerceAtLeast(0L)
            windowEnd = maxOf(player.bufferedPosition, originMs)
        }
    }

    /** Whether the picture can show [positionMs] without loading. */
    fun covers(positionMs: Long): Boolean = player != null && positionMs in windowStart..windowEnd

    /** The thumb is at [targetMs] on [level]; the picture follows if the level is fine enough. */
    fun update(level: YtScrubLevel, targetMs: Long) {
        if (player == null || !level.livePicture || !YtPlayerGesturePrefs.livePicture) return
        if (!covers(targetMs)) return
        if (!on) {
            on = true
            command(YtScrubModule.SCRUB_ON)
            handler.postDelayed(keepAlive, KEEP_ALIVE_MS)
        }
        pending = targetMs
        handler.removeCallbacks(flush)
        val wait = THROTTLE_MS - (SystemClock.uptimeMillis() - lastSeekAt)
        if (wait <= 0L) seekNow() else handler.postDelayed(flush, wait)
    }

    /**
     * The scrub ended with a seek to [targetMs] (made by the bar), or with none: then the picture
     * goes back to [originMs] if it had moved ([seekBack]).
     */
    fun end(targetMs: Long?, originMs: Long, seekBack: Boolean = true) {
        handler.removeCallbacks(flush)
        handler.removeCallbacks(keepAlive)
        val player = player
        if (seekBack && seeked && targetMs == null && player != null) {
            YtPlaybackClock.tagNextSeek(YtSeekReason.USER_SCRUB)
            player.seekTo(originMs)
            YtTestHooks.log("YtScrub") { "live back to=$originMs" }
        }
        if (on) {
            on = false
            command(YtScrubModule.SCRUB_OFF)
        }
        this.player = null
        pending = -1L
        seeked = false
    }

    private fun seekNow() {
        val player = player ?: return
        val target = pending
        pending = -1L
        if (target < 0L || target == sent) return
        YtPlaybackClock.tagNextSeek(YtSeekReason.USER_SCRUB)
        player.seekTo(target)
        sent = target
        seeked = true
        lastSeekAt = SystemClock.uptimeMillis()
        YtTestHooks.log("YtScrub") { "live seek to=$target" }
    }

    private fun command(name: String) {
        (player as? MediaController)?.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            bundleOf(YtServiceModules.KEY to name),
        )
    }

    private companion object {
        const val THROTTLE_MS = 60L
        const val KEEP_ALIVE_MS = 1_000L

        // PlayerHelper keeps 3 minutes behind the position; a little less, to be safe
        const val BACK_BUFFER_MS = 170_000L
    }
}
