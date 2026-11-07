package com.github.libretube.ui.yt.feed

/**
 * YT: the quiet mark a home card gets for a video the user already watched: a thin progress
 * bar for a started one, a dimmed thumbnail and a small "Gesehen" badge for a finished one.
 * Same groups as the subscriptions' order ([YtSubsOrder.seen]). Pure, for the JVM tests.
 */
object YtSeenMark {
    /** [progress] is the watched share (0..1) for a started video, null when its length is unknown. */
    data class Mark(val watched: Boolean, val progress: Float?)

    /** Brightness a fully watched thumbnail keeps. */
    const val WATCHED_BRIGHTNESS = 0.65f

    /** A bar never looks emptier than this, so a started video is visibly marked. */
    private const val MIN_BAR = 0.04f

    /** The mark for [positionMillis] into a video of [durationSeconds]; null when there is none (unwatched). */
    fun of(positionMillis: Long?, durationSeconds: Long?): Mark? = when (YtSubsOrder.seen(positionMillis, durationSeconds)) {
        YtSubsOrder.Seen.UNWATCHED -> null
        YtSubsOrder.Seen.WATCHED -> Mark(watched = true, progress = 1f)
        YtSubsOrder.Seen.PARTLY -> {
            val duration = durationSeconds?.takeIf { it > 0 }
            val share = duration?.let { (positionMillis!! / 1000f / it).coerceIn(MIN_BAR, 1f) }
            Mark(watched = false, progress = share)
        }
    }
}
