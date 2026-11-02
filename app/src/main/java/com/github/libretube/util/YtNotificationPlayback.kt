package com.github.libretube.util

/** A dismissal belongs to the media item that created the notification. */
internal object YtNotificationPlayback {
    fun matches(capturedVideo: String?, capturedTrack: Int?, currentVideo: String?, currentTrack: Int?): Boolean =
        capturedVideo != null && capturedTrack != null && capturedVideo == currentVideo && capturedTrack == currentTrack
}
