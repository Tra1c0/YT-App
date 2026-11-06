package com.github.libretube.services

/**
 * YT: whether the offline player can play a download, kept apart from the service (which needs
 * Android) so it can be tested on the JVM.
 */
object YtOfflineAudio {
    /**
     * True if a download with these files can play. In audio-only mode (the audio player, and
     * every background start) a download without an audio file of its own plays from its video
     * file, which carries the sound; the video track stays off there. Upstream stopped the
     * service instead, so a queue in audio mode ended at the first such video: the one before
     * played (it was opened as a video and only switched to audio), the next one stopped
     * everything.
     *
     * @param hasAny whether the download has any playable file at all (video, audio or subtitles)
     */
    fun playable(hasVideo: Boolean, hasAudio: Boolean, hasAny: Boolean, audioOnly: Boolean): Boolean =
        if (audioOnly) hasAudio || hasVideo else hasAny

    /** True if audio-only mode takes the sound from the video file (no audio file there). */
    fun soundFromVideo(hasVideo: Boolean, hasAudio: Boolean, audioOnly: Boolean): Boolean =
        audioOnly && !hasAudio && hasVideo
}
