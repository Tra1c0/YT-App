package com.github.libretube.ui.yt.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtPipPlaybackTest {
    @Test fun leavingWhileBufferingKeepsTheRequestedPlaybackInPip() {
        assertTrue(YtPipPlayback.active(true, Player.STATE_BUFFERING))
        assertTrue(YtPipPlayback.active(true, Player.STATE_READY))
    }

    @Test fun aPausedVideoDoesNotEnterPipWhenItsBufferFinishes() {
        assertFalse(YtPipPlayback.active(false, Player.STATE_BUFFERING))
        assertFalse(YtPipPlayback.active(false, Player.STATE_READY))
    }

    @Test fun aFinishedOrClosedVideoDoesNotOpenPip() {
        assertFalse(YtPipPlayback.active(true, Player.STATE_ENDED))
        assertFalse(YtPipPlayback.active(true, Player.STATE_IDLE))
    }
}
