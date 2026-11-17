package com.github.libretube.util

/**
 * YT: the pure part of [YtFetchGate]. The one pause after a bot check or HTTP 429 is the
 * archive's stored throttle state (`backoffUntil`); every background fetcher reads it.
 */
object YtFetchGateRules {
    /** How long a background job waits for the gate before it skips its run. */
    const val BACKGROUND_WAIT_MS = 60_000L

    /** The gate is polled this often while a job waits. */
    const val POLL_MS = 250L

    /** The latest of the stored pause-until times that is still ahead of [now], or null. */
    fun pausedUntil(backoffs: List<Long>, now: Long): Long? = backoffs.filter { it > now }.maxOrNull()

    /** Whether a job that started waiting at [startedAt] has waited [waitMs] by [now]. */
    fun waitOver(startedAt: Long, waitMs: Long, now: Long): Boolean = now - startedAt >= waitMs

    /** The pause after the [strikes]th block in a row: 6 h, then 12, 24, 48 h. */
    fun pauseMs(strikes: Int, first: Long, max: Long): Long = (first shl (strikes - 1).coerceIn(0, 4)).coerceAtMost(max)
}
