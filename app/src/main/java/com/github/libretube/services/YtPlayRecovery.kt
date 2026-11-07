package com.github.libretube.services

import androidx.media3.common.PlaybackException

/**
 * YT: what the service does when a video (not a live stream, see [YtLiveRecovery]) fails with an
 * error the player can get over by itself.
 *
 * Why: playing quickly with speed and pitch could end in "Unexpected runtime error" (a
 * RuntimeException on the playback thread, e.g. from the audio processors or a decoder that
 * refused a new operating rate). The player then sat in STATE_IDLE: the online service ended
 * itself on IDLE, while the page had already called play() and its clock ran on over a still
 * picture until the video was closed and opened again. A fresh prepare() at the same position
 * rebuilds the renderers and the audio sink and plays on. Errors of the source (network, format)
 * keep their old handling, and so does a video after [MAX_ATTEMPTS] tries in a row without
 * playing in between.
 */
object YtPlayRecovery {
    enum class Action { PREPARE_AGAIN, GIVE_UP }

    const val MAX_ATTEMPTS = 3

    fun decide(errorCode: Int, attempts: Int): Action {
        if (attempts >= MAX_ATTEMPTS) return Action.GIVE_UP
        return if (errorCode in RECOVERABLE) Action.PREPARE_AGAIN else Action.GIVE_UP
    }

    /** The wait before the next attempt: short, the player only has to set itself up anew. */
    fun delayMs(attempts: Int): Long = 300L + 700L * attempts

    private val RECOVERABLE = setOf(
        PlaybackException.ERROR_CODE_UNSPECIFIED,
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    )
}
