package com.github.libretube.util

import com.github.libretube.ui.yt.feed.YtSeenHide
import com.github.libretube.ui.yt.feed.YtSeenMark
import com.github.libretube.ui.yt.feed.YtSubsOrder
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * YT: pure rules of the seen ledger (YtSeenLedger).
 *
 * A video is marked when the PLAYER (never the card) says it is finished and it was played, not
 * only jumped to its end. Finished: playback ended, or the watched mark's own rule holds for the
 * player's duration ([reached]: at most [LEFT_MS] left after at least [NEAR_SHARE], as
 * YtSubsOrder.seen). Played ([played]): a stretch without a jump led there, or half of the video
 * was played in this process; so dragging the time bar to the end and back marks nothing.
 *
 * A mark is never removed by a position that falls back (a re-opened video starts at 0) or by a
 * card without a duration; only the user removes it. No Android here, for the JVM tests.
 */
object YtSeenRules {
    /** The watched mark's rule (YtSubsOrder.seen), on the player's own numbers. */
    const val NEAR_SHARE = 0.75
    const val LEFT_MS = 60_000L

    /** LibreTube's fastest playback: what moved within the elapsed time times this was played. */
    const val MAX_SPEED = 4.0
    const val SLACK_MS = 1_500L

    /** A seek this short (double tap, a sponsor segment, a rewind) does not break a played stretch. */
    const val SKIP_MS = 90_000L

    /** Without an earlier snapshot, only a position this small was surely played from the start. */
    const val YOUNG_MS = 15_000L

    /** The stretch that must lead to the mark: this share of the video, at most [RUN_MS]. */
    const val RUN_SHARE = 0.1
    const val RUN_MS = 60_000L

    /** Playback that ended needs less: the player ran into the end by itself. */
    const val ENDED_RUN_MS = 10_000L

    /** Or this share of the video was played in all (after a skip over the credits to the end). */
    const val PLAYED_SHARE = 0.5

    /** LibreTube's manual "mark as watched" stores this position. */
    const val MANUAL_POSITION = Long.MAX_VALUE

    private const val PARTS = 64

    /** One seen video: when, the best share reached (0..1) and the duration if known. */
    @Serializable
    data class Entry(val id: String, val at: Long, val share: Float, val durationSec: Long? = null)

    /**
     * How a video was played in this process: the last snapshot, the stretch played without a
     * jump ([runMs]) and which of [PARTS] equal parts of the video were played ([covered], a bit each).
     */
    data class Track(val positionMs: Long, val atMs: Long, val runMs: Long, val covered: Long)

