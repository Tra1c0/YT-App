package com.github.libretube.enums

enum class NotificationId(val id: Int) {
    PLAYER_PLAYBACK(1),
    DOWNLOAD_IN_PROGRESS(2),
    ENQUEUE_PLAYLIST_DOWNLOAD(3),

    // YT: "tap to resume" when the player service could not go into the foreground
    PLAYER_RESUME(4)
}
