package com.github.libretube.ui.yt.player

import com.github.libretube.helpers.PreferenceHelper

/**
 * YT: the explanation loops used on a video, kept on the device (as YtNotes keeps notes), so a
 * marked passage is there again next time; the newest first, a few per video.
 */
object YtLoops {
    private fun key(videoId: String) = "yt_loops_$videoId"

    fun get(videoId: String): List<YtLoop> =
        runCatching { YtLoopPlan.decode(PreferenceHelper.getString(key(videoId), "")) }.getOrDefault(emptyList())

    fun remember(videoId: String, loop: YtLoop): List<YtLoop> =
        YtLoopPlan.remember(get(videoId), loop).also { save(videoId, it) }

    fun forget(videoId: String, loop: YtLoop): List<YtLoop> =
        get(videoId).filterNot { YtLoopPlan.samePassage(it, loop) }.also { save(videoId, it) }

    private fun save(videoId: String, loops: List<YtLoop>) {
        if (loops.isEmpty()) {
            PreferenceHelper.remove(key(videoId))
        } else {
            PreferenceHelper.putString(key(videoId), YtLoopPlan.encode(loops))
        }
    }
}
