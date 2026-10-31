package com.github.libretube.ui.yt.player

/**
 * YT: the rewind memory ("Zurückspulen mit Gedächtnis"). After a jump back of a few seconds the
 * player offers the way back for a moment: a chip "↩ 12:34" that returns exactly to where the
 * jump started. This is the rule of when it offers what, as a pure reducer; the chip, the
 * clock and the player are outside (YtRewindChip). Times are in ms, [YtRewindEvent.nowMs] on
 * one monotonic clock.
 */
data class YtRewindState(
    /** The video the return point belongs to. */
    val videoId: String? = null,
    /** Where the chip returns to, or -1 without a return point. */
    val returnMs: Long = -1L,
    /** From when the chip shows (a jump settles first, a series of jumps waits for its end). */
    val showAtMs: Long = 0L,
    /** When the chip goes again. */
    val expireAtMs: Long = 0L,
    /** A scrub of the time bar runs: its seeks count as one, when it ends. */
    val scrubbing: Boolean = false,
    /** Seeks before this are the tail of a scrub or of the chip's own seek. */
    val quietUntilMs: Long = 0L,
) {
    val hasReturn: Boolean get() = returnMs >= 0L

    /** Whether the chip shows at [nowMs]. */
    fun visible(nowMs: Long): Boolean =
        hasReturn && !scrubbing && nowMs >= showAtMs && nowMs < expireAtMs

    /** How much of the chip's time is left at [nowMs], 1 when it appears, 0 when it goes. */
    fun remaining(nowMs: Long): Float {
        if (!hasReturn) return 0f
        val total = (expireAtMs - showAtMs).coerceAtLeast(1L)
        return ((expireAtMs - nowMs).toFloat() / total).coerceIn(0f, 1f)
    }
}

/** Why the position jumped, as far as the rewind memory cares. */
enum class YtRewindCause {
    /** A plain jump: a double tap, a chapter, a link in the description, the notification. */
    PLAIN,

    /** Part of a scrub of the time bar (it counts once, at its end). */
    SCRUB,

    /** The chip's own way back. */
    CHIP,

    /** A loop going back to its start. */
    LOOP,
}

sealed interface YtRewindEvent {
    val nowMs: Long

    /** The position jumped from [fromMs] to [toMs]. */
    data class Seek(
        val fromMs: Long,
        val toMs: Long,
        val durationMs: Long,
        val videoId: String?,
        val live: Boolean,
        val cause: YtRewindCause,
        override val nowMs: Long,
    ) : YtRewindEvent

    /** A scrub of the time bar begins. */
    data class ScrubStart(override val nowMs: Long) : YtRewindEvent

    /**
     * The scrub ended: it went from [fromMs] (where the video was before it) to [toMs], or
     * changed nothing ([cancelled]: let go below the bar or back at the start).
     */
    data class ScrubStop(
        val fromMs: Long,
        val toMs: Long,
        val cancelled: Boolean,
        val durationMs: Long,
        val videoId: String?,
        val live: Boolean,
        override val nowMs: Long,
    ) : YtRewindEvent

    /** The time passes; the video is at [positionMs]. */
    data class Tick(val positionMs: Long, val videoId: String?, override val nowMs: Long) : YtRewindEvent

    /** The chip was tapped (the caller seeks to the return point it read before). */
    data class Tap(override val nowMs: Long) : YtRewindEvent

    /** Another video plays (or the first one). */
    data class Video(val videoId: String?, override val nowMs: Long) : YtRewindEvent
}

object YtRewindMemory {
    /** A jump back shorter than this is not worth a way back. */
    const val MIN_JUMP_MS = 5_000L

    /** How long the chip shows. */
    const val SHOW_MS = 5_000L

    /** A plain jump back settles this long before the chip shows (a double tap animates, a series of taps goes on). */
    const val SETTLE_MS = 700L

    /** After a scrub the chip shows sooner: the scrub was one decided move. */
    const val SETTLE_SCRUB_MS = 350L

    /** Seeks this long after a scrub or the chip's tap are their own late echoes. */
    const val QUIET_MS = 1_000L

    /** A jump from the last second is a replay from the end (or repeat one), not a jump back. */
    const val END_MS = 1_000L

    /** A jump forward to this close before the return point counts as having gone back. */
    const val NEAR_RETURN_MS = 1_000L

    /** Playback passing the return point by itself ends the offer (a little early, as the chip fades). */
    const val PASS_MS = 250L

