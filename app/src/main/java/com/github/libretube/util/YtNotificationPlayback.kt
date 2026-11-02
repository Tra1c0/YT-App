package com.github.libretube.util

/** A replaced notification must not dismiss the player, even within the same media item. */
internal class YtNotificationPlayback {
    private var revision = 0L

    fun nextNotification(): Long = ++revision

    fun canDismiss(capturedRevision: Long, capturedVideo: String?, capturedTrack: Int?, currentVideo: String?, currentTrack: Int?): Boolean =
        capturedRevision == revision && matches(capturedVideo, capturedTrack, currentVideo, currentTrack)

    companion object {
        fun matches(capturedVideo: String?, capturedTrack: Int?, currentVideo: String?, currentTrack: Int?): Boolean =
            capturedVideo != null && capturedTrack != null && capturedVideo == currentVideo && capturedTrack == currentTrack
    }
}
