package com.github.libretube.ui.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.preference.Preference
import com.github.libretube.R
import com.github.libretube.databinding.DialogSliderBinding
import com.github.libretube.ui.yt.archive.YtArchive
import com.github.libretube.ui.yt.archive.YtArchiveRules
import com.github.libretube.ui.yt.archive.YtArchiveUi
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * YT: the archive's capacity: a slider over the steps of [YtArchiveRules.CAPACITY_STEPS_MB]
 * (5 MB … 15 GB, not linear), showing what fits at each step ("≈ Text für 1000 Videos · ≈ 300
 * Videos mit Ton …") and the extra room for high-risk videos. Stored as an int in MB.
 */
class YtArchiveCapacityPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private fun value(): Int = getPersistedInt(YtArchiveRules.DEFAULT_CAPACITY_MB)

    override fun getSummary(): CharSequence = summaryOf(value())

    private fun summaryOf(mb: Int): String {
        val budget = YtArchiveRules.budgetBytes(mb)
        return context.getString(R.string.yt_r31_archive_capacity_summary, YtArchiveUi.size(budget), YtArchiveUi.fits(context, budget, YtArchive.top()))
    }

    override fun onClick() {
        val binding = DialogSliderBinding.inflate(LayoutInflater.from(context))
        val last = YtArchiveRules.CAPACITY_STEPS_MB.lastIndex.toFloat()
        val extra = TextView(context).apply {
            setPadding(0, 8, 0, 0)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        binding.root.addView(extra)
        binding.slider.apply {
            valueFrom = 0f
            valueTo = last
            stepSize = 1f
            value = YtArchiveRules.stepIndexOf(value()).toFloat()
            // the steps are sizes, not numbers
            setLabelFormatter { YtArchiveUi.size(YtArchiveRules.budgetBytes(YtArchiveRules.capacityMb(it.toInt()))) }
        }
        fun show() {
            val mb = YtArchiveRules.capacityMb(binding.slider.value.toInt())
            val budget = YtArchiveRules.budgetBytes(mb)
            binding.currentValue.text = YtArchiveUi.size(budget)
            val fits = YtArchiveUi.fits(context, budget, YtArchive.top())
            val more = context.getString(R.string.yt_r31_archive_capacity_extra, YtArchiveUi.size(YtArchiveRules.hardCap(budget) - budget))
            extra.text = "$fits\n$more"
            extra.isVisible = true
            binding.minus.alpha = if (binding.slider.value <= 0f) 0.5f else 1f
            binding.plus.alpha = if (binding.slider.value >= last) 0.5f else 1f
        }
        binding.minus.setOnClickListener { binding.slider.value = (binding.slider.value - 1).coerceAtLeast(0f) }
        binding.plus.setOnClickListener { binding.slider.value = (binding.slider.value + 1).coerceAtMost(last) }
        binding.slider.addOnChangeListener { _, _, _ -> show() }
        show()
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.okay) { _, _ ->
                val mb = YtArchiveRules.capacityMb(binding.slider.value.toInt())
                if (callChangeListener(mb)) {
                    persistInt(mb)
                    notifyChanged()
                }
            }
            .show()
    }
}