    /**
     * The next state after [event]. [showMs] is how long the chip shows (longer with a screen
     * reader, see AccessibilityManager.getRecommendedTimeoutMillis).
     */
    fun reduce(state: YtRewindState, event: YtRewindEvent, showMs: Long = SHOW_MS): YtRewindState =
        when (event) {
            is YtRewindEvent.Seek -> onSeek(state, event, showMs)
            is YtRewindEvent.ScrubStart -> state.copy(scrubbing = true)
            is YtRewindEvent.ScrubStop -> onScrubStop(state, event, showMs)
            is YtRewindEvent.Tick -> onTick(state, event)
            is YtRewindEvent.Tap -> YtRewindState(videoId = state.videoId, quietUntilMs = event.nowMs + QUIET_MS)
            is YtRewindEvent.Video ->
                if (event.videoId == state.videoId) state else YtRewindState(videoId = event.videoId)
        }

    private fun onSeek(state: YtRewindState, e: YtRewindEvent.Seek, showMs: Long): YtRewindState {
        // the scrub, the chip and a loop make their own seeks; a scrub counts at its end
        if (e.cause != YtRewindCause.PLAIN) return state
        if (state.scrubbing || e.nowMs < state.quietUntilMs) return state
        return jump(state, e.fromMs, e.toMs, e.durationMs, e.videoId, e.live, e.nowMs, SETTLE_MS, showMs)
    }

    private fun onScrubStop(state: YtRewindState, e: YtRewindEvent.ScrubStop, showMs: Long): YtRewindState {
        val after = state.copy(scrubbing = false, quietUntilMs = e.nowMs + QUIET_MS)
        if (e.cancelled) {
            // nothing moved: an offer from before the scrub comes back, for a full turn
            if (!after.hasReturn) return after
            val showAt = e.nowMs + SETTLE_SCRUB_MS
            return after.copy(showAtMs = showAt, expireAtMs = showAt + showMs)
        }
        return jump(after, e.fromMs, e.toMs, e.durationMs, e.videoId, e.live, e.nowMs, SETTLE_SCRUB_MS, showMs)
    }

    private fun jump(
        state: YtRewindState,
        fromMs: Long,
        toMs: Long,
        durationMs: Long,
        videoId: String?,
        live: Boolean,
        nowMs: Long,
        settleMs: Long,
        showMs: Long,
    ): YtRewindState {
        // another video: what was remembered belongs to the old one
        val base = if (videoId != null && state.videoId != null && videoId != state.videoId) {
            YtRewindState(videoId = videoId, quietUntilMs = state.quietUntilMs)
        } else {
            state.copy(videoId = videoId ?: state.videoId)
        }
        if (live) return YtRewindState(videoId = base.videoId, quietUntilMs = base.quietUntilMs)
        if (fromMs < 0L || toMs < 0L) return base
        // a replay from the end, or repeat one
        if (durationMs > 0L && fromMs >= durationMs - END_MS) return base

        // a series: still offered (or about to be)
        val series = base.hasReturn && nowMs < base.expireAtMs
        val back = fromMs - toMs
        return when {
            back >= MIN_JUMP_MS -> {
                val showAt = nowMs + settleMs
                base.copy(
                    // the point before the first jump of the series
                    returnMs = if (series) base.returnMs else fromMs,
                    showAtMs = showAt,
                    expireAtMs = showAt + showMs,
                )
            }

            toMs > fromMs -> {
                // forward to (or past) the return point: the user went back by themselves
                if (base.hasReturn && toMs >= base.returnMs - NEAR_RETURN_MS) {
                    YtRewindState(videoId = base.videoId, quietUntilMs = base.quietUntilMs)
                } else {
                    base
                }
            }

            // a small step back within a series: the series goes on, the chip waits for its end
            series && back > 0L -> {
                val showAt = nowMs + settleMs
                base.copy(showAtMs = showAt, expireAtMs = showAt + showMs)
            }

            else -> base
        }
    }

    private fun onTick(state: YtRewindState, e: YtRewindEvent.Tick): YtRewindState {
        if (!state.hasReturn) return state
        val cleared = YtRewindState(videoId = state.videoId, scrubbing = state.scrubbing, quietUntilMs = state.quietUntilMs)
        if (e.videoId != null && state.videoId != null && e.videoId != state.videoId) {
            return cleared.copy(videoId = e.videoId)
        }
        // while scrubbing the offer waits; it is renewed (or replaced) when the scrub ends
        if (state.scrubbing) return state
        if (e.nowMs >= state.expireAtMs) return cleared
        // playback reached the return point by itself
        if (e.nowMs >= state.showAtMs && e.positionMs >= state.returnMs - PASS_MS) return cleared
        return state
    }
}
