package com.github.libretube.ui.yt.builder

import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.metrics.performance.JankStats
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.util.YtCrashLog

/**
 * YT: measured smoothness of the theme editor (JankStats): every frame's duration while the
 * editor is in front goes into YtFrameStats; the overlay shows P50/P90/P95/P99 and the janky
 * frames, and the session's figures are kept for Diagnose ("Theme-Editor"). Tracking runs only
 * while the editor's window is resumed and is removed with it: nothing is left running when
 * the editor is closed.
 */
class YtEditorMetrics(window: Window) {
    private val stats = YtFrameStats()
    private var jank: JankStats? = runCatching { JankStats.createAndTrack(window) { frame -> stats.add(frame.frameDurationUiNanos, frame.isJank) } }.getOrNull()

    /** The overlay's line, refreshed about twice a second by the editor while it shows. */
    var line by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        line = stats.summary()?.line()
    }

    fun resume() {
        jank?.isTrackingEnabled = true
    }

    fun pause() {
        jank?.isTrackingEnabled = false
        store()
    }

    fun release() {
        pause()
        jank = null
    }

    private fun store() {
        val s = stats.summary() ?: return
        runCatching { PreferenceHelper.putString(PREF, "${s.line()} · ${YtCrashLog.formatTime(System.currentTimeMillis())}") }
    }

    companion object {
        private const val PREF = "yt_builder_frames"

        fun diagnosis(): String {
            val last = PreferenceHelper.getString(PREF, "").ifEmpty { "not opened yet" }
            val active = YtBuilderActive.doc?.let { "${it.name.take(30)} (${it.id}, schema ${it.schema})" } ?: "none loaded"
            val inUse = if (YtDesign.current() == YtDesign.CUSTOM) "in use" else "not in use"
            return "$last; theme $active, $inUse"
        }
    }
}
