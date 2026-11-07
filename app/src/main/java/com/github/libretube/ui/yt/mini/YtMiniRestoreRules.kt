package com.github.libretube.ui.yt.mini

/**
 * YT: the pure rules behind bringing back a mini player that was swiped away (or closed):
 * whether a long press on the "Abos" tab restores it, how long to wait for the old player
 * service to finish, and where the video starts again. No Android types, so it is unit tested.
 */
object YtMiniRestoreRules {
    /** How long a closed player can be brought back. */
    const val KEEP_MS = 10 * 60 * 1000L

    /**
     * How long after the close the new player waits at least. The closed page stops its service,
     * and that service clears the playing queue when it is destroyed; a player started before
     * that would lose the queue just put back (and could attach to the dying service).
     */
    const val SETTLE_MS = 1500L

    /** A video that was this close to its end starts from the beginning again. */
    const val END_MARGIN_MS = 3000L

    enum class Verdict {
        /** Bring the player back. */
        RESTORE,

        /** Nothing was closed (or it was brought back already). */
        NOTHING_SAVED,

        /** Closed too long ago; the saved session is dropped. */
        EXPIRED,

        /** A player is on screen; the long press does nothing. */
        PLAYER_SHOWING,
    }

    /**
     * @param closedAt when the player was closed (monotonic clock, ms), null if nothing is saved
     * @param now the same clock now
     * @param playerShowing whether a player page (video or audio) is in the activity
     */
    fun decide(closedAt: Long?, now: Long, playerShowing: Boolean, keepMs: Long = KEEP_MS): Verdict {
        if (closedAt == null) return Verdict.NOTHING_SAVED
        val age = now - closedAt
        // a negative age means the clock is not the one the close was taken with
        if (age < 0 || age > keepMs) return Verdict.EXPIRED
        if (playerShowing) return Verdict.PLAYER_SHOWING
        return Verdict.RESTORE
    }

    /** How long to wait before starting the player again, 0 when the close was long enough ago. */
    fun startDelay(closedAt: Long, now: Long, settleMs: Long = SETTLE_MS): Long =
        (settleMs - (now - closedAt)).coerceIn(0L, settleMs)

    /**
     * The second the video starts again at: where it was, or the beginning if it had (nearly)
     * ended; [durationMs] ≤ 0 means unknown.
     */
    fun startSeconds(positionMs: Long, durationMs: Long): Long {
        if (positionMs <= 0) return 0
        if (durationMs > 0 && positionMs >= durationMs - END_MARGIN_MS) return 0
        return positionMs / 1000
    }
}
