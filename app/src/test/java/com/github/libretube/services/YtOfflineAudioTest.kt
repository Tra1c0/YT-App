package com.github.libretube.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtOfflineAudioTest {
    @Test
    fun audioOnlyPlaysAVideoOnlyDownload() {
        // the case that stopped the service at the next video of an audio-mode queue
        assertTrue(YtOfflineAudio.playable(hasVideo = true, hasAudio = false, hasAny = true, audioOnly = true))
        assertTrue(YtOfflineAudio.soundFromVideo(hasVideo = true, hasAudio = false, audioOnly = true))
    }

    @Test
    fun audioOnlyPrefersTheAudioFile() {
        assertTrue(YtOfflineAudio.playable(hasVideo = true, hasAudio = true, hasAny = true, audioOnly = true))
        assertFalse(YtOfflineAudio.soundFromVideo(hasVideo = true, hasAudio = true, audioOnly = true))
        assertTrue(YtOfflineAudio.playable(hasVideo = false, hasAudio = true, hasAny = true, audioOnly = true))
    }

    @Test
    fun audioOnlyWithoutSoundDoesNotPlay() {
        // only subtitles: nothing to hear
        assertFalse(YtOfflineAudio.playable(hasVideo = false, hasAudio = false, hasAny = true, audioOnly = true))
        assertFalse(YtOfflineAudio.playable(hasVideo = false, hasAudio = false, hasAny = false, audioOnly = true))
    }

    @Test
    fun videoModeAsBefore() {
        assertTrue(YtOfflineAudio.playable(hasVideo = true, hasAudio = false, hasAny = true, audioOnly = false))
        assertTrue(YtOfflineAudio.playable(hasVideo = false, hasAudio = true, hasAny = true, audioOnly = false))
        assertTrue(YtOfflineAudio.playable(hasVideo = false, hasAudio = false, hasAny = true, audioOnly = false))
        assertFalse(YtOfflineAudio.playable(hasVideo = false, hasAudio = false, hasAny = false, audioOnly = false))
        assertFalse(YtOfflineAudio.soundFromVideo(hasVideo = true, hasAudio = false, audioOnly = false))
    }
}
