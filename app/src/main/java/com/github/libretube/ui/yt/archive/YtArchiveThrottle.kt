package com.github.libretube.ui.yt.archive

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * YT: how hard the archive may ask YouTube, so it never looks like a bot (a bot check would also
 * hit normal playback). Pure, kept as JSON in the archive's prefs.
 *
 * - Per run at most [TEXT_PER_RUN] text saves (one stream-info request, one caption file and
 *   one small thumbnail each), [MEDIA_PER_RUN] audio/video saves, [URGENT_PER_RUN] high-risk
 *   audio saves and [CHECKS_PER_RUN] light checks.
 * - At most [MEDIA_PER_DAY] audio/video saves per day (the night the phone charges).
 * - A random pause of 2–8 s ([jitterMs]) between any two videos.
 * - A bot check or HTTP 429 ends the run and pauses the archive: 6 h, then 12, 24, 48 h for each
 *   further one in a row ([afterBlock]); a run without one clears the count.
 * - [ERRORS_IN_ROW] other failures in a row end the run (the network or YouTube is off now).
 * - A run stops after [RUN_BUDGET_MS] (WorkManager stops a worker after 10 minutes).
 */
object YtArchiveThrottle {
    const val TEXT_PER_RUN = 15
    const val MEDIA_PER_RUN = 6
    const val URGENT_PER_RUN = 3
    const val CHECKS_PER_RUN = 5
    const val MEDIA_PER_DAY = 20
    const val JITTER_MIN_MS = 2_000L
    const val JITTER_MAX_MS = 8_000L
    const val ERRORS_IN_ROW = 3
    const val RUN_BUDGET_MS = 8L * 60 * 1000
    private const val HOUR_MS = 60L * 60 * 1000
    const val BACKOFF_FIRST_MS = 6 * HOUR_MS
    const val BACKOFF_MAX_MS = 48 * HOUR_MS
    private const val DAY_MS = 24 * HOUR_MS

    @Serializable
    data class State(
        val backoffUntil: Long = 0,
        val strikes: Int = 0,
        /** The local day ([dayOf]) [mediaToday] counts for. */
        val day: Long = 0,
        val mediaToday: Int = 0,
        val lastRun: Long = 0,
        val lastRunKind: String? = null,
        val lastError: String? = null,
        val lastErrorAt: Long = 0,
    )

    /** The pause before the next video, 2–8 s. */
    fun jitterMs(random: Random = Random.Default): Long = random.nextLong(JITTER_MIN_MS, JITTER_MAX_MS + 1)

    /** The local day of [now]; [offsetMs] is the time zone's offset, so the day turns at local midnight. */
    fun dayOf(now: Long, offsetMs: Long): Long = Math.floorDiv(now + offsetMs, DAY_MS)

    fun paused(state: State, now: Long): Boolean = now < state.backoffUntil

    /** How many audio/video saves are left today. */
    fun mediaLeft(state: State, now: Long, offsetMs: Long): Int =
        if (state.day != dayOf(now, offsetMs)) MEDIA_PER_DAY else (MEDIA_PER_DAY - state.mediaToday).coerceAtLeast(0)

    fun countMedia(state: State, now: Long, offsetMs: Long): State {
        val today = dayOf(now, offsetMs)
        return if (state.day != today) state.copy(day = today, mediaToday = 1) else state.copy(mediaToday = state.mediaToday + 1)
    }

    /** A bot check or 429: pause 6 h, doubling per strike in a row, at most 48 h. */
    fun afterBlock(state: State, now: Long, reason: String): State {
        val strikes = state.strikes + 1
        val pause = (BACKOFF_FIRST_MS shl (strikes - 1).coerceIn(0, 4)).coerceAtMost(BACKOFF_MAX_MS)
        return state.copy(backoffUntil = now + pause, strikes = strikes, lastError = reason, lastErrorAt = now)
    }

    fun afterError(state: State, now: Long, reason: String): State = state.copy(lastError = reason, lastErrorAt = now)

    /** A run that ended without a block. */
    fun afterRun(state: State, now: Long, kind: String, blocked: Boolean): State =
        state.copy(lastRun = now, lastRunKind = kind, strikes = if (blocked) state.strikes else 0)
}
