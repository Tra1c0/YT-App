package com.github.libretube.services

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.github.libretube.ui.yt.YtPlaybackClock
import com.github.libretube.ui.yt.YtSeekReason
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.player.YtLoop
import com.github.libretube.ui.yt.player.YtLoopAction
import com.github.libretube.ui.yt.player.YtLoopPlan
import com.github.libretube.ui.yt.player.YtLoopRun
import com.github.libretube.ui.yt.player.YtLoopStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * YT (package C): the explanation loop (A to B, slower, with a pause to think), run in the
 * service so it is exact and goes on in the background and in PiP. A player message at B ends
 * each pass; its own tick catches jumps past B (a SponsorBlock skip), as the service's segment
 * tick only runs with SponsorBlock on. The loop's speed is not saved, and is undone at the end
 * only if the user did not pick another one meanwhile. The page sends the commands and shows
 * [status]; both live in the app's process, on the main thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object YtLoopModule : YtServiceModule {
    const val SET = "loop_set"
    const val CLEAR = "loop_clear"
    const val SKIP_PAUSE = "loop_skip_pause"

    const val ARG_A = "loop_a"
    const val ARG_B = "loop_b"
    const val ARG_SPEED = "loop_speed"
    const val ARG_PAUSE = "loop_pause"
    const val ARG_VIDEO = "loop_video"

    private const val LOG_TAG = "YtLoop"
    private const val TICK_MS = 250L

    // inside the loop but this close to B, a new loop starts at A instead (B would pass unseen)
    private const val NEAR_B_MS = 300L

    private val state = MutableStateFlow(YtLoopStatus())

    /** What the loop does now, for the page's band, chip and sheet. */
    val status: StateFlow<YtLoopStatus> = state.asStateFlow()

    private var handler: Handler? = null
    private var held: ExoPlayer? = null
    private var loopRun: YtLoopRun? = null
    private var videoId: String? = null
    private var message: PlayerMessage? = null

    // the speed before the loop, and the one the loop set (NaN: none)
    private var speedBefore = Float.NaN
    private var speedApplied = Float.NaN

    private val tick = Runnable { onTick() }
    private val pauseOver = Runnable { onPauseOver() }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // play pressed while the loop thinks: the next pass starts now, from A
            if (playWhenReady && loopRun?.pausing == true) act(loopRun?.resume() ?: YtLoopAction.NONE, "play pressed")
        }
    }

    override fun command(service: AbstractPlayerService, exo: ExoPlayer?, args: Bundle): Boolean {
        when (args.getString(YtServiceModules.KEY)) {
            SET -> {
                val player = exo ?: return true
                val loop = YtLoop(
                    aMs = args.getLong(ARG_A),
                    bMs = args.getLong(ARG_B),
                    speed = args.getFloat(ARG_SPEED, 1f),
                    pauseMs = args.getLong(ARG_PAUSE, 0L),
                )
                set(player, loop, args.getString(ARG_VIDEO))
            }

            CLEAR -> clear("cleared")
            SKIP_PAUSE -> act(loopRun?.resume() ?: YtLoopAction.NONE, "pause skipped")
            else -> return false
        }
        return true
    }

    override fun onPlayer(exo: ExoPlayer, handler: Handler) {
        // a new service's player: a loop on the old one is not ours to keep
        if (held != null && held !== exo) clear("new player")
        this.handler = handler
    }

    override fun onNavigate(videoId: String) {
        if (loopRun != null) clear("next video")
    }

    // a video never ends inside a loop (B is kept off the end); should it anyway, no autoplay
    override fun holdAutoplay(): Boolean = loopRun != null

    override fun release(exo: ExoPlayer) {
        if (held !== exo) return
        clear("release")
    }

    private fun set(exo: ExoPlayer, wanted: YtLoop, forVideo: String?) {
        if (exo.isCurrentMediaItemLive) {
            YtTestHooks.log(LOG_TAG) { "refused: live" }
            return
        }
        if (held != null && held !== exo) clear("other player")
        val loop = YtLoopPlan.clamp(wanted, exo.duration)
        if (loop == null) {
            YtTestHooks.log(LOG_TAG) { "refused: too short a=${wanted.aMs} b=${wanted.bMs}" }
            return
        }
        if (held !== exo) {
            held = exo
            exo.addListener(listener)
        }
        videoId = forVideo

        // the speed: only the first loop remembers the one before; a speed picked by the user
        // meanwhile is the one to come back to
        val current = exo.playbackParameters.speed
        if (speedBefore.isNaN() || (!speedApplied.isNaN() && abs(current - speedApplied) > SPEED_EPSILON)) {
            speedBefore = current
        }
        if (abs(current - loop.speed) > SPEED_EPSILON) exo.setPlaybackSpeed(loop.speed)
        speedApplied = loop.speed

        val existing = loopRun
        val run = existing ?: YtLoopRun(loop)
        loopRun = run
        // new settings without the pause it holds for: the next pass starts now
        val action = if (existing != null) existing.update(loop) else YtLoopAction.NONE
        armMessage(exo, loop.bMs)

        // a loop starts at A unless the video is in it already
        val position = exo.currentPosition
        val inside = position >= loop.aMs && position < loop.bMs - NEAR_B_MS
        if (action == YtLoopAction.NONE && !run.pausing && !inside) seekToA(exo, loop)
        publish()
        YtTestHooks.log(LOG_TAG) {
            "set a=${loop.aMs} b=${loop.bMs} speed=${loop.speed} pause=${loop.pauseMs} " +
                "video=$forVideo from=$position pass=${run.passes + 1}"
        }
        act(action, "settings")
        schedule()
    }

    private fun armMessage(exo: ExoPlayer, bMs: Long) {
        runCatching { message?.cancel() }
        // kept after delivery: every pass comes back past B
        message = runCatching {
            exo.createMessage { _, _ -> onB() }
                .setPosition(bMs)
                .setLooper(Looper.getMainLooper())
                .setDeleteAfterDelivery(false)
                .send()
        }.getOrNull()
    }

    private fun onB() {
        val run = loopRun ?: return
        act(run.atB(SystemClock.uptimeMillis()), "at B")
    }

    private fun onTick() {
        val exo = held ?: return
        val run = loopRun ?: return
        // the duration may come only after the loop: B moves off the end then
        val clamped = YtLoopPlan.clamp(run.loop, exo.duration)
        if (clamped == null) {
            clear("no room")
            return
        }
        if (clamped != run.loop) {
            run.update(clamped)
            armMessage(exo, clamped.bMs)
            publish()
        }
        act(run.tick(exo.currentPosition, SystemClock.uptimeMillis()), "tick")
        schedule()
    }

    private fun onPauseOver() {
        val run = loopRun ?: return
        act(run.tick(held?.currentPosition ?: 0L, SystemClock.uptimeMillis()), "pause over")
    }

    private fun act(action: YtLoopAction, why: String) {
        val exo = held ?: return
        val run = loopRun ?: return
        when (action) {
            YtLoopAction.NONE -> return

            YtLoopAction.SEEK_A -> {
                seekToA(exo, run.loop)
                YtTestHooks.log(LOG_TAG) { "pass=${run.passes + 1} ($why)" }
            }

            YtLoopAction.PAUSE -> {
                // it holds on the last picture of the passage, to think about it
                exo.pause()
                handler?.removeCallbacks(pauseOver)
                handler?.postDelayed(pauseOver, run.loop.pauseMs)
                YtTestHooks.log(LOG_TAG) { "pause ms=${run.loop.pauseMs} after pass=${run.passes} ($why)" }
            }

            YtLoopAction.RESUME -> {
                handler?.removeCallbacks(pauseOver)
                seekToA(exo, run.loop)
                exo.play()
                YtTestHooks.log(LOG_TAG) { "pass=${run.passes + 1} ($why)" }
            }
        }
        publish()
    }

    private fun seekToA(exo: ExoPlayer, loop: YtLoop) {
        // the rewind memory takes no return point from the loop's own jumps
        YtPlaybackClock.tagNextSeek(YtSeekReason.LOOP)
        exo.seekTo(loop.aMs)
    }

    private fun schedule() {
        val handler = handler ?: return
        handler.removeCallbacks(tick)
        if (loopRun != null) handler.postDelayed(tick, TICK_MS)
    }

    private fun publish() {
        val run = loopRun
        state.value = if (run == null) {
            YtLoopStatus()
        } else {
            YtLoopStatus(run.loop, videoId, run.passes, run.pauseEndsAt)
        }
    }

    private fun clear(why: String) {
        handler?.removeCallbacks(tick)
        handler?.removeCallbacks(pauseOver)
        runCatching { message?.cancel() }
        message = null
        val exo = held
        val hadLoop = loopRun != null
        loopRun = null
        held = null
        videoId = null
        if (exo != null) {
            runCatching {
                exo.removeListener(listener)
                // back to the speed from before, unless the user picked one meanwhile
                if (!speedApplied.isNaN() && !speedBefore.isNaN() &&
                    abs(exo.playbackParameters.speed - speedApplied) <= SPEED_EPSILON
                ) {
                    exo.setPlaybackSpeed(speedBefore)
                }
            }
        }
        speedBefore = Float.NaN
        speedApplied = Float.NaN
        publish()
        if (hadLoop) YtTestHooks.log(LOG_TAG) { "end ($why)" }
    }

    private const val SPEED_EPSILON = 0.001f
}
