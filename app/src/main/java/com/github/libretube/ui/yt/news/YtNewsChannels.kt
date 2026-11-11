package com.github.libretube.ui.yt.news

import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtChannelPause
import com.github.libretube.ui.yt.YtTestHooks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * YT: the channels whose newest videos the News overlay shows ("Nachrichtenkanal"). The user
 * marks and unmarks them in the quick sheet of a card and in the Feed-Regler's channel list; a
 * channel of a video that played with the category "News & Politics" is marked by itself unless
 * the user unmarked it before (the stream info is loaded for playing anyway, no extra call).
 * Stored on the device as one JSON object under [KEY] (YtNewsChannelList).
 */
object YtNewsChannels {
    const val KEY = "yt_news_channels"

    private val flow by lazy { MutableStateFlow(load()) }

    val list: StateFlow<YtNewsChannelList> get() = flow

    fun isNews(video: StreamItem): Boolean =
        YtNewsChannelRules.isNews(flow.value, YtChannelPause.channelId(video.uploaderUrl), video.uploaderName)

    fun isNews(channelId: String?, name: String?): Boolean = YtNewsChannelRules.isNews(flow.value, channelId, name)

    /** Marks or unmarks the channel of [video]. @return true if it is a news channel now */
    fun toggle(video: StreamItem): Boolean = toggle(YtChannelPause.channelId(video.uploaderUrl), video.uploaderName)

    fun toggle(channelId: String?, name: String?): Boolean {
        val next = YtNewsChannelRules.toggle(flow.value, channelId, name)
        set(next)
        return YtNewsChannelRules.isNews(next, channelId, name)
            .also { on -> YtTestHooks.log("YtNews") { "channel id=$channelId news=$on" } }
    }

    /** A video of a channel was loaded for playing: its category may mark the channel. */
    fun noteWatched(streams: Streams) {
        val before = flow.value
        val next = YtNewsChannelRules.detect(before, YtChannelPause.channelId(streams.uploaderUrl), streams.uploader, streams.category)
        if (next !== before) {
            set(next)
            YtTestHooks.log("YtNews") { "channel detected name=${streams.uploader}" }
        }
    }

    private fun set(list: YtNewsChannelList) {
        flow.value = list
        runCatching {
            if (list.channels.isEmpty() && list.refused.isEmpty()) {
                PreferenceHelper.remove(KEY)
            } else {
                PreferenceHelper.putString(KEY, JsonHelper.json.encodeToString(YtNewsChannelList.serializer(), list))
            }
        }
    }

    private fun load(): YtNewsChannelList = runCatching {
        PreferenceHelper.getString(KEY, "").takeIf { it.isNotBlank() }
            ?.let { JsonHelper.json.decodeFromString(YtNewsChannelList.serializer(), it) }
    }.getOrNull() ?: YtNewsChannelList()
}
