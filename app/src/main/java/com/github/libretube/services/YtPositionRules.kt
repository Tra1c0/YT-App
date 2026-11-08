package com.github.libretube.services

import com.github.libretube.repo.LocalUserDataRepository.Companion.ABSOLUTE_WATCHED_THRESHOLD
import com.github.libretube.repo.LocalUserDataRepository.Companion.RELATIVE_WATCHED_THRESHOLD

/**
 * YT: pure rules of the watch position ledger (YtPositionLedger): when a position goes to the
 * database, what is stored for a finished video, and which snapshot belongs to the video left
 * behind on a switch.
 */
object YtPositionRules {
    /** How often the ledger looks at the playing player. */
    const val TICK_MS = 5_000L

    /** A position goes to the database once it moved this far since the last stored one. */
    const val STEP_MS = 5_000L

    /**
     * Positions this close to the start are not stored unless the user sought there: a video
     * that just started (or whose saved position is still being restored) must not overwrite a
     * real saved position with ~0.
     */
    const val MIN_START_MS = 5_000L

    /** Share of the video after which it counts as finished, whatever is left. */
    const val FINISHED_SHARE = 0.95

    /** What the player held at one moment, read on the player's thread. */
    data class Snapshot(
        val videoId: String,
        val positionMs: Long,
        val durationMs: Long,
        val isLive: Boolean,
    )

    /**
     * Finished: the app's existing rule (DatabaseHelper.isVideoWatched: at most 60 s left and
     * at least 75 % seen, which covers "the last 30 s" for every video of 2 min or more) or at
     * least [FINISHED_SHARE] seen. A plain "last 30 s" rule is not added: it would call a short
     * video finished a few seconds in.
     */
    fun isFinished(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        val progress = positionMs / 1000
        val durationSeconds = durationMs / 1000
        val existing = durationSeconds - progress <= ABSOLUTE_WATCHED_THRESHOLD &&
            progress >= RELATIVE_WATCHED_THRESHOLD * durationSeconds
        return existing || positionMs >= FINISHED_SHARE * durationMs
    }

    /**
     * The value stored for [snap]: a finished video is stored at its full duration, so that
     * every place that reads positions (start of playback, marks, lists) sees it as watched and
     * the next start begins at 0.
     */
    fun storedValue(snap: Snapshot): Long {
        val position = snap.positionMs.coerceIn(0, snap.durationMs)
        return if (isFinished(position, snap.durationMs)) snap.durationMs else position
    }

    /**
     * The position to write for [snap], or null for none. [lastStored] is what this ledger last
     * wrote for the video (null: nothing yet). [forced] saves (pause, switch, end, close) skip
     * the [STEP_MS] step; [explicitSeek] lets a position near the start through.
     */
    fun toPersist(snap: Snapshot, lastStored: Long?, forced: Boolean, explicitSeek: Boolean): Long? {
        if (snap.isLive || snap.durationMs <= 0 || snap.positionMs < 0) return null
        if (snap.positionMs < MIN_START_MS && !explicitSeek) return null
        val value = storedValue(snap)
        if (lastStored == null) return value
        if (value == lastStored) return null
        if (!forced && kotlin.math.abs(value - lastStored) < STEP_MS) return null
        return value
    }

    /**
     * The snapshot to save for the video being left: what the player holds right now if it is
     * still readable ([live]), else the last one taken ([last]).
     */
    fun forSwitch(live: Snapshot?, last: Snapshot?): Snapshot? = live ?: last

    /**
     * On a media item transition the player already holds [newItemId]: the last snapshot is
     * saved only if it belongs to another video (never read the old video from the player now).
     */
    fun leftBehind(last: Snapshot?, newItemId: String?): Snapshot? =
        last?.takeIf { it.videoId != newItemId }
}
