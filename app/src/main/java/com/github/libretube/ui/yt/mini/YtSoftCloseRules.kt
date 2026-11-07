package com.github.libretube.ui.yt.mini

/**
 * YT: the pure rules of the soft close (YtSoftClose): whether a mini player that is swiped away
 * or closed keeps its player paused in the service for a minute, whether a long press on "Abos"
 * brings that same player back or restarts the video (YtMiniRestore), when the kept player is
 * let go, and which memory warnings let it go early. No Android types, so it is unit tested.
 */
object YtSoftCloseRules {
    /** How long a closed player stays paused in its service ("nur so 1 Minute"). */
    const val HOLD_MS = 60_000L

    /**
     * The service ends a soft close by itself this long after [HOLD_MS] if nobody else did (the
     * page side lost its connection, its timer came late): it never stays paused forever.
     */
    const val BACKSTOP_MS = 5_000L

    /** How long a resumed player waits for its page to connect before it is let go after all. */
    const val ATTACH_MS = 10_000L

    // the player states of media3 (Player.STATE_*), as plain numbers
    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4

    /**
     * Whether a player closed now is kept: only a connected online video that is loaded and not
     * at its end (an ended or failed one has nothing to come back to), and no live stream (its
     * buffer is stale after a minute; the restart catches up with the live edge).
     */
    fun keeps(
        connected: Boolean,
        offline: Boolean,
        live: Boolean,
        hasMedia: Boolean,
        playbackState: Int,
    ): Boolean {
        if (!connected || offline || live || !hasMedia) return false
        return playbackState == STATE_READY || playbackState == STATE_BUFFERING
    }

    enum class Route {
        /** The kept player comes back as it is: no new extraction, no buffering. */
        RESUME,

        /** The video starts again from the saved position (the fallback up to ten minutes). */
        RESTART,
    }

    /**
     * How a long press brings back the closed video [savedVideo].
     *
     * @param heldVideo the video of the kept player, null if none is kept (released, expired,
     *   or its service went away)
     * @param heldAt when it was closed (monotonic clock, ms)
     */
    fun route(heldVideo: String?, heldAt: Long, savedVideo: String?, now: Long, holdMs: Long = HOLD_MS): Route {
        if (heldVideo == null || savedVideo == null || heldVideo != savedVideo) return Route.RESTART
        return if (expired(heldAt, now, holdMs)) Route.RESTART else Route.RESUME
    }

    /** Whether the kept player is past its minute (a negative age: not the clock it was taken with). */
    fun expired(heldAt: Long, now: Long, holdMs: Long = HOLD_MS): Boolean {
        val age = now - heldAt
        return age < 0 || age > holdMs
    }

    /** How long the kept player has left, 0 when it is over. */
    fun remainingMs(heldAt: Long, now: Long, holdMs: Long = HOLD_MS): Long =
        if (expired(heldAt, now, holdMs)) 0L else holdMs - (now - heldAt)

    /**
     * A player page connected to the service while a player is kept: it plays only if it is the
     * page the long press asked for ([resumeFor], within [ATTACH_MS] of [resumeAt]); any other
     * page (one created again while the close was still running) ends the soft close paused.
     */
    fun playsOnAttach(resumeFor: String?, resumeAt: Long, pageVideo: String?, now: Long): Boolean {
        if (resumeFor == null || pageVideo != resumeFor) return false
        return now - resumeAt in 0..ATTACH_MS
    }

    // ComponentCallbacks2 levels: RUNNING_LOW, RUNNING_CRITICAL, MODERATE, COMPLETE
    private const val TRIM_RUNNING_LOW = 10
    private const val TRIM_RUNNING_CRITICAL = 15
    private const val TRIM_MODERATE = 60
    private const val TRIM_COMPLETE = 80

    /**
     * Whether a memory warning lets the kept player go before its minute is over. The warnings
     * every app gets when it goes to the background (UI_HIDDEN, BACKGROUND) do not: leaving the
     * app for a moment must not cost the quick way back.
     */
    fun releasesOnTrim(level: Int): Boolean =
        level == TRIM_RUNNING_LOW || level == TRIM_RUNNING_CRITICAL || level >= TRIM_MODERATE
}
