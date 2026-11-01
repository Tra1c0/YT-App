package com.github.libretube.services

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.github.libretube.constants.IntentData
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.feed.YtSession

/**
 * YT (package D): the time box ("Heute habe ich … Minuten") in the player service.
 * - Counts the time while a video plays (YtSession.accrue), so the mini player can show what is
 *   left.
 * - A planned end point (a chapter end) is a PlayerMessage at that position: the last video
 *   pauses there and the time is up; a video with more planned after it ends there and autoplay
 *   takes the next one from the queue.
 * - Once the last planned video is over or the time is used up, autoplay is held, so related
 *   videos do not run past the budget. A next video asked for while one plays still goes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object YtSessionModule : YtServiceModule {
    private var exo: ExoPlayer? = null

    /** The video the service plays now, from its playlist metadata (or the navigation). */
    private var currentId: String? = null

    private var endMessage: PlayerMessage? = null

    /** The video whose end point was set up (or found already passed); cleared on navigation. */
    private var armedFor: String? = null

    private fun now() = SystemClock.elapsedRealtime()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            YtSession.update { it.accrue(now(), isPlaying) }
        }

        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
            // the service posts the video id when a video starts loading (also the first one);
            // the SponsorBlock updates carry no id
            val id = mediaMetadata.extras?.getString(IntentData.videoId) ?: return
            if (id == currentId) return
            disarm()
            currentId = id
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> arm()
                Player.STATE_ENDED -> onEnded()
            }
        }
    }

    override fun onPlayer(exo: ExoPlayer, handler: Handler) {
        if (this.exo === exo) return
        disarm()
        this.exo?.removeListener(listener)
        exo.addListener(listener)
        this.exo = exo
        currentId = null
        YtSession.update { it.accrue(now(), false) }
    }

    override fun onNavigate(videoId: String) {
        disarm()
        currentId = videoId
        // a video picked after the time was up ends the time box; "Noch ein Video" clears done first
        if (YtSession.state.value?.done == true) {
            YtSession.end()
            YtTestHooks.log("YtSession") { "ended by navigation id=$videoId" }
        }
    }

    override fun holdAutoplay(): Boolean {
        val state = YtSession.state.value ?: return false
        val hold = state.holds(currentId, exo?.playbackState == Player.STATE_ENDED, now())
        if (hold) YtTestHooks.log("YtSession") { "autoplay held id=$currentId" }
        return hold
    }

    override fun release(exo: ExoPlayer) {
        if (exo !== this.exo) return
        disarm()
        exo.removeListener(listener)
        this.exo = null
        currentId = null
        // the player is gone, and with it the time box
        if (YtSession.active) {
            YtSession.end()
            YtTestHooks.log("YtSession") { "ended with the player" }
        }
    }

    /** The time box changed (an end point added or dropped): set the current video's end point up again. */
    fun rearm() {
        disarm()
        arm()
    }

    /** "Weiterschauen" at an end point: the paused video plays on. */
    fun resume() {
        exo?.play()
    }

    private fun arm() {
        val exo = exo ?: return
        val id = currentId ?: return
        if (armedFor == id) return
        val state = YtSession.state.value ?: return
        val endMs = state.endAtMs(id) ?: return
        if (exo.currentTimeline.isEmpty) return
        // with the autoplay countdown on, the service would not move on by itself: only the last
        // planned video stops at its end point then, the others play to their end
        if (id != state.lastId && PlayerHelper.autoPlayCountdown) return
        armedFor = id
        if (exo.currentPosition >= endMs) return
        endMessage = runCatching {
            exo.createMessage { _, _ -> onEndPoint(id) }
                .setLooper(Looper.getMainLooper())
                .setPosition(exo.currentMediaItemIndex, endMs)
                .setDeleteAfterDelivery(true)
                .send()
        }.getOrNull()
        YtTestHooks.log("YtSession") { "end point id=$id at=${endMs / 1000}s armed=${endMessage != null}" }
    }

    private fun disarm() {
        endMessage?.cancel()
        endMessage = null
        armedFor = null
    }

    private fun onEndPoint(id: String) {
        endMessage = null
        val exo = exo ?: return
        if (id != currentId) return
        val state = YtSession.state.value ?: return
        val now = now()
        if (id == state.lastId || state.remainingMs(now) <= 0) {
            exo.pause()
            YtSession.update { it.accrue(now, false).copy(done = true, stoppedAt = id) }
            YtTestHooks.log("YtSession") { "time up at end point id=$id" }
            return
        }
        // more is planned: this video ends here (its position is saved on pause), and the
        // service's autoplay takes the next one from the queue
        YtTestHooks.log("YtSession") { "end point passed id=$id, next" }
        exo.pause()
        exo.clearMediaItems()
    }

    private fun onEnded() {
        val exo = exo ?: return
        // emptied for the next video (navigation or an end point), not played to its end
        if (exo.currentTimeline.isEmpty) return
        val state = YtSession.state.value ?: return
        if (state.done || !state.holds(currentId, ended = true, now = now())) return
        YtSession.update { it.accrue(now(), false).copy(done = true) }
        YtTestHooks.log("YtSession") { "time up id=$currentId" }
    }
}
