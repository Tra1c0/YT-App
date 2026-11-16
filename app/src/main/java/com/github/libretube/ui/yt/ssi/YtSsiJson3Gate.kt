package com.github.libretube.ui.yt.ssi

import kotlinx.serialization.Serializable

/**
 * What SSI knows about YouTube's json3 caption answers: the counts for Diagnose, the videos whose
 * json3 came back empty or blocked, and the pause after such an answer. Stored as JSON.
 */
@Serializable
data class YtSsiJson3State(
    /** json3 tried and it held the captions. */
    val ok: Int = 0,
    /** json3 tried and it came back empty, unreadable, failed or blocked. */
    val empty: Int = 0,
    /** The track's own format (TTML) fetched instead: after an empty json3 or while json3 is paused. */
    val fallback: Int = 0,
    /** json3 left out (paused, or this video's json3 was empty before). */
    val skipped: Int = 0,
    /** Empty answers in a row (a good one resets it). */
    val streak: Int = 0,
    /** Videos json3 is still left out for, but only until [skipUntil]. */
    val skipLeft: Int = 0,
    val skipUntil: Long = 0,
    /** Video id → when its json3 was empty; at most [YtSsiJson3Gate.MAX_FAILED], newest kept. */
    val failed: Map<String, Long> = emptyMap(),
)

/**
 * YT (SSI-1 gap): whether a caption fetch asks for json3 first, pure. An empty or blocked json3
 * answer costs a request for nothing, so after one json3 is left out for the next
 * [SKIP_VIDEOS] videos (doubled for each further empty answer in a row, at most
 * [MAX_SKIP_VIDEOS]), at most for [SKIP_MS]; then the next video tries json3 again. A video whose
 * json3 was empty goes straight to the fallback for [FAILED_KEEP_MS].
 */
object YtSsiJson3Gate {
    const val SKIP_VIDEOS = 5
    const val MAX_SKIP_VIDEOS = 40
    const val SKIP_MS = 6L * 60 * 60 * 1000
    const val FAILED_KEEP_MS = 14L * 24 * 60 * 60 * 1000
    const val MAX_FAILED = 200

    /** Whether json3 is tried for [videoId] at [now], and the state after the decision. */
    fun decide(state: YtSsiJson3State, videoId: String, now: Long): Pair<Boolean, YtSsiJson3State> {
        val failedAt = state.failed[videoId]
        if (failedAt != null && now - failedAt in 0 until FAILED_KEEP_MS) return false to state.copy(skipped = state.skipped + 1)
        if (state.skipLeft > 0 && now < state.skipUntil) {
            return false to state.copy(skipLeft = state.skipLeft - 1, skipped = state.skipped + 1)
        }
        return true to if (state.skipLeft != 0) state.copy(skipLeft = 0) else state
    }

    /** json3 held the captions of [videoId]. */
    fun ok(state: YtSsiJson3State, videoId: String): YtSsiJson3State =
        state.copy(ok = state.ok + 1, streak = 0, skipLeft = 0, skipUntil = 0, failed = state.failed - videoId)

    /** json3 of [videoId] came back empty or blocked at [now]: pause it. */
    fun empty(state: YtSsiJson3State, videoId: String, now: Long): YtSsiJson3State {
        val streak = (state.streak + 1).coerceAtMost(16)
        val skip = (SKIP_VIDEOS.toLong() shl (streak - 1).coerceAtMost(4)).coerceAtMost(MAX_SKIP_VIDEOS.toLong()).toInt()
        val failed = (state.failed.filterValues { now - it in 0 until FAILED_KEEP_MS } + (videoId to now))
            .entries.sortedBy { it.value }.takeLast(MAX_FAILED).associate { it.key to it.value }
        return state.copy(empty = state.empty + 1, streak = streak, skipLeft = skip, skipUntil = now + SKIP_MS, failed = failed)
    }

    /** The fallback format was fetched. */
    fun fallback(state: YtSsiJson3State): YtSsiJson3State = state.copy(fallback = state.fallback + 1)

    /** For Diagnose: "json3 ok 12 / empty 2 / fallback 3 (skipped 4, paused for 5 more videos)". */
    fun describe(state: YtSsiJson3State, now: Long): String = buildString {
        append("json3 ok ${state.ok} / empty ${state.empty} / fallback ${state.fallback}")
        val paused = state.skipLeft > 0 && now < state.skipUntil
        if (state.skipped > 0 || paused) {
            append(" (skipped ${state.skipped}")
            if (paused) append(", paused for ${state.skipLeft} more videos")
            append(")")
        }
    }
}
