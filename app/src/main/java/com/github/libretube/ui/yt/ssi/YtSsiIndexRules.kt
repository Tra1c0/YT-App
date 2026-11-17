package com.github.libretube.ui.yt.ssi

/**
 * YT: the pure rules of the SSI index's health (round 38): the order and budget of a background
 * run's local work, the follow-up chain, what leaves the index and when the vectors leave memory.
 */
object YtSsiIndexRules {
    // ---- work order and budget ----

    enum class Step {
        /** Videos without any vector, newest first: cut by meaning (model present) and embed their passages in one pass. */
        CUT_AND_EMBED,

        /** Remaining passages without a vector. */
        EMBED,

        /** Old rules-cut videos that already have vectors: re-cut (and embed the new passages). */
        RECUT,
    }

    /** The local steps in priority order; without the model nothing local is embedded or cut. */
    fun plan(vectorlessVideos: Int, unembedded: Int, rulesCutWithVectors: Int, modelReady: Boolean): List<Step> {
        if (!modelReady) return emptyList()
        return buildList {
            if (vectorlessVideos > 0) add(Step.CUT_AND_EMBED)
            if (unembedded > 0 || vectorlessVideos > 0) add(Step.EMBED)
            if (rulesCutWithVectors > 0) add(Step.RECUT)
        }
    }

    /** Local work (cut, embed, evict) gets everything up to this point; WorkManager stops a worker after 10 minutes. */
    const val LOCAL_BUDGET_MS = 9L * 60 * 1000

    fun localDeadline(start: Long): Long = start + LOCAL_BUDGET_MS

    // ---- follow-up chain ----

    const val MAX_IDLE_FOLLOW_UPS = 3
    const val FOLLOW_UP_DELAY_MIN = 1L

    /** A follow-up run follows when work remains, the phone charges, is cool and the last runs got somewhere. */
    fun followUp(backlog: Int, charging: Boolean, batteryLow: Boolean, hot: Boolean, idleFollowUps: Int): Boolean =
        backlog > 0 && charging && !batteryLow && !hot && idleFollowUps < MAX_IDLE_FOLLOW_UPS

    /** Runs in a row without progress after a run that [progress]ed (or not). */
    fun idleCount(previous: Int, progress: Boolean): Int = if (progress) 0 else previous + 1

    // ---- window and cap ----

    val WINDOW_DAYS = listOf(14, 30, 60, 90, 0)
    const val DEFAULT_WINDOW_DAYS = 30

    /** 0 = unlimited. */
    fun windowDays(raw: String?): Int = raw?.toIntOrNull()?.takeIf { it in WINDOW_DAYS } ?: DEFAULT_WINDOW_DAYS

    const val CAP_PASSAGES = 250_000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    data class Candidate(
        val videoId: String,
        /** When the video entered the index. */
        val indexedAt: Long,
        val passages: Int,
        /** Opened or rated from results, in a saved research or the benchmark, vanished or kept in the archive. */
        val protected: Boolean,
        /** Has a watch position. */
        val watched: Boolean,
    )

    /**
     * Videos to evict, in order: first the unprotected, unwatched ones older than the window
     * (oldest first); then, while the total still exceeds [cap], more unprotected ones (oldest
     * first), and last the watched ones (oldest first). Protected videos never go.
     */
    fun evictions(candidates: List<Candidate>, now: Long, windowDays: Int, cap: Int = CAP_PASSAGES): List<String> {
        val out = ArrayList<String>()
        var total = candidates.sumOf { it.passages.toLong() }
        val free = candidates.filter { !it.protected && !it.watched }.sortedBy { it.indexedAt }
        val taken = HashSet<String>()
        if (windowDays > 0) {
            val limit = now - windowDays * DAY_MS
            for (c in free) if (c.indexedAt < limit) {
                out += c.videoId; taken += c.videoId; total -= c.passages
            }
        }
        for (c in free) {
            if (total <= cap) break
            if (c.videoId in taken) continue
            out += c.videoId; taken += c.videoId; total -= c.passages
        }
        for (c in candidates.filter { !it.protected && it.watched }.sortedBy { it.indexedAt }) {
            if (total <= cap) break
            out += c.videoId; total -= c.passages
        }
        return out
    }

    // ---- tombstones ----

    /** Whether the archive pass and the feed pass may index [videoId]: not while it is a tombstone. */
    fun mayIndex(videoId: String, tombstones: Set<String>): Boolean = videoId !in tombstones

    // ---- vectors in memory ----

    const val IDLE_RELEASE_MS = 5L * 60 * 1000

    /** android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW. */
    const val TRIM_RUNNING_LOW = 10

    /** The vectors in memory go after 5 minutes without SSI use, or on a trim at RUNNING_LOW or worse. */
    fun releaseVectors(loaded: Boolean, lastUse: Long, now: Long, trimLevel: Int? = null): Boolean =
        loaded && (now - lastUse >= IDLE_RELEASE_MS || (trimLevel != null && isLow(trimLevel)))

    /** RUNNING_LOW (10), RUNNING_CRITICAL (15), COMPLETE (80), MODERATE (60), BACKGROUND (40) count; UI_HIDDEN (20) and RUNNING_MODERATE (5) do not. */
    fun isLow(level: Int): Boolean = level >= TRIM_RUNNING_LOW && level != 20
}
