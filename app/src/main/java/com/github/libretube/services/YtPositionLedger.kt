package com.github.libretube.services

import android.os.Handler
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.github.libretube.api.obj.WatchHistoryEntryMetadata
import com.github.libretube.constants.IntentData
import com.github.libretube.repo.UserDataRepositoryHelper
import com.github.libretube.services.YtPositionRules.Snapshot
import com.github.libretube.ui.yt.YtTestHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * YT: remembers where each video was left, for every player that runs in a player service
 * (page, mini player, PiP, floating player, background, queue; online and offline).
 *
 * While playing, every [YtPositionRules.TICK_MS] it takes a snapshot of the player (on the
 * player's thread, a few getters) and writes it when it moved [YtPositionRules.STEP_MS] since the
 * last write, so a killed process loses at most ~5 s. Pause, seek, switch, end, error, soft close,
 * memory trim and the service's end write at once ([flush], [onSeek], [beforeSwitch]).
 *
 * A snapshot carries the id of the media item the player holds (its metadata extras), not the
 * service's current id, and is only taken once that item has been READY: after a switch the old
 * video is saved from its own last snapshot, and the new video's ~0 before its restore seek is
 * never written over a real position (see [YtPositionRules.toPersist]).
 *
 * Writes go to the existing repository (the same entry as PlayerHelper.saveWatchPosition) on
 * Dispatchers.IO, at most one in flight per video; a newer value replaces a waiting one.
 * No work while paused: the tick runs only while the player plays, on the service's handler.
 */
class YtPositionLedger(
    private val handler: Handler,
    private val player: () -> Player?,
    private val enabled: () -> Boolean,
) {
    /** The item id that reached READY since it became the player's item; snapshots need it. */
    private var readyId: String? = null

    /** The last snapshot taken (any video). */
    private var last: Snapshot? = null

    /** What this ledger last wrote per video (main thread only). */
    private val stored = HashMap<String, Long>()

    private val tick = object : Runnable {
        override fun run() {
            take()?.let { persist(it, forced = false, explicitSeek = false) }
            handler.postDelayed(this, YtPositionRules.TICK_MS)
        }
    }

    val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(tick)
            // the pause itself is saved by the service (saveWatchPosition → flush)
            if (isPlaying && enabled()) handler.postDelayed(tick, YtPositionRules.TICK_MS)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) readyId = player()?.let(::itemId)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) onSeek()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // the player holds the next item already: the one left behind is saved from its
            // last snapshot, never from the player
            val newId = mediaItem?.mediaMetadata?.extras?.getString(IntentData.videoId)
            YtPositionRules.leftBehind(last, newId)?.let { persist(it, forced = true, explicitSeek = false) }
            readyId = null
        }

        override fun onPlayerError(error: PlaybackException) = flush()
    }

    /** Saves now (pause, end, error, soft close, memory trim, end of the service). */
    fun flush() {
        val snap = YtPositionRules.forSwitch(take(), last) ?: return
        persist(snap, forced = true, explicitSeek = false)
    }

    /** A seek: the position after it is saved, a seek to the start included. */
    private fun onSeek() {
        val snap = take() ?: return
        persist(snap, forced = true, explicitSeek = true)
    }

    /**
     * The service is about to switch to [newVideoId] (navigateVideo: next, previous, a video by
     * id, autoplay, queue jump): the video playing now is saved before the player is emptied.
     */
    fun beforeSwitch(newVideoId: String) {
        flush()
        readyId = null
        // the video may have been played before in this service; its entry starts afresh
        stored.remove(newVideoId)
    }

    private fun itemId(p: Player): String? =
        p.currentMediaItem?.mediaMetadata?.extras?.getString(IntentData.videoId)

    /** A snapshot of the player now, or null if it holds no video that has been READY. */
    private fun take(): Snapshot? {
        val p = player() ?: return null
        val id = itemId(p) ?: return null
        if (id != readyId) return null
        val duration = p.duration
        if (duration == androidx.media3.common.C.TIME_UNSET || duration <= 0) return null
        return Snapshot(id, p.currentPosition, duration, p.isCurrentMediaItemLive).also { last = it }
    }

    private fun persist(snap: Snapshot, forced: Boolean, explicitSeek: Boolean) {
        if (!enabled()) return
        val value = YtPositionRules.toPersist(snap, stored[snap.videoId], forced, explicitSeek) ?: return
        stored[snap.videoId] = value
        YtTestHooks.log("YtPositions") { "save ${snap.videoId} $value/${snap.durationMs} forced=$forced seek=$explicitSeek" }
        Writer.enqueue(
            WatchHistoryEntryMetadata(
                videoId = snap.videoId,
                addedDate = System.currentTimeMillis(),
                finished = value == snap.durationMs,
                positionMillis = value,
            )
        )
    }

    /** Coalesced database writes: one in flight per video, the latest waiting value wins. */
    private object Writer {
        private val lock = Any()
        private val waiting = HashMap<String, WatchHistoryEntryMetadata>()
        private val running = HashSet<String>()

        fun enqueue(entry: WatchHistoryEntryMetadata) {
            val id = entry.videoId
            synchronized(lock) {
                waiting[id] = entry
                if (!running.add(id)) return
            }
            CoroutineScope(Dispatchers.IO).launch {
                while (true) {
                    val next = synchronized(lock) {
                        waiting.remove(id) ?: run { running.remove(id); null }
                    } ?: break
                    try {
                        UserDataRepositoryHelper.userDataRepository.updateWatchHistoryEntry(next)
                    } catch (e: Exception) {
                        Log.e("YtPositionLedger", "saving the watch position failed", e)
                    }
                }
            }
        }
    }
}
