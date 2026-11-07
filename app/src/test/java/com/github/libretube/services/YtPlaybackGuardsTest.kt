package com.github.libretube.services

import androidx.media3.common.PlaybackException
import com.github.libretube.services.YtLiveRecovery.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtForegroundHoldTest {
    @Test
    fun skipsOnlyWhileHolding() {
        val hold = YtForegroundHold()
        assertFalse(hold.skip())
        hold.begin()
        assertTrue(hold.skip())
        // a skipped update has to be caught up once the hold ends
        assertTrue(hold.release())
        assertFalse(hold.holding)
        assertFalse(hold.skip())
    }

    @Test
    fun releaseWithoutSkipNeedsNoUpdate() {
        val hold = YtForegroundHold()
        hold.begin()
        assertFalse(hold.release())
        assertFalse(hold.holding)
    }

    @Test
    fun aLateReleaseOfAnOlderNavigationKeepsTheNewerHold() {
        // next, next quickly: the first load reports back after the second one began
        val hold = YtForegroundHold()
        val first = hold.begin()
        val second = hold.begin()
        assertTrue(hold.skip())
        assertFalse(hold.release(first))
        assertTrue(hold.holding)
        assertTrue(hold.release(second))
        assertFalse(hold.holding)
    }

    @Test
    fun aNewHoldStartsWithoutSkippedUpdates() {
        val hold = YtForegroundHold()
        hold.begin()
        hold.skip()
        hold.begin()
        assertTrue(hold.holding)
        assertFalse(hold.release())
    }
}

class YtLiveRecoveryTest {
    @Test
    fun videoOnDemandIsNotRetried() {
        assertEquals(Action.GIVE_UP, YtLiveRecovery.decide(false, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, 0))
        assertEquals(Action.GIVE_UP, YtLiveRecovery.decide(false, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 0))
    }

    @Test
    fun behindTheLiveWindowGoesBackToTheLiveEdge() {
        assertEquals(
            Action.SEEK_TO_LIVE_EDGE,
            YtLiveRecovery.decide(true, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, 0)
        )
    }

    @Test
    fun sourceErrorsOfALiveStreamReload() {
        listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        ).forEach { code ->
            assertEquals(code.toString(), Action.RELOAD, YtLiveRecovery.decide(true, code, 1))
        }
    }

    @Test
    fun hopelessErrorsAndTooManyAttemptsGiveUp() {
        assertEquals(Action.GIVE_UP, YtLiveRecovery.decide(true, PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, 0))
        assertEquals(Action.GIVE_UP, YtLiveRecovery.decide(true, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, 0))
        assertEquals(
            Action.GIVE_UP,
            YtLiveRecovery.decide(true, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, YtLiveRecovery.MAX_ATTEMPTS)
        )
    }

    @Test
    fun theWaitGrows() {
        assertTrue(YtLiveRecovery.delayMs(1) > YtLiveRecovery.delayMs(0))
    }
}
