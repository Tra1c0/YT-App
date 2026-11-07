package com.github.libretube.util

/**
 * YT: which videos count as music, and what that changes in playback. Pure, so the feed, the
 * player page and the playback service decide alike (tested in YtMusicRulesTest).
 */
object YtMusicRules {

    // YouTube's category name; NewPipe may hand it over in the content language
    private val MUSIC_CATEGORIES = setOf("music", "musik")

    // titles that only music uploads carry; kept narrow so talk videos are not caught
    private val TITLE_HINTS = listOf("official music video", "official audio", "official lyric video", "lyric video")

    /** A music channel by its name alone: "… - Topic" (YouTube's auto channels), VEVO, "… Music". */
    fun channelLooksMusic(name: String?): Boolean {
        val n = name.orEmpty().trim()
        if (n.isEmpty()) return false
        return n.endsWith(" - Topic") || n.contains("VEVO") || n.endsWith("Music", ignoreCase = true)
    }

    /**
     * Whether a video is music: a channel the user marked as music (by id or name, as the feed
     * keys them), a music channel by name, YouTube's category "Music", or a music-only title.
     */
    fun isMusic(
        channelId: String?,
        channelName: String?,
        category: String?,
        title: String?,
        marked: Set<String>,
    ): Boolean {
        if (channelId != null && channelId in marked) return true
        if (!channelName.isNullOrBlank() && channelName in marked) return true
        if (channelLooksMusic(channelName)) return true
        if (category?.trim()?.lowercase() in MUSIC_CATEGORIES) return true
        val t = title.orEmpty().lowercase()
        return TITLE_HINTS.any { t.contains(it) }
    }

    /**
     * Whether the saved watch position is restored. A link's own time (t=) is handled before this
     * and always wins; music starts at 0:00 when [musicFromStart] is on.
     */
    fun restoreSavedPosition(watchPositionsEnabled: Boolean, isMusic: Boolean, musicFromStart: Boolean): Boolean =
        watchPositionsEnabled && !(isMusic && musicFromStart)

    /**
     * Whether the video track rests while the app is in the background. Not while the picture is
     * still visible (picture-in-picture, YT's floating window) and not on a turn of the screen.
     */
    fun audioInBackground(
        enabled: Boolean,
        isMusic: Boolean,
        pictureVisible: Boolean,
        changingConfiguration: Boolean,
    ): Boolean = enabled && isMusic && !pictureVisible && !changingConfiguration
}
