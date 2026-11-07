package com.github.libretube.ui.yt.feed

/**
 * YT: the order of the subscriptions tab's "All" filter. Every open and every pull draws a new
 * [seed], so the list is a fresh mix each time instead of a copy of "Today" with older videos
 * below. The mix is rough on purpose: unwatched videos come first, partly watched ones after
 * them and fully watched ones last, each group shuffled on its own.
 *
 * The shuffle sorts by a hash of (seed, video id) rather than a Fisher-Yates pass over the list,
 * so a video hidden or added later (a background refresh, the undo pill) does not reshuffle the
 * rest: everything else keeps its place while the user scrolls.
 */
object YtSubsOrder {
    /** How far the user got into a video. */
    enum class Seen { UNWATCHED, PARTLY, WATCHED }

    /**
     * [items] in the random order of [seed], grouped by [seen] (unwatched first). [id] gives the
     * key a video is shuffled by; items whose id is null keep their relative order at the end
     * of their group.
     */
    fun <T> shuffle(items: List<T>, seed: Long, id: (T) -> String?, seen: (T) -> Seen): List<T> =
        items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<T>> { seen(it.value).ordinal }
                    .thenBy { entry -> id(entry.value)?.let { rank(seed, it) } ?: Long.MAX_VALUE }
                    .thenBy { it.index },
            )
            .map { it.value }

    /**
     * The group of a video from its watch position. Thresholds as in LibreTube's "watched"
     * check (DatabaseHelper.isVideoWatched): at most 60 s left and at least 75 % seen. A few
     * seconds of playback (an accidental tap) still count as unwatched.
     */
    fun seen(positionMillis: Long?, durationSeconds: Long?): Seen {
        if (positionMillis == null || positionMillis < MIN_PARTLY_MILLIS) return Seen.UNWATCHED
        val duration = durationSeconds ?: return Seen.PARTLY
        if (duration <= 0) return Seen.PARTLY
        val progress = positionMillis / 1000
        val watched = duration - progress <= WATCHED_LEFT_SECONDS && progress >= WATCHED_SHARE * duration
        return if (watched) Seen.WATCHED else Seen.PARTLY
    }

    /** A well mixed 64-bit rank of [id] for [seed] (splitmix64 finaliser). */
    internal fun rank(seed: Long, id: String): Long {
        var z = seed xor (id.hashCode().toLong() * -0x61c8864680b583ebL)
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private const val MIN_PARTLY_MILLIS = 10_000L
    private const val WATCHED_LEFT_SECONDS = 60f
    private const val WATCHED_SHARE = 0.75f
}
