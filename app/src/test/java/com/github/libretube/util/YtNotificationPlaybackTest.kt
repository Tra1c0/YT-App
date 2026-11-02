package com.github.libretube.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNotificationPlaybackTest {
    @Test fun dismissalOfTheCurrentNotificationStillStopsPlayback() {
        assertTrue(YtNotificationPlayback.matches("video-a", 7, "video-a", 7))
    }

    @Test fun aDelayedDismissalCannotStopTheSameVideoAfterItWasReopened() {
        assertFalse(YtNotificationPlayback.matches("video-a", 7, "video-a", 8))
    }

    @Test fun aPreviousVideosNotificationCannotStopTheReplacement() {
        assertFalse(YtNotificationPlayback.matches("video-a", 7, "video-b", 7))
    }

    @Test fun aNotificationWithoutMediaIdentityCannotStopANewSession() {
        assertFalse(YtNotificationPlayback.matches(null, null, "video-a", 7))
    }
}
