package com.github.libretube.ui.yt.feed

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import com.github.libretube.R
import com.github.libretube.databinding.DialogSliderBinding
import com.github.libretube.helpers.PreferenceHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/**
 * YT: where the card swipe's tolerance is stored ("Wisch-Empfindlichkeit", "Fein einstellen",
 * calibration). The card reads [current] once per composition; it is cached until one of the
 * keys changes.
 */
object YtSwipeTuningPrefs {
    const val SENSITIVITY = "yt_swipe_sensitivity"
    const val FINE = "yt_swipe_fine"
    const val ANGLE = "yt_swipe_angle"
    const val DRIFT = "yt_swipe_drift"
    const val DISTANCE = "yt_swipe_distance"
    private val KEYS = setOf(SENSITIVITY, FINE, ANGLE, DRIFT, DISTANCE)

    @Volatile
    private var cached: YtSwipeTuning? = null

    val sensitivity: Int get() = PreferenceHelper.getInt(SENSITIVITY, YtSwipeTuning.DEFAULT_SENSITIVITY)
    val fine: Boolean get() = PreferenceHelper.getBoolean(FINE, false)

    /** The tuning the card swipe uses now. */
    fun current(): YtSwipeTuning = cached ?: compute().also { cached = it }

    /** The slider's values alone, what the fine values start from. */
    fun fromSensitivity(): YtSwipeTuning = YtSwipeTuning.forSensitivity(sensitivity)

    private fun compute(): YtSwipeTuning {
        if (!fine) return fromSensitivity()
        fun stored(key: String) = PreferenceHelper.getInt(key, -1).takeIf { it >= 0 }?.toFloat()
        return YtSwipeTuning.resolve(sensitivity, stored(ANGLE), stored(DRIFT), stored(DISTANCE))
    }

    fun setSensitivity(value: Int) {
        PreferenceHelper.putInt(SENSITIVITY, value.coerceIn(0, 100))
        invalidate()
    }

    fun setValue(key: String, value: Int) {
        PreferenceHelper.putInt(key, value)
        invalidate()
    }

    /** Applies a calibration: the fine values are switched on with [tuning]'s angle, drift and distance. */
    fun applyFine(tuning: YtSwipeTuning) {
        val t = tuning.clamped()
        PreferenceHelper.putInt(ANGLE, t.angleDeg.roundToInt())
        PreferenceHelper.putInt(DRIFT, t.driftDp.roundToInt())
        PreferenceHelper.putInt(DISTANCE, t.distanceDp.roundToInt())
        PreferenceHelper.putBoolean(FINE, true)
        invalidate()
    }

    /** Back to the built-in values. */
    fun reset() {
        KEYS.forEach(PreferenceHelper::remove)
        invalidate()
    }

    fun invalidate() {
        cached = null
    }

    /** Drops the cache whenever one of the keys changes, also through the plain switch preference. */
    internal fun isTuningKey(key: String?) = key in KEYS
}

/** Keeps a preference's summary in step with the swipe tuning keys while it is shown. */
private class TuningWatch(private val context: Context, private val onChange: () -> Unit) :
    SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    fun start() = prefs.registerOnSharedPreferenceChangeListener(this)
    fun stop() = prefs.unregisterOnSharedPreferenceChangeListener(this)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (!YtSwipeTuningPrefs.isTuningKey(key)) return
        YtSwipeTuningPrefs.invalidate()
        onChange()
    }
}

/** Opens a slider dialog (the app's usual one) for an integer setting. */
private fun sliderDialog(
    context: Context,
    title: CharSequence?,
    value: Int,
    range: IntRange,
    step: Int,
    label: (Int) -> String,
    onPick: (Int) -> Unit,
) {
    val binding = DialogSliderBinding.inflate(LayoutInflater.from(context))
    val slider = binding.slider
    slider.valueFrom = range.first.toFloat()
    slider.valueTo = range.last.toFloat()
    slider.stepSize = step.toFloat()
    slider.value = value.coerceIn(range).let { range.first + ((it - range.first) / step) * step }.toFloat()
    slider.setLabelFormatter { label(it.roundToInt()) }
    fun update() {
        binding.currentValue.text = label(slider.value.roundToInt())
        binding.minus.alpha = if (slider.value <= slider.valueFrom) 0.5f else 1f
        binding.plus.alpha = if (slider.value >= slider.valueTo) 0.5f else 1f
    }
    binding.minus.setOnClickListener { slider.value = maxOf(slider.valueFrom, slider.value - step) }
    binding.plus.setOnClickListener { slider.value = minOf(slider.valueTo, slider.value + step) }
    slider.addOnChangeListener { _, _, _ -> update() }
    update()
    MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setView(binding.root)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.okay) { _, _ -> onPick(slider.value.roundToInt()) }
        .show()
}

/** The slider's words: strict … loose, with the middle named as the default. */
internal fun sensitivityLabel(context: Context, value: Int): String {
    val word = when {
        value == YtSwipeTuning.DEFAULT_SENSITIVITY -> R.string.yt_swipe_level_default
        value < 20 -> R.string.yt_swipe_level_very_strict
        value < YtSwipeTuning.DEFAULT_SENSITIVITY -> R.string.yt_swipe_level_strict
        value <= 80 -> R.string.yt_swipe_level_loose
        else -> R.string.yt_swipe_level_very_loose
    }
    return context.getString(R.string.yt_swipe_level, context.getString(word), value)
}

