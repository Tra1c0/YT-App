package com.github.libretube.ui.yt.feed

import com.github.libretube.helpers.PreferenceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * YT: the session a topic set to "Nur diese Sitzung" lives in. A session starts with the first
 * start and again whenever the app comes back after half an hour away (YtFeedTopics.nextSession,
 * the same visit the intent chooser asks on); a process the system killed in between keeps it.
 * Stored as its start time (yt_topic_session) and the last time the app went to the background
 * (yt_topic_last_stop). Main thread only.
 */
object YtTopicSession {
    private const val KEY = "yt_topic_session"
    private const val LAST_STOP_KEY = "yt_topic_last_stop"

    private val currentFlow = MutableStateFlow(load())

    /** The start of the running session, ms. */
    val current: StateFlow<Long> get() = currentFlow

    private fun load(): Long = runCatching {
        val now = System.currentTimeMillis()
        val stored = PreferenceHelper.getLong(KEY, 0L)
        val lastStop = PreferenceHelper.getLong(LAST_STOP_KEY, 0L).takeIf { it > 0L }
        YtFeedTopics.nextSession(now, lastStop, stored).also { if (it != stored) PreferenceHelper.putLong(KEY, it) }
    }.getOrDefault(System.currentTimeMillis())

    /** The app came to the front (MainActivity.onStart). */
    fun onStart() {
        val now = System.currentTimeMillis()
        val lastStop = PreferenceHelper.getLong(LAST_STOP_KEY, 0L).takeIf { it > 0L }
        val next = YtFeedTopics.nextSession(now, lastStop, currentFlow.value)
        if (next != currentFlow.value) {
            currentFlow.value = next
            PreferenceHelper.putLong(KEY, next)
        }
    }

    /** The app went to the background (a real onStop, not a rotation). */
    fun onStop() {
        PreferenceHelper.putLong(LAST_STOP_KEY, System.currentTimeMillis())
    }
}
