package com.github.libretube.helpers

import com.github.libretube.api.obj.Streams
import com.github.libretube.ui.yt.feed.YtFeed
import com.github.libretube.util.YtMusicRules

/** YT: the music settings of the player and the music check of a loaded stream. */
object YtMusicPlayback {
    const val AUDIO_IN_BACKGROUND = "yt_music_audio_background"
    const val FROM_START = "yt_music_from_start"

    val audioInBackground: Boolean
        get() = PreferenceHelper.getBoolean(AUDIO_IN_BACKGROUND, true)

    val fromStart: Boolean
        get() = PreferenceHelper.getBoolean(FROM_START, true)

    /** Whether [streams] is music; the channels marked as music in the feed sheet count too. */
    fun isMusic(streams: Streams?): Boolean {
        streams ?: return false
        val marked = runCatching { YtFeed.profile.value.personal.musicChannels }.getOrDefault(emptySet())
        return YtMusicRules.isMusic(
            channelId = YtChannelPause.channelId(streams.uploaderUrl),
            channelName = streams.uploader,
            category = streams.category,
            title = streams.title,
            marked = marked,
        )
    }
}
