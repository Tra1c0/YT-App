package com.github.libretube.ui.yt.feed

/**
 * YT: "Gesehenes ausblenden" (Feed-Regler, YtFeedPersonal.hideSeen): when a video counts as
 * seen to the end and leaves the home lists. Stricter than the watched mark (YtSubsOrder.seen),
 * so a video stopped a minute before the end stays: at least [SHARE] of it, or at most
 * [LEFT_SECONDS] left after at least [MIN_SHARE_NEAR_END] (a short clip opened for a second
 * is not "seen"). A video of unknown length is never hidden. Pure, for the JVM tests.
 */
object YtSeenHide {
    const val SHARE = 0.9f
    const val LEFT_SECONDS = 30L
    const val MIN_SHARE_NEAR_END = 0.5f

    fun finished(positionMillis: Long?, durationSeconds: Long?): Boolean {
        if (positionMillis == null || positionMillis <= 0) return false
        val duration = durationSeconds?.takeIf { it > 0 } ?: return false
        val seconds = positionMillis / 1000f
        val share = seconds / duration
        if (share >= SHARE) return true
        return duration - seconds <= LEFT_SECONDS && share >= MIN_SHARE_NEAR_END
    }

    /** [items] without the finished ones; the same list when nothing goes. */
    fun <T> visible(items: List<T>, position: (T) -> Long?, duration: (T) -> Long?): List<T> {
        val kept = items.filterNot { finished(position(it), duration(it)) }
        return if (kept.size == items.size) items else kept
    }
}