    /** The position/duration the player holds says "finished" (needs the player's real duration). */
    fun reached(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0 || positionMs < 0) return false
        return durationMs - positionMs <= LEFT_MS && positionMs >= NEAR_SHARE * durationMs
    }

    /**
     * The track after a snapshot at [positionMs] taken at [nowMs] (a monotonic clock). [seek]: the
     * snapshot follows a seek. Playing adds to the stretch and the parts; a short seek keeps the
     * stretch; a jump starts it again.
     */
    fun track(old: Track?, positionMs: Long, durationMs: Long, nowMs: Long, seek: Boolean): Track {
        val position = positionMs.coerceAtLeast(0)
        if (old == null) {
            val young = if (!seek && position <= YOUNG_MS) position else 0L
            return Track(position, nowMs, young, cover(0L, 0L, young, durationMs))
        }
        val moved = position - old.positionMs
        val playable = ((nowMs - old.atMs).coerceAtLeast(0) * MAX_SPEED).toLong() + SLACK_MS
        return when {
            !seek && moved in 0..playable ->
                Track(position, nowMs, old.runMs + moved, cover(old.covered, old.positionMs, position, durationMs))
            abs(moved) <= SKIP_MS -> Track(position, nowMs, old.runMs, old.covered)
            else -> Track(position, nowMs, 0L, old.covered)
        }
    }

    private fun cover(mask: Long, fromMs: Long, toMs: Long, durationMs: Long): Long {
        if (durationMs <= 0 || toMs <= fromMs) return mask
        val first = (fromMs * PARTS / durationMs).coerceIn(0L, PARTS - 1L).toInt()
        val last = ((toMs - 1) * PARTS / durationMs).coerceIn(0L, PARTS - 1L).toInt()
        var result = mask
        for (part in first..last) result = result or (1L shl part)
        return result
    }

    /** The share of the video that [track] played (0..1, in steps of 1/[PARTS]). */
    fun playedShare(track: Track): Double = java.lang.Long.bitCount(track.covered) / PARTS.toDouble()

    /** Whether [track] shows the video was played to where it is, not only jumped there. */
    fun played(track: Track?, durationMs: Long, ended: Boolean): Boolean {
        if (track == null || durationMs <= 0) return false
        val stretch = if (ended) minOf(ENDED_RUN_MS, durationMs / 2) else minOf(RUN_MS, (RUN_SHARE * durationMs).toLong())
        return track.runMs >= stretch || playedShare(track) >= PLAYED_SHARE
    }

    /** Whether a player snapshot marks a video that has no mark yet. Live streams never do. */
    fun shouldMark(positionMs: Long, durationMs: Long, ended: Boolean, isLive: Boolean, track: Track?): Boolean =
        !isLive && (ended || reached(positionMs, durationMs)) && played(track, durationMs, ended)

    /** The entry after a mark: the earlier best share and duration stay when the new ones are worse. */
    fun mark(old: Entry?, id: String, positionMs: Long, durationMs: Long, ended: Boolean, now: Long): Entry {
        val share = when {
            ended -> 1f
            durationMs > 0 -> (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
            else -> 0f
        }
        val seconds = durationMs.takeIf { it > 0 }?.div(1000) ?: old?.durationSec
        return Entry(id, now, maxOf(share, old?.share ?: 0f), seconds)
    }

    /**
     * Whether an entry also counts for "Gesehenes ausblenden", whose rule is stricter than the
     * mark's (YtSeenHide: a video stopped a minute before its end stays in the lists).
     */
    fun finished(entry: Entry): Boolean {
        if (entry.share >= YtSeenHide.SHARE) return true
        val duration = entry.durationSec?.takeIf { it > 0 } ?: return false
        return (1f - entry.share) * duration <= YtSeenHide.LEFT_SECONDS && entry.share >= YtSeenHide.MIN_SHARE_NEAR_END
    }

    /**
     * The one decision "seen" for every user: the ledger first, else the old rule on the watch
     * position when a duration is known ([strict]: the hide filter's rule instead of the mark's).
     */
    fun isSeen(entry: Entry?, positionMs: Long?, durationSec: Long?, strict: Boolean = false): Boolean {
        if (entry != null && (!strict || finished(entry))) return true
        if (strict) return YtSeenHide.finished(positionMs, durationSec)
        return YtSubsOrder.seen(positionMs, durationSec) == YtSubsOrder.Seen.WATCHED
    }

    /** The mark of a card: a ledger entry means a finished mark, else the position's mark. */
    fun markOf(entry: Entry?, positionMs: Long?, durationSec: Long?): YtSeenMark.Mark? =
        if (entry != null) YtSeenMark.Mark(watched = true, progress = 1f) else YtSeenMark.of(positionMs, durationSec)

    /**
     * First run: what the old tables call watched: the watched mark's rule on the stored position
     * and the known duration, and LibreTube's manual "watched" whatever the duration.
     */
    fun seed(positionsMs: Map<String, Long>, durationsSec: Map<String, Long>, now: Long): List<Entry> =
        positionsMs.mapNotNull { (id, position) ->
            val duration = durationsSec[id]?.takeIf { it > 0 }
            when {
                position == MANUAL_POSITION -> Entry(id, now, 1f, duration)
                duration == null || position <= 0 -> null
                YtSubsOrder.seen(position, duration) != YtSubsOrder.Seen.WATCHED -> null
                else -> Entry(id, now, (position / 1000.0 / duration).coerceIn(0.0, 1.0).toFloat(), duration)
            }
        }

    /**
     * Whether writing [valueMs] as the new stored position would have un-marked a video by the old
     * rule: it has an entry, the old rule saw it before ([previousMs], null: unknown) and does not now.
     */
    fun preventedUnmark(hasEntry: Boolean, previousMs: Long?, valueMs: Long, durationMs: Long): Boolean {
        if (!hasEntry || durationMs <= 0) return false
        val now = YtSubsOrder.seen(valueMs, durationMs / 1000) == YtSubsOrder.Seen.WATCHED
        val before = previousMs == null || YtSubsOrder.seen(previousMs, durationMs / 1000) == YtSubsOrder.Seen.WATCHED
        return before && !now
    }
}
