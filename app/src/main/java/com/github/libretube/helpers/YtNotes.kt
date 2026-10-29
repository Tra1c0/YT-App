package com.github.libretube.helpers

import com.github.libretube.api.JsonHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** YT: a private note on a video, written at a position of the video. */
@Serializable
data class YtNote(
    val positionMillis: Long,
    val text: String,
    val createdAt: Long,
)

/**
 * YT: local notes per video ("Notizen" in the design Side). They stay on the device,
 * there is no account to send them anywhere.
 */
object YtNotes {
    private fun key(videoId: String) = "yt_notes_$videoId"

    fun get(videoId: String): List<YtNote> = runCatching {
        JsonHelper.json.decodeFromString<List<YtNote>>(PreferenceHelper.getString(key(videoId), "[]"))
    }.getOrDefault(emptyList())

    fun add(videoId: String, note: YtNote): List<YtNote> =
        (get(videoId) + note).sortedBy { it.positionMillis }.also { save(videoId, it) }

    fun remove(videoId: String, note: YtNote): List<YtNote> =
        (get(videoId) - note).also { save(videoId, it) }

    private fun save(videoId: String, notes: List<YtNote>) {
        if (notes.isEmpty()) {
            PreferenceHelper.remove(key(videoId))
        } else {
            PreferenceHelper.putString(key(videoId), JsonHelper.json.encodeToString(notes))
        }
    }
}

/** YT: videos marked with a heart in the design Side; only stored on the device. */
object YtHearts {
    private const val KEY = "yt_side_hearts"

    fun all(): Set<String> = PreferenceHelper.getStringSet(KEY, emptySet())

    fun toggle(videoId: String): Set<String> {
        val hearts = all().toMutableSet()
        if (!hearts.add(videoId)) hearts.remove(videoId)
        PreferenceHelper.putStringSet(KEY, hearts)
        return hearts
    }
}
