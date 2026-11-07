package com.github.libretube.ui.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import com.github.libretube.R
import com.github.libretube.helpers.YtChannelSpeed
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat

/**
 * YT: "Geschwindigkeit je Kanal" in the player settings: the channels with their own speed; the
 * ones ticked in the dialog lose it. Stores nothing itself.
 */
class YtChannelSpeedsPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        isPersistent = false
    }

    override fun onAttached() {
        super.onAttached()
        refresh()
    }

    private fun refresh() {
        val count = YtChannelSpeed.all().size
        summary = if (count == 0) {
            context.getString(R.string.yt_channel_speeds_none)
        } else {
            context.resources.getQuantityString(R.plurals.yt_channel_speeds_count, count, count)
        }
    }

    override fun onClick() {
        val entries = YtChannelSpeed.all().sortedBy { it.name.lowercase() }
        if (entries.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(R.string.yt_channel_speeds_none)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val number = NumberFormat.getInstance(context.resources.configuration.locales[0]).apply {
            maximumFractionDigits = 2
        }
        val labels = entries.map<_, CharSequence> {
            context.getString(R.string.yt_channel_speed_entry, it.name.ifBlank { it.id }, number.format(it.speed))
        }.toTypedArray()
        val checked = BooleanArray(entries.size)
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yt_channel_speeds_remove) { _, _ ->
                entries.filterIndexed { i, _ -> checked[i] }.forEach { YtChannelSpeed.remove(it.id) }
                refresh()
            }
            .show()
    }
}
