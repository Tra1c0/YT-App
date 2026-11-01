package com.github.libretube.helpers

import com.github.libretube.api.JsonHelper
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.feed.YtHiddenRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * YT: videos swiped away to the left on the home screens. They stay away from the home
 * screens for 90 days (YtHiddenRules), and only from there: search, channel pages, history and
 * the library still show them. Stored on the device only, as one JSON map of video id to the
 * day it was hidden; the settings can bring them all back.
 */
object YtHidden {
    const val KEY = "yt_hidden_videos"

    private val flow by lazy { MutableStateFlow(load()) }

    /** The hidden videos, video id to the day they went; screens collect it to filter. */
    val hidden: StateFlow<Map<String, Int>> get() = flow

    fun isHidden(videoId: String): Boolean = videoId in flow.value

    fun count(): Int = flow.value.size

    fun hide(videoId: String) {
        val next = YtHiddenRules.hide(flow.value, videoId, today())
        flow.value = next
        save(next)
        YtTestHooks.log("YtHidden") { "hide id=$videoId n=${next.size}" }
    }

    fun unhide(videoId: String) {
        val next = YtHiddenRules.unhide(flow.value, videoId)
        if (next === flow.value) return
        flow.value = next
        save(next)
        YtTestHooks.log("YtHidden") { "unhide id=$videoId n=${next.size}" }
    }

    /** Brings every hidden video back. */
    fun clear() {
        flow.value = emptyMap()
        save(emptyMap())
        YtTestHooks.log("YtHidden") { "clear" }
    }

    private fun today() = YtHiddenRules.day(System.currentTimeMillis())

    private fun load(): Map<String, Int> {
        val stored = runCatching {
            JsonHelper.json.decodeFromString<Map<String, Int>>(PreferenceHelper.getString(KEY, "{}"))
        }.getOrDefault(emptyMap())
        val fresh = YtHiddenRules.prune(stored, today())
        if (fresh !== stored) save(fresh)
        return fresh
    }

    private fun save(hidden: Map<String, Int>) {
        if (hidden.isEmpty()) {
            PreferenceHelper.remove(KEY)
        } else {
            PreferenceHelper.putString(KEY, JsonHelper.json.encodeToString(hidden))
        }
    }
}
