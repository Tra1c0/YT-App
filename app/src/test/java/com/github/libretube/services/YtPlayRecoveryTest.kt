package com.github.libretube.services

import androidx.media3.common.PlaybackException
import com.github.libretube.services.YtPlayRecovery.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtPlayRecoveryTest {
    @Test
    fun anUnexpectedRuntimeErrorIsPreparedAgain() {
        assertEquals(Action.PREPARE_AGAIN, YtPlayRecovery.decide(PlaybackException.ERROR_CODE_UNSPECIFIED, 0))
        assertEquals(Action.PREPARE_AGAIN, YtPlayRecovery.decide(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, 1))
        assertEquals(Action.PREPARE_AGAIN, YtPlayRecovery.decide(PlaybackException.ERROR_CODE_DECODING_FAILED, 2))
    }

    @Test
    fun sourceErrorsAndRepeatsGiveUp() {
        assertEquals(Action.GIVE_UP, YtPlayRecovery.decide(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 0))
        assertEquals(Action.GIVE_UP, YtPlayRecovery.decide(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, 0))
        assertEquals(
            Action.GIVE_UP,
            YtPlayRecovery.decide(PlaybackException.ERROR_CODE_UNSPECIFIED, YtPlayRecovery.MAX_ATTEMPTS)
        )
    }

    @Test
    fun eachAttemptWaitsLonger() {
        assertTrue(YtPlayRecovery.delayMs(1) > YtPlayRecovery.delayMs(0))
    }
}