/**
 * YT: "Wisch-Empfindlichkeit": one slider from strict (0) to loose (100) for the card swipe;
 * 50 keeps the built-in values.
 */
class YtSwipeSensitivityPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private val watch = TuningWatch(context) { notifyChanged() }

    init {
        isPersistent = false
    }

    override fun onAttached() {
        super.onAttached()
        watch.start()
    }

    override fun onDetached() {
        watch.stop()
        super.onDetached()
    }

    override fun getSummary(): CharSequence {
        val level = sensitivityLabel(context, YtSwipeTuningPrefs.sensitivity)
        return if (YtSwipeTuningPrefs.fine) context.getString(R.string.yt_swipe_sensitivity_fine_on, level) else level
    }

    override fun onClick() {
        sliderDialog(context, title, YtSwipeTuningPrefs.sensitivity, 0..100, 5, { sensitivityLabel(context, it) }) {
            YtSwipeTuningPrefs.setSensitivity(it)
            notifyChanged()
        }
    }
}

/**
 * YT: one fine value of the card swipe (angle, drift or distance, chosen by its key). Shown
 * under "Fein einstellen"; while that is off it shows the value the slider gives.
 */
class YtSwipeValuePreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private val watch = TuningWatch(context) { notifyChanged() }

    init {
        isPersistent = false
    }

    override fun onAttached() {
        super.onAttached()
        watch.start()
    }

    override fun onDetached() {
        watch.stop()
        super.onDetached()
    }

    private fun valueOf(tuning: YtSwipeTuning): Int = when (key) {
        YtSwipeTuningPrefs.ANGLE -> tuning.angleDeg
        YtSwipeTuningPrefs.DRIFT -> tuning.driftDp
        else -> tuning.distanceDp
    }.roundToInt()

    private val range: IntRange
        get() = when (key) {
            YtSwipeTuningPrefs.ANGLE -> YtSwipeTuning.ANGLE_RANGE
            YtSwipeTuningPrefs.DRIFT -> YtSwipeTuning.DRIFT_RANGE
            else -> YtSwipeTuning.DISTANCE_RANGE
        }.let { it.start.roundToInt()..it.endInclusive.roundToInt() }

    private fun label(value: Int): String =
        if (key == YtSwipeTuningPrefs.ANGLE) context.getString(R.string.yt_swipe_degrees, value) else context.getString(R.string.yt_swipe_dp, value)

    private fun hint(): Int = when (key) {
        YtSwipeTuningPrefs.ANGLE -> R.string.yt_swipe_angle_summary
        YtSwipeTuningPrefs.DRIFT -> R.string.yt_swipe_drift_summary
        else -> R.string.yt_swipe_distance_summary
    }

    override fun getSummary(): CharSequence =
        context.getString(hint(), label(valueOf(YtSwipeTuningPrefs.current())))

    override fun onClick() {
        sliderDialog(context, title, valueOf(YtSwipeTuningPrefs.current()), range, 1, ::label) {
            YtSwipeTuningPrefs.setValue(key, it)
            notifyChanged()
        }
    }
}

/**
 * YT: "Fein einstellen": switching it on starts the fine values from what the slider gives, so
 * nothing jumps.
 */
class YtSwipeFinePreference(context: Context, attrs: AttributeSet?) :
    androidx.preference.SwitchPreferenceCompat(context, attrs) {
    override fun onClick() {
        if (!isChecked) {
            val base = YtSwipeTuningPrefs.fromSensitivity().clamped()
            YtSwipeTuningPrefs.setValue(YtSwipeTuningPrefs.ANGLE, base.angleDeg.roundToInt())
            YtSwipeTuningPrefs.setValue(YtSwipeTuningPrefs.DRIFT, base.driftDp.roundToInt())
            YtSwipeTuningPrefs.setValue(YtSwipeTuningPrefs.DISTANCE, base.distanceDp.roundToInt())
        }
        super.onClick()
        YtSwipeTuningPrefs.invalidate()
    }
}

/** YT: "Wischgesten kalibrieren": opens the calibration screen over the settings. */
class YtSwipeCalibratePreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        isPersistent = false
    }

    override fun onClick() {
        YtSwipeCalibrationScreen.show(context) { tuning ->
            YtSwipeTuningPrefs.applyFine(tuning)
            preferenceManager?.findPreference<TwoStatePreference>(YtSwipeTuningPrefs.FINE)?.isChecked = true
        }
    }
}

/** YT: "Zurücksetzen": the card swipe's built-in values again (slider in the middle, fine off). */
class YtSwipeResetPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init {
        isPersistent = false
    }

    override fun onClick() {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(R.string.yt_swipe_reset_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.yt_swipe_reset_confirm) { _, _ ->
                YtSwipeTuningPrefs.reset()
                preferenceManager?.findPreference<TwoStatePreference>(YtSwipeTuningPrefs.FINE)?.isChecked = false
                YtSwipeTuningPrefs.reset()
            }
            .show()
    }
}
