package com.github.libretube.helpers

import com.github.libretube.api.JsonHelper
import com.github.libretube.constants.PreferenceKeys
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * YT: playback speed per channel ("Für diesen Kanal merken" in the speed sheet). One JSON list
 * under [KEY]; a channel is known by the id in its URL. The service applies the stored speed when
 * a video of the channel starts (see [YtSpeedRules.startSpeed]).
 */
object YtChannelSpeed {
    const val KEY = "yt_channel_speeds"

    fun all(): List<YtChannelSpeedEntry> = runCatching {
        JsonHelper.json.decodeFromString<List<YtChannelSpeedEntry>>(PreferenceHelper.getString(KEY, "[]"))
    }.getOrDefault(emptyList())

    fun find(channelId: String?): YtChannelSpeedEntry? = YtSpeedRules.find(all(), channelId)

    fun put(channelId: String, name: String, speed: Float) =
        save(YtSpeedRules.put(all(), channelId, name, speed))

    fun remove(channelId: String) = save(YtSpeedRules.remove(all(), channelId))

    /** The general speed from the settings (what upstream stores on every change in the sheet). */
    fun generalSpeed(): Float = YtSpeedRules.clampSpeed(
        PreferenceHelper.getString(PreferenceKeys.PLAYBACK_SPEED, "1").replace("F", "").toFloatOrNull() ?: 1f
    )

    private fun save(entries: List<YtChannelSpeedEntry>) {
        if (entries.isEmpty()) {
            PreferenceHelper.remove(KEY)
        } else {
            PreferenceHelper.putString(KEY, JsonHelper.json.encodeToString(entries))
        }
    }
}
