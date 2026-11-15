package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.services.YtServiceModule
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.util.PlayingQueue

/**
 * YT: "Alle als Clip-Folge abspielen" in the player service, with the time box's mechanism
 * (YtSessionModule): each clip's end is a PlayerMessage at that position. At a clip's end the
 * next clip of the same video is a seek; the next video is the next item of the queue (the clip
 * videos in their order), reached by emptying the player so the service's autoplay takes it;
 * once a video is ready the module seeks to its clip's start. The last clip pauses. Navigating to
 * a video outside the clips ends the sequence. Main thread only.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object YtSsiClipModule : YtServiceModule {
    private var exo: ExoPlayer? = null
    private var currentId: String? = null
    private var clips: List<YtSsiClip> = emptyList()
    private var index = -1
    private var endMessage: PlayerMessage? = null
    private var armedFor = -1

    val active: Boolean get() = index in clips.indices

    /** Starts [list] (from the SSI screen): the queue becomes the clip videos, the first one plays from its clip. */
    fun start(context: Context, list: List<YtSsiClip>) {
        if (list.isEmpty()) return
        disarm()
        clips = list
        index = 0
        val videos = list.map { it.videoId }.distinct().map { id ->
            val clip = list.first { it.videoId == id }
            StreamItem(
                url = "/watch?v=$id",
                type = StreamItem.TYPE_STREAM,
                title = clip.title,
                uploaderName = clip.channelName,
                thumbnail = "https://i.ytimg.com/vi/$id/mqdefault.jpg",
            )
        }
        PlayingQueue.setStreams(videos)
        YtTestHooks.log("YtSsi") { "clips start n=${list.size} videos=${videos.size}" }
        NavigationHelper.navigateVideo(context, PlayerData(list.first().videoId, keepQueue = true, timestamp = list.first().startMs / 1000))
    }

    fun stop() {
        disarm()
        clips = emptyList()
        index = -1
    }

    private val listener = object : Player.Listener {
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
            val id = mediaMetadata.extras?.getString(IntentData.videoId) ?: return
            if (id == currentId) return
            currentId = id
            follow(id)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) arm()
        }
    }

    override fun onPlayer(exo: ExoPlayer, handler: Handler) {
        if (this.exo === exo) return
        disarm()
        this.exo?.removeListener(listener)
        exo.addListener(listener)
        this.exo = exo
        currentId = null
    }

    override fun onNavigate(videoId: String) {
        currentId = videoId
        follow(videoId)
    }

    override fun release(exo: ExoPlayer) {
        if (exo !== this.exo) return
        disarm()
        exo.removeListener(listener)
        this.exo = null
        stop()
    }

    /** A video starts: its first clip from the current one on, or the end of the sequence. */
    private fun follow(videoId: String) {
        if (!active) return
        if (clips[index].videoId == videoId) return
        disarm()
        val next = clips.indices.firstOrNull { it > index && clips[it].videoId == videoId }
            ?: clips.indices.firstOrNull { clips[it].videoId == videoId }
        if (next == null) {
            YtTestHooks.log("YtSsi") { "clips left for id=$videoId" }
            stop()
        } else {
            index = next
        }
    }

    private fun arm() {
        val exo = exo ?: return
        if (!active || armedFor == index) return
        val clip = clips[index]
        if (clip.videoId != currentId || exo.currentTimeline.isEmpty) return
        armedFor = index
        val position = exo.currentPosition
        // the watch position or a whole-second start may be off: the clip's own start
        if (position < clip.startMs - SLACK_MS || position > clip.endMs) exo.seekTo(clip.startMs)
        endMessage = runCatching {
            exo.createMessage { _, _ -> onClipEnd() }
                .setLooper(Looper.getMainLooper())
                .setPosition(exo.currentMediaItemIndex, clip.endMs)
                .setDeleteAfterDelivery(true)
                .send()
        }.getOrNull()
        YtTestHooks.log("YtSsi") { "clip ${index + 1}/${clips.size} id=${clip.videoId} ${clip.startMs}-${clip.endMs}" }
    }

    private fun disarm() {
        endMessage?.cancel()
        endMessage = null
        armedFor = -1
    }

    private fun onClipEnd() {
        endMessage = null
        val exo = exo ?: return
        if (!active) return
        val next = index + 1
        if (next >= clips.size) {
            exo.pause()
            YtTestHooks.log("YtSsi") { "clips done" }
            stop()
            return
        }
        armedFor = -1
        index = next
        if (clips[next].videoId == currentId) {
            exo.seekTo(clips[next].startMs)
            arm()
        } else {
            // as the time box: the video ends here and autoplay takes the next one of the queue
            exo.pause()
            exo.clearMediaItems()
        }
    }

    private const val SLACK_MS = 2_000L
}
