package com.github.libretube.util

import com.github.libretube.ui.yt.feed.YtSeenHide
import com.github.libretube.ui.yt.feed.YtSeenMark
import com.github.libretube.ui.yt.feed.YtSubsOrder
import kotlinx.serialization.Serializable

/**
 * YT: pure rules of the seen ledger (YtSeenLedger). A video is marked once playback ended, or
 * the player's position reached [SHARE] of the duration the PLAYER knows (never the card's), or,
 * for a video of [LONG_MS] and more, at most [LEFT_MS] were left. A mark is never removed by a
 * position that falls back (a re-opened video starts at 0), a deleted history entry or a card
 * without a duration; only the user removes it. No Android here, for the JVM tests.
 */
object YtSeenRules {
    const val SHARE = 0.9
    const val LONG_MS = 10 * 60_000L
    const val LEFT_MS = 60_000L

    /** One seen video: when, the best share reached (0..1) and the duration if known. */
    @Serializable
    data class Entry(val id: String, val at: Long, val share: Float, val durationSec: Long? = null)

    /** The position/duration the player holds says "seen" (needs the player's real duration). */
    fun reached(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0 || positionMs < 0) return false
        if (positionMs >= SHARE * durationMs) return true
        return durationMs >= LONG_MS && durationMs - positionMs <= LEFT_MS
    }

    /** Whether a player snapshot marks the video. Live streams never do. */
    fun shouldMark(positionMs: Long, durationMs: Long, ended: Boolean, isLive: Boolean): Boolean =
        !isLive && (ended || reached(positionMs, durationMs))

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
     * The one decision "seen" for every user: the ledger first, else the old share rule when a
     * duration is known ([strict]: the hide filter's stricter rule instead of the mark's).
     */
    fun isSeen(entry: Entry?, positionMs: Long?, durationSec: Long?, strict: Boolean = false): Boolean {
        if (entry != null) return true
        if (strict) return YtSeenHide.finished(positionMs, durationSec)
        return YtSubsOrder.seen(positionMs, durationSec) == YtSubsOrder.Seen.WATCHED
    }

    /** The mark of a card: a ledger entry means a finished mark, else the position's mark. */
    fun markOf(entry: Entry?, positionMs: Long?, durationSec: Long?): YtSeenMark.Mark? =
        if (entry != null) YtSeenMark.Mark(watched = true, progress = 1f) else YtSeenMark.of(positionMs, durationSec)

    /** First run: what was watched to [SHARE] by the old tables (position over the known duration). */
    fun seed(positionsMs: Map<String, Long>, durationsSec: Map<String, Long>, now: Long): List<Entry> =
        positionsMs.mapNotNull { (id, position) ->
            val duration = durationsSec[id]?.takeIf { it > 0 } ?: return@mapNotNull null
            val share = position / 1000.0 / duration
            if (position <= 0 || share < SHARE) null else Entry(id, now, share.coerceAtMost(1.0).toFloat(), duration)
        }

    /**
     * Whether writing [value] as the new stored position would have un-marked a video by the old
     * rule: it has an entry, the old rule saw it before ([previous], null: unknown) and does not now.
     */
    fun preventedUnmark(hasEntry: Boolean, previousMs: Long?, valueMs: Long, durationMs: Long): Boolean {
        if (!hasEntry || durationMs <= 0) return false
        val now = YtSubsOrder.seen(valueMs, durationMs / 1000) == YtSubsOrder.Seen.WATCHED
        val before = previousMs == null || YtSubsOrder.seen(previousMs, durationMs / 1000) == YtSubsOrder.Seen.WATCHED
        return before && !now
    }
}
