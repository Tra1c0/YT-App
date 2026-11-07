package com.github.libretube.services

import androidx.media3.common.PlaybackException

/**
 * YT: keeps the player service in the foreground while a queue moves on to its next item.
 *
 * Why: navigateVideo empties the player (clearMediaItems) and loads the next video, which takes
 * from a few milliseconds (a download) to a second or more (an online video). media3's
 * MediaNotificationManager sees the empty timeline, removes the notification and calls
 * stopForeground. When the next item then plays, media3 has to start the service in the
 * foreground again with startForegroundService, and Android 12+ forbids that while the app is
 * in the background: ForegroundServiceStartNotAllowedException, which crashed the app.
 *
 * While the hold is on, the service skips media3's notification updates, so the notification and
 * the foreground state of the item before stay until the next item is in the player (media3 then
 * only calls startForeground on a service that is in the foreground already, which is allowed).
 * The hold ends a short grace time after the next item arrived (the notification controller sees
 * the player's state a little later, and its stale empty timeline must not remove the
 * notification either), after a failed load, or at the latest after [MAX_MS].
 *
 * Plain state, no Android, main thread only; the service does the timing.
 */
class YtForegroundHold {
    /** Counts the navigations, so a late "done" of an older one does not end a newer hold. */
    var seq = 0
        private set

    var holding = false
        private set

    /** Whether an update was skipped during this hold (then the release has to catch up). */
    private var skipped = false

    /** A navigation starts: hold. Returns its number for [release]. */
    fun begin(): Int {
        seq++
        holding = true
        skipped = false
        return seq
    }

    /** True if a notification update has to be skipped now. */
    fun skip(): Boolean {
        if (!holding) return false
        skipped = true
        return true
    }

    /**
     * Ends the hold of navigation [forSeq] (null: whatever hold there is). True if updates were
     * skipped, so the caller has to trigger one now.
     */
    fun release(forSeq: Int? = null): Boolean {
        if (!holding || (forSeq != null && forSeq != seq)) return false
        holding = false
        val catchUp = skipped
        skipped = false
        return catchUp
    }

    companion object {
        /** After the next item is in the player: the time for media3's controller to see it. */
        const val GRACE_MS = 1_000L

        /** The longest hold, in case a load never reports back. */
        const val MAX_MS = 30_000L
    }
}

/**
 * YT: what the online service does when a live stream fails.
 *
 * Why: any fatal player error puts the player into STATE_IDLE, and OnlinePlayerService ends
 * itself on STATE_IDLE. For a live stream that turned a recoverable error into "the stream does
 * not start" plus the toast "Source error" (the text of every ExoPlaybackException of
 * TYPE_SOURCE, which says nothing about the cause). Errors a live stream recovers from:
 * - ERROR_CODE_BEHIND_LIVE_WINDOW: the position fell out of the live window (a slow start, a
 *   stall, a seek back): media3's documented fix is seekToDefaultPosition and prepare.
 * - IO errors (an expired or rejected segment url, HTTP 403/404, a timeout) and malformed
 *   playlists: the stream's urls are fetched anew and the stream starts at its live edge.
 * Video on demand keeps the old behaviour, and so does a live stream after [MAX_ATTEMPTS]
 * attempts in a row without playing in between.
 */
object YtLiveRecovery {
    enum class Action { SEEK_TO_LIVE_EDGE, RELOAD, GIVE_UP }

    const val MAX_ATTEMPTS = 3

    fun decide(isLive: Boolean, errorCode: Int, attempts: Int): Action {
        if (!isLive || attempts >= MAX_ATTEMPTS) return Action.GIVE_UP
        return when (errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> Action.SEEK_TO_LIVE_EDGE
            // a cleartext url or a missing permission does not get better by asking again
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Action.GIVE_UP

            in IO_ERRORS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_UNSPECIFIED -> Action.RELOAD

            else -> Action.GIVE_UP
        }
    }

    /** The wait before the next attempt: a little longer each time. */
    fun delayMs(attempts: Int): Long = 500L + 1_000L * attempts

    // the IO error codes of PlaybackException (2000 to 2999)
    private val IO_ERRORS = 2000..2999
}
