package com.github.libretube.util

import android.content.Context
import android.os.SystemClock
import com.github.libretube.ui.yt.archive.YtArchive
import com.github.libretube.ui.yt.archive.YtArchiveThrottle
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * YT: one process-wide gate for background YouTube traffic (archive runs, SSI indexing, Deep
 * fetches, the dead-subscriptions scan): one fetcher at a time, so their paces do not add up.
 * Background jobs use [background] and skip their run when the gate stays busy; user-started
 * work uses [user] and waits. Playback and browsing never come here. The pause after a bot
 * check or 429 is the archive's stored throttle state ([pausedUntil], [reportBlock]).
 */
object YtFetchGate {
    private val mutex = Mutex()

    @Volatile
    private var holder: String? = null

    @Volatile
    private var lastSkip: Pair<String, Long>? = null

    /** Runs [block] when the gate is free within [waitMs]; false (and a note for Diagnose) when it stayed busy. */
    suspend fun background(who: String, waitMs: Long = YtFetchGateRules.BACKGROUND_WAIT_MS, block: suspend () -> Unit): Boolean {
        val started = SystemClock.elapsedRealtime()
        while (!mutex.tryLock()) {
            if (YtFetchGateRules.waitOver(started, waitMs, SystemClock.elapsedRealtime())) {
                lastSkip = "$who (busy: ${holder ?: "?"})" to System.currentTimeMillis()
                return false
            }
            delay(YtFetchGateRules.POLL_MS)
        }
        holder = who
        try {
            block()
        } finally {
            holder = null
            mutex.unlock()
        }
        return true
    }

    /** Runs [block] once the gate is free; never skipped. */
    suspend fun <T> user(who: String, block: suspend () -> T): T = mutex.withLock {
        holder = who
        try {
            block()
        } finally {
            holder = null
        }
    }

    fun pausedUntil(context: Context, now: Long = System.currentTimeMillis()): Long? =
        YtFetchGateRules.pausedUntil(listOf(YtArchive.throttle(context).backoffUntil), now)

    /** A bot check or 429 anywhere: pauses all background fetching (the archive's state, atomically). */
    fun reportBlock(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        YtArchive.updateThrottle(context) { YtArchiveThrottle.afterBlock(it, now, reason) }
    }

    /** For Diagnose. */
    fun describe(): String = buildString {
        append(holder?.let { "busy ($it)" } ?: "free")
        lastSkip?.let { (who, at) -> append(", last skipped: $who at ${YtCrashLog.formatTime(at)}") }
    }
}
