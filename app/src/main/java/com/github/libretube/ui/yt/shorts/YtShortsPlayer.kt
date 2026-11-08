package com.github.libretube.ui.yt.shorts

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.YtBattery
import com.github.libretube.helpers.YtBatteryCapRules
import com.github.libretube.helpers.YtBatteryCapWatch
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.ui.yt.YtTestHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * YT: the one player of the Shorts view. It is the view's own ExoPlayer, not the app's player
 * service: the service carries the queue, the notification and background playback of the
 * video player, and a feed of shorts that flips every few seconds would rewrite that queue and
 * notification on every swipe. So the Shorts view plays in the foreground only (it pauses when
 * the app goes to the background) and takes the audio focus, which pauses the video or audio
 * player while it is open. "Open in video player" hands a short over to the service.
 *
 * Preloading: the information of the neighbouring shorts (the slow part, one request each) is
 * fetched ahead and kept for the last few, so a swipe only waits for the first segment.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class YtShortsPlayer(private val context: Context, private val scope: CoroutineScope) {
    val player: ExoPlayer = PlayerHelper.createPlayer(context, DefaultTrackSelector(context)).apply {
        repeatMode = Player.REPEAT_MODE_ONE
        playWhenReady = true
    }

    // the information of the last shorts asked for, oldest first, and what has arrived of it
    // (both only touched on the main thread)
    private val cache = LinkedHashMap<String, Deferred<YtShortMedia?>>()
    private val done = HashMap<String, YtShortMedia>()
    private var job: Job? = null

    /** The short playing (or loading) now. */
    var currentId by mutableStateOf<String?>(null)
        private set

    /** Its information once it is there. */
    var media by mutableStateOf<YtShortMedia?>(null)
        private set

    /** How often it has played to its end since it came on screen. */
    var completed by mutableIntStateOf(0)
        private set

    /** It reached its end without looping (the play before an autoscroll). */
    var ended by mutableStateOf(false)
        private set

    /** The first picture is on screen: the still above the video can go. */
    var firstFrame by mutableStateOf(false)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    /** Nothing could be loaded for it. */
    var failed by mutableStateOf(false)
        private set

    /** Width to height of the picture (0 until known): wide videos are fitted, not cropped. */
    var aspect by mutableFloatStateOf(0f)
        private set

    /** The user paused with a tap; a new short plays again. */
    var userPaused by mutableStateOf(false)

    private var historyFor: String? = null

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                completed++
                YtTestHooks.log(TAG) { "loop id=$currentId plays=$completed" }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                ended = true
                YtTestHooks.log(TAG) { "ended id=$currentId plays=$completed" }
            }
            if (playbackState == Player.STATE_READY) {
                YtTestHooks.log(TAG) { "ready id=$currentId duration=${player.duration}" }
                saveHistory()
            }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onRenderedFirstFrame() {
            firstFrame = true
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            YtTestHooks.log(TAG) { "error id=$currentId ${error.errorCodeName}" }
            failed = true
        }
    }

    // the low-battery cap, as the player service applies it (YtBatteryQualityModule): this
    // player never goes through the service, so it watches the battery itself while it lives
    private val batteryCap = YtBatteryCapWatch { applyBatteryCap() }

    init {
        player.addListener(listener)
        batteryCap.start(context)
        applyBatteryCap()
    }

    // nothing else limits this player's video size or frame rate, so the cap is all there is
    private fun applyBatteryCap() {
        val active = batteryCap.active
        val side = YtBatteryCapRules.shortsMaxSide(YtBattery.capHeight, active)
        val fps = YtBatteryCapRules.maxFrameRate(active, exempt = false)
        val current = player.trackSelectionParameters
        if (current.maxVideoWidth == side && current.maxVideoHeight == side && current.maxVideoFrameRate == fps) return
        player.trackSelectionParameters = current.buildUpon()
            .setMaxVideoSize(side, side)
            .setMaxVideoFrameRate(fps)
            .build()
    }

    private fun load(videoId: String): Deferred<YtShortMedia?> {
        cache.remove(videoId)?.let { old ->
            // kept unless it finished without a result: a failed look-up is asked again
            if (!old.isCompleted || done.containsKey(videoId)) {
                cache[videoId] = old
                return old
            }
        }
        val deferred = scope.async {
            YtShortsSource.resolve(videoId)?.also { done[videoId] = it }
        }
        cache[videoId] = deferred
        while (cache.size > CACHE) {
            val oldest = cache.keys.firstOrNull { it != currentId && it != videoId } ?: break
            cache.remove(oldest)?.cancel()
            done.remove(oldest)
        }
        return deferred
    }

    /** Fetches the information of a short that may come next. */
    fun prefetch(videoId: String) {
        if (!cache.containsKey(videoId)) load(videoId)
    }

    /** The information of a short if it has been fetched already. */
    fun known(videoId: String): YtShortMedia? = done[videoId]

    /** Plays [videoId] from the start; nothing happens if it is the current one already. */
    fun play(videoId: String) {
        if (videoId == currentId) return
        currentId = videoId
        media = null
        completed = 0
        ended = false
        firstFrame = false
        failed = false
        aspect = 0f
        userPaused = false
        job?.cancel()
        player.stop()
        player.clearMediaItems()
        player.repeatMode = Player.REPEAT_MODE_ONE
        val deferred = load(videoId)
        job = scope.launch {
            val found = runCatching { deferred.await() }.getOrNull()
            if (currentId != videoId) return@launch
            if (found == null || !YtShortsSource.apply(context, player, found)) {
                failed = true
                YtTestHooks.log(TAG) { "nothing to play id=$videoId" }
                return@launch
            }
            media = found
            player.prepare()
            player.play()
        }
    }

    /** Tries the current short again after an error. */
    fun retry() {
        val id = currentId ?: return
        currentId = null
        cache.remove(id)
        done.remove(id)
        play(id)
    }

    /** Tap: pause or play. */
    fun toggle() {
        // playWhenReady, not isPlaying: a tap while it buffers pauses too
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            userPaused = true
            player.pause()
        } else {
            userPaused = false
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
            player.play()
        }
    }

    /** Starts the current short from the beginning again (an end that did not scroll). */
    fun replay() {
        ended = false
        completed++
        player.seekTo(0L)
        player.play()
    }

    /** Whether the player repeats the short at its end (off only for the play that scrolls). */
    fun setLoop(loop: Boolean) {
        val mode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (player.repeatMode != mode) player.repeatMode = mode
    }

    fun release() {
        job?.cancel()
        cache.values.forEach { it.cancel() }
        cache.clear()
        done.clear()
        batteryCap.stop()
        player.removeListener(listener)
        player.release()
    }

    // a short counts as watched like a video does, once it plays
    private fun saveHistory() {
        val id = currentId ?: return
        if (historyFor == id || !PlayerHelper.watchHistoryEnabled) return
        val streams = media?.streams ?: return
        historyFor = id
        val entry = streams.toStreamItem(id).toWatchHistoryEntry(player.currentPosition)
        scope.launch(Dispatchers.IO) {
            runCatching { UserDataRepositoryHelper.userDataRepository.addToWatchHistory(entry) }
        }
    }

    companion object {
        const val TAG = "YtShorts"
        private const val CACHE = 8
    }
}
