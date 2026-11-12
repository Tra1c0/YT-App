package com.github.libretube.util

import java.time.Instant

/**
 * YT: the pure rules of the "attention mode" ([YtUpdateWatch]): while a build of main runs on
 * GitHub, a few extra checks shortly after it should be out, instead of waiting hours for the
 * periodic one. Works without the instant push; a "started" push only starts it sooner.
 * No Android types, so they run in JVM tests.
 */
object YtUpdateWatchRules {
    /** Minutes after the run's start at which a check runs (a build takes about 10 to 15 min). */
    val SLOT_MINUTES = listOf(12L, 16L, 20L, 30L, 45L)

    /** A slot due within this time from now is still taken (WorkManager is not exact anyway). */
    const val SLACK_MS = 30_000L

    /**
     * At least this long between two looks at the running builds: GitHub allows 60 requests an
     * hour without an account, shared with the release check.
     */
    const val PROBE_GAP_MS = 10 * 60_000L

    /** A run started longer ago than the last slot is over (or failed) and is not watched. */
    val MAX_AGE_MS = SLOT_MINUTES.last() * 60_000L

    /** Workflow run states of GitHub that mean "not done yet". */
    private val ACTIVE = setOf("queued", "in_progress", "waiting", "pending", "requested")

    /** One run of the build workflow, as the Actions API lists it. */
    data class Run(val number: Int, val status: String?, val branch: String?, val startedAt: Long?)

    /**
     * The time of the next check of a run that started at [runStart]: the first slot that is not
     * past at [now] (minus [SLACK_MS]) and later than the slot that ran last ([after]); null when
     * all slots are past, which ends the watch.
     */
    fun nextSlot(runStart: Long, now: Long, after: Long? = null): Long? = SLOT_MINUTES
        .map { runStart + it * 60_000L }
        .firstOrNull { it >= now - SLACK_MS && (after == null || it > after) }

    /** The delay from [now] to [slot], never negative. */
    fun delay(slot: Long, now: Long): Long = (slot - now).coerceAtLeast(0L)

    /** Whether the running builds may be looked up now; the last look was at [lastProbe]. */
    fun mayProbe(lastProbe: Long, now: Long): Boolean =
        lastProbe !in 1..now || now - lastProbe >= PROBE_GAP_MS

    /**
     * The run to watch among [runs]: the newest build of main that is still running, started
     * within [MAX_AGE_MS] and newer than the [installed] build and the one notified last;
     * null when there is none.
     */
    fun pickRun(runs: List<Run>, installed: Int?, lastNotified: Int?, now: Long): Run? = runs
        .filter { it.branch == null || it.branch == "main" }
        .filter { it.status?.lowercase() in ACTIVE }
        .filter { YtUpdateRules.isNewer(installed, it.number) && (lastNotified == null || it.number > lastNotified) }
        .filter { run -> run.startedAt == null || now - run.startedAt in -SLACK_MS..MAX_AGE_MS }
        .maxByOrNull { it.number }

    /**
     * Whether a watch for [build] (started at [start]) replaces the watch that runs (for
     * [activeBuild], null when none): a newer build does; the same or an older one does not,
     * so a repeated "started" or a second look does not push the slots further out. An unknown
     * build replaces only an idle watch.
     */
    fun replaces(build: Int?, activeBuild: Int?, activeStart: Long?, start: Long): Boolean {
        if (activeStart == null || nextSlot(activeStart, start) == null) return true
        if (build == null) return false
        return activeBuild == null || build > activeBuild
    }

    /** GitHub's time ("2026-11-12T10:00:00Z") in ms, or null. */
    fun parseTime(text: String?): Long? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}
