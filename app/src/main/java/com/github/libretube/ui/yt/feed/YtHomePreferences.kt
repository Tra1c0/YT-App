package com.github.libretube.ui.yt.feed

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import com.github.libretube.R
import com.github.libretube.helpers.YtChannelPause
import com.github.libretube.helpers.YtHidden
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * YT: "Pausierte Kanäle" in Gesten & Interaktion: the channels paused for a week with the days
 * left; the ones ticked in the dialog are resumed. Stores nothing itself.
 */
class YtPausedChannelsPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        isPersistent = false
    }

    override fun onAttached() {
        super.onAttached()
        refresh()
    }

    private fun refresh() {
        val count = YtChannelPause.active().size
        summary = if (count == 0) {
            context.getString(R.string.yt_home_paused_channels_none)
        } else {
            context.resources.getQuantityString(R.plurals.yt_home_paused_channels_count, count, count)
        }
    }

    override fun onClick() {
        val paused = YtChannelPause.active()
        if (paused.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(R.string.yt_home_paused_channels_none)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val now = System.currentTimeMillis()
        val labels = paused.map<YtPausedChannel, CharSequence> {
            val days = context.getString(R.string.yt_home_pause_days_left, YtPauseRules.daysLeft(it.untilMillis, now))
            it.name.ifBlank { it.id.orEmpty() } + " · " + days
        }.toTypedArray()
        val checked = BooleanArray(paused.size)
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yt_home_paused_resume) { _, _ ->
                paused.filterIndexed { i, _ -> checked[i] }.forEach(YtChannelPause::resume)
                refresh()
            }
            .show()
    }
}

/**
 * YT: "Ausgeblendete Videos" in Gesten & Interaktion: how many videos the card swipe hid, and a
 * way to bring them all back at once. Stores nothing itself.
 */
class YtHiddenVideosPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        isPersistent = false
    }

    override fun onAttached() {
        super.onAttached()
        refresh()
    }

    private fun refresh() {
        val count = YtHidden.count()
        summary = if (count == 0) {
            context.getString(R.string.yt_home_hidden_videos_none)
        } else {
            context.resources.getQuantityString(R.plurals.yt_home_hidden_videos_count, count, count)
        }
    }

    override fun onClick() {
        val count = YtHidden.count()
        if (count == 0) return
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(context.resources.getQuantityString(R.plurals.yt_home_hidden_show_all_message, count, count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yt_home_hidden_show_all) { _, _ ->
                YtHidden.clear()
                refresh()
            }
            .show()
    }
}
