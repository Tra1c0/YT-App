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

    /** What is left of a started video, rounded up to whole minutes from one minute on. */
    sealed interface Left {
        data class Seconds(val s: Int) : Left
        data class Minutes(val m: Int) : Left
        data class Hours(val h: Int, val m: Int) : Left
    }

    fun left(progress: Float, durationSeconds: Long?): Left? {
        val total = durationSeconds?.takeIf { it > 0 } ?: return null
        val rest = (total * (1f - progress.coerceIn(0f, 1f))).toLong().coerceAtLeast(0)
        if (rest <= 0) return null
        if (rest < 60) return Left.Seconds(rest.toInt())
        val minutes = ((rest + 59) / 60).toInt()
        return if (minutes < 60) Left.Minutes(minutes) else Left.Hours(minutes / 60, minutes % 60)
    }
}
