package com.github.libretube.ui.yt.shorts

import java.util.Locale

/**
 * YT: the plain rules of the Shorts view, kept free of Android so they run in JVM tests:
 * when the view scrolls on by itself ([YtShortsAutoscroll]), how the hidden timeline maps a
 * finger to a position ([YtShortsScrub]) and when the list should grow ([YtShortsList]).
 */
object YtShortsAutoscroll {
    /** The choices of the setting: 0 is off, else the plays of a short before it scrolls on. */
    val CHOICES = intArrayOf(0, 1, 2, 3, 5)

    /**
     * One play, then on to the next short. The user asked for autoscroll, so it is on from the
     * start; the countdown before each scroll can be tapped to stay, and the quick toggle in
     * the view turns it off with one tap.
     */
    const val DEFAULT = 1

    /** The countdown before a scroll, at most this long (half of a very short short). */
    const val COUNTDOWN_MS = 3000L

    /** The stored value of the setting ("off", "1", "2", "3", "5") as a number of plays. */
    fun parse(value: String?): Int {
        if (value == null) return DEFAULT
        if (value == "off") return 0
        val n = value.toIntOrNull() ?: return DEFAULT
        return if (n in CHOICES) n else DEFAULT
    }

    /** The stored value for [plays]. */
    fun format(plays: Int): String = if (plays <= 0) "off" else plays.toString()

    /**
     * What the player does now.
     * [loop]: the player repeats the short at its end (false only on the play that scrolls).
     * [countdownMs]: the time left before the scroll while the countdown shows, else null.
     * [scroll]: go to the next short now.
     */
    data class Decision(val loop: Boolean, val countdownMs: Long?, val scroll: Boolean) {
        companion object {
            val LOOP = Decision(loop = true, countdownMs = null, scroll = false)
        }
    }

    /**
     * [plays]: the setting (0 = off). [completed]: how often the short has played to its end
     * since it came on screen. [blocked]: a finger is down, the timeline is open or the user
     * chose to stay on this short; then it never scrolls, and the short loops (a later end
     * tries again). [ended]: the player reached the end without looping.
     */
    fun decide(
        plays: Int,
        completed: Int,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
        blocked: Boolean,
        hasNext: Boolean,
    ): Decision {
        if (plays <= 0 || !hasNext || blocked) return Decision.LOOP
        // the play that ends in a scroll is the plays-th one
        if (completed < plays - 1) return Decision.LOOP
        if (ended) return Decision(loop = false, countdownMs = null, scroll = true)
        if (durationMs <= 0L) return Decision(loop = false, countdownMs = null, scroll = false)
        val window = minOf(COUNTDOWN_MS, durationMs / 2)
        val left = (durationMs - positionMs).coerceAtLeast(0L)
        return Decision(loop = false, countdownMs = if (left <= window) left else null, scroll = false)
    }

    /** The whole seconds the countdown shows for [leftMs] (3, 2, 1). */
    fun seconds(leftMs: Long): Int = ((leftMs.coerceAtLeast(0L) + 999L) / 1000L).toInt().coerceAtLeast(1)
}

object YtShortsScrub {
    /** The timeline fades out this long after the finger lifts. */
    const val HIDE_AFTER_MS = 1200L

    /** The touch zone along the bottom edge, above the navigation bar, in dp. */
    const val ZONE_DP = 48f

    /**
     * Whether a touch at [y] starts the timeline: in the [zone] above the bottom [inset] of a
     * screen [height] high. The inset itself counts too, for the three-button bar (the gesture
     * bar takes its own touches before the app sees them).
     */
    fun inZone(y: Float, height: Float, inset: Float, zone: Float): Boolean =
        height > 0f && y >= height - inset - zone && y <= height

    /** The fraction of the bar under [x], with [padding] left free at both ends. */
    fun fraction(x: Float, width: Float, padding: Float): Float {
        val track = width - 2f * padding
        if (track <= 0f) return 0f
        return ((x - padding) / track).coerceIn(0f, 1f)
    }

    /** The position for [fraction] of a short [durationMs] long. */
    fun position(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (fraction.coerceIn(0f, 1f).toDouble() * durationMs).toLong().coerceIn(0L, durationMs)
    }

    /** The x of [positionMs] on the bar, the inverse of [fraction] and [position]. */
    fun x(positionMs: Long, durationMs: Long, width: Float, padding: Float): Float {
        val track = (width - 2f * padding).coerceAtLeast(0f)
        if (durationMs <= 0L) return padding
        return padding + track * (positionMs.coerceIn(0L, durationMs).toFloat() / durationMs)
    }

    /** Whether the finger moved from [fromMs] into another whole second (one tick per second). */
    fun crossedSecond(fromMs: Long, toMs: Long): Boolean =
        fromMs.coerceAtLeast(0L) / 1000L != toMs.coerceAtLeast(0L) / 1000L

    /** "0:07", "1:05", "1:02:03". */
    fun label(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000L
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

object YtShortsList {
    /** Load more once the user is this close to the end. */
    const val AHEAD = 2

    fun shouldLoadMore(page: Int, size: Int, loading: Boolean, exhausted: Boolean): Boolean =
        !loading && !exhausted && size > 0 && page >= size - 1 - AHEAD

    /** [current] followed by the items of [more] that are not in it yet (by [key]), in order. */
    fun <T> append(current: List<T>, more: List<T>, key: (T) -> String?): List<T> {
        val seen = current.mapNotNullTo(HashSet()) { key(it) }
        val added = more.filter { item -> key(item)?.let { seen.add(it) } == true }
        return if (added.isEmpty()) current else current + added
    }

    /** The page to start on: [startKey]'s page, else the first. */
    fun <T> startPage(items: List<T>, startKey: String?, key: (T) -> String?): Int =
        items.indexOfFirst { key(it) == startKey }.coerceAtLeast(0)
}
