package com.github.libretube.helpers

import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.feed.YtPauseRules
import com.github.libretube.ui.yt.feed.YtPausedChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * YT: channels paused for a week ("Kanal 1 Woche pausieren"): their videos stay off the home
 * screens and the subscription feed until the pause runs out, nothing is blocked for good, and
 * search and the channel page still show them. Stored on the device only, as one JSON list
 * (key [KEY]) that drops what ran out whenever it is read or written. A channel is known by the
 * id in its URL, or by its name where a video has no channel URL.
 */
object YtChannelPause {
    const val KEY = "yt_paused_channels"

    private val flow by lazy { MutableStateFlow(load()) }

    /** The pauses; screens collect it to filter (a pause that ran out meanwhile counts as gone). */
    val paused: StateFlow<List<YtPausedChannel>> get() = flow

    /** The id of a channel from its URL (or a bare id), null without one. */
    fun channelId(uploaderUrl: String?): String? = uploaderUrl?.takeIf { it.isNotBlank() }?.toID()

    /** The pauses still running. */
    fun active(): List<YtPausedChannel> = YtPauseRules.prune(flow.value, System.currentTimeMillis())

    fun find(video: StreamItem): YtPausedChannel? = find(channelId(video.uploaderUrl), video.uploaderName)

    fun find(channelId: String?, name: String?): YtPausedChannel? =
        YtPauseRules.find(flow.value, channelId, name, System.currentTimeMillis())

    /** Pauses the channel of [video] for a week. @return the pause, null if the video names no channel */
    fun pause(video: StreamItem): YtPausedChannel? =
        pause(channelId(video.uploaderUrl), video.uploaderName, video.uploaderAvatar)

    fun pause(channelId: String?, name: String?, avatar: String?): YtPausedChannel? {
        val now = System.currentTimeMillis()
        val next = YtPauseRules.pause(flow.value, channelId, name, avatar, now)
        set(next)
        return YtPauseRules.find(next, channelId, name, now)
            .also { YtTestHooks.log("YtPause") { "pause id=$channelId name=$name ok=${it != null}" } }
    }

    fun resume(entry: YtPausedChannel) {
        set(YtPauseRules.resume(flow.value, entry.id, entry.name))
        YtTestHooks.log("YtPause") { "resume id=${entry.id} name=${entry.name}" }
    }

    /** Puts a pause back as it was (undo of a resume). */
    fun restore(entry: YtPausedChannel) {
        set(YtPauseRules.restore(flow.value, entry, System.currentTimeMillis()))
    }

    private fun set(paused: List<YtPausedChannel>) {
        flow.value = paused
        save(paused)
    }

    private fun load(): List<YtPausedChannel> {
        val stored = runCatching {
            JsonHelper.json.decodeFromString<List<YtPausedChannel>>(PreferenceHelper.getString(KEY, "[]"))
        }.getOrDefault(emptyList())
        val running = YtPauseRules.prune(stored, System.currentTimeMillis())
        if (running !== stored) save(running)
        return running
    }

    private fun save(paused: List<YtPausedChannel>) {
        if (paused.isEmpty()) {
            PreferenceHelper.remove(KEY)
        } else {
            PreferenceHelper.putString(KEY, JsonHelper.json.encodeToString(paused))
        }
    }
}
