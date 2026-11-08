package com.github.libretube.ui.yt

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtBatteryCues
import com.github.libretube.helpers.YtBatteryPreviewRules
import com.github.libretube.helpers.YtBatteryPreviewRules.Note
import com.github.libretube.ui.yt.player.YtBatteryCueView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT: the preview of the battery cue (Player settings → Battery → Preview). A 16:9 stand-in
 * picture (the thumbnail of the last watched video, else a neutral gradient) is wrapped in the
 * real [YtBatteryCueView], so the animation is the player's own; the vibration is the real
 * [YtBatteryVibes]. It follows the current settings: with the animations off the colour frame
 * only, with "Custom haptics" off no vibration, and it says so. The player, if one plays, is
 * never touched. Everything stops when the dialog goes (dismiss, stop, rotation).
 */
class YtBatteryPreviewDialog : DialogFragment() {
    private var frame: View? = null
    private var cueView: YtBatteryCueView? = null
    private var label: TextView? = null
    private var notes: TextView? = null
    private var allButton: MaterialButton? = null
    private var run: Job? = null
    private var vibrated = false

    private val prefsWatch = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == YtMotionPrefs.KEY || key == YtHapticsPrefs.KEY) showNotes()
    }

    // battery saver and the ringer change from the quick settings while the dialog stays open
    private var systemWatchOn = false
    private val systemWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showNotes()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext())
        val themed = builder.context
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(themed, 24), dp(themed, 8), dp(themed, 24), 0)
        }

        // the picture: a 16:9 frame with the stand-in image, the cue view above it, the label on top
        val picture = Ratio(themed).apply {
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply {
                cornerRadius = dp(themed, 12).toFloat()
                setColor(0xFF000000.toInt())
            }
        }
        val image = ImageView(themed).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF5A6270.toInt(), 0xFF2B3038.toInt()),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val cue = YtBatteryCueView(themed)
        val level = TextView(themed).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(themed, 18), dp(themed, 8), dp(themed, 18), dp(themed, 8))
            visibility = View.INVISIBLE
        }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        picture.addView(image, FrameLayout.LayoutParams(match, match))
        picture.addView(cue, FrameLayout.LayoutParams(match, match))
        picture.addView(
            level,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        content.addView(picture, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(
            text(themed, getString(R.string.yt_battery_preview_hint), primary = true).apply {
                setPadding(0, dp(themed, 12), 0, 0)
            },
        )
        val noteView = text(themed, "", primary = false).apply { setPadding(0, dp(themed, 6), 0, 0) }
        content.addView(noteView)

        val chips = ChipGroup(themed).apply { setPadding(0, dp(themed, 8), 0, 0) }
        for (mark in YtBatteryCues.MARKS) {
            chips.addView(
                Chip(themed).apply {
                    text = getString(R.string.yt_battery_preview_level, mark)
                    isCheckable = false
                    setOnClickListener { playOne(mark) }
                },
            )
        }
        content.addView(chips)
        val all = MaterialButton(themed).apply {
            setText(R.string.yt_battery_preview_all)
            setOnClickListener { if (run?.isActive == true) stop() else playAll() }
        }
        content.addView(
            all,
            LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(themed, 4)
            },
        )

        frame = image
        cueView = cue
        label = level
        notes = noteView
        allButton = all
        showNotes()
        loadPicture(image)

        return builder
            .setTitle(R.string.yt_battery_preview_title)
            .setView(ScrollView(themed).apply { addView(content) })
            .setNegativeButton(R.string.yt_battery_preview_close, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        PreferenceHelper.settings.registerOnSharedPreferenceChangeListener(prefsWatch)
        runCatching {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED).apply {
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(requireContext(), systemWatch, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            systemWatchOn = true
        }
        showNotes()
    }

    override fun onStop() {
        // leaving the app or rotating ends the cue: no small picture, no vibration left over
        stop()
        PreferenceHelper.settings.unregisterOnSharedPreferenceChangeListener(prefsWatch)
        if (systemWatchOn) {
            systemWatchOn = false
            runCatching { requireContext().unregisterReceiver(systemWatch) }
        }
        super.onStop()
    }

    override fun onDismiss(dialog: DialogInterface) {
        stop()
        super.onDismiss(dialog)
    }

    override fun onDestroy() {
        stop()
        frame = null
        cueView = null
        label = null
        notes = null
        allButton = null
        super.onDestroy()
    }

    private fun motion(): Boolean = YtMotionPrefs.enabled

    // the same decision as the player's cue: Android drops a vibration in battery saver or on silent
    private fun vibrates(context: Context): Boolean =
        YtBatteryPreviewRules.vibrates(YtBatteryVibes.blocked(context))

    private fun showNotes() {
        val view = notes ?: return
        val context = view.context
        val list = YtBatteryPreviewRules.notes(
            motion = motion(),
            blocked = YtBatteryVibes.blocked(context),
            amplitude = YtBatteryVibes.hasAmplitudeControl(context),
        )
        view.text = list.joinToString("\n") {
            context.getString(
                when (it) {
                    Note.MOTION_OFF -> R.string.yt_battery_preview_motion_off
                    Note.HAPTICS_OFF -> R.string.yt_battery_preview_haptics_off
                    Note.NO_VIBRATOR -> R.string.yt_battery_preview_no_vibrator
                    Note.POWER_SAVE -> R.string.yt_battery_preview_power_save
                    Note.SILENT -> R.string.yt_battery_preview_silent
                    Note.SYSTEM_OFF -> R.string.yt_battery_preview_system_off
                    Note.SWELL_AS_PULSES -> R.string.yt_battery_preview_pulses
                },
            )
        }
        view.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The most recently watched video's thumbnail, if there is one; the gradient stays otherwise. */
    private fun loadPicture(image: ImageView) {
        lifecycleScope.launch {
            val url = withContext(Dispatchers.IO) {
                runCatching {
                    Database.watchHistoryDao().getPage(
                        limit = 1,
                        cursor = Long.MAX_VALUE,
                        watched = null,
                        absoluteWatchedThresholdSeconds = 0f,
                        relativeWatchedThreshold = 0f,
                    ).firstOrNull()?.item?.thumbnailUrl
                }.getOrNull()
            }
            if (!url.isNullOrEmpty()) runCatching { ImageHelper.loadImage(url, image) }
        }
    }

    private fun playOne(mark: Int) {
        stop()
        val context = context ?: return
        val cue = YtBatteryCues.of(mark) ?: return
        val motion = motion()
        val vibrates = vibrates(context)
        val length = YtBatteryPreviewRules.lengthMs(
            cue, motion, vibrates, YtBatteryVibes.hasAmplitudeControl(context),
        )
        run = lifecycleScope.launch {
            show(mark, motion)
            delay(length)
            hideLabel(motion)
        }
    }

    private fun playAll() {
        stop()
        val context = context ?: return
        val motion = motion()
        val steps = YtBatteryPreviewRules.sequence(
            motion = motion,
            vibrates = vibrates(context),
            amplitude = YtBatteryVibes.hasAmplitudeControl(context),
        )
        allButton?.setText(R.string.yt_battery_preview_stop)
        run = lifecycleScope.launch {
            try {
                var now = 0L
                for (step in steps) {
                    delay(step.atMs - now)
                    show(step.mark, motion)
                    delay(step.lengthMs)
                    hideLabel(motion)
                    now = step.atMs + step.lengthMs
                }
            } finally {
                allButton?.setText(R.string.yt_battery_preview_all)
            }
        }
    }

    /** Plays [mark]'s cue on the picture with the real view and vibration, and shows its label. */
    private fun show(mark: Int, motion: Boolean) {
        val cue = YtBatteryCues.of(mark) ?: return
        val frame = frame ?: return
        val view = cueView ?: return
        val vibrates = vibrates(view.context)
        view.play(frame, cue, motion = motion, strong = !vibrates)
        if (vibrates) {
            YtBatteryVibes.play(view.context, cue)
            vibrated = true
        }
        val label = label ?: return
        label.animate().cancel()
        // the big level exists only here (the player shows no number): a small caption says so
        val level = getString(R.string.yt_battery_preview_level, mark)
        val caption = getString(R.string.yt_battery_preview_label_caption)
        label.text = SpannableString("$level\n$caption").apply {
            val from = level.length + 1
            setSpan(RelativeSizeSpan(0.36f), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        label.setTextColor(YtContrast.readable(WHITE, cue.endColor))
        label.background = GradientDrawable().apply {
            cornerRadius = dp(label.context, 18).toFloat()
            setColor(cue.endColor)
        }
        label.visibility = View.VISIBLE
        if (motion) {
            label.alpha = 0f
            label.scaleX = 0.9f
            label.scaleY = 0.9f
            label.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(YtBatteryCues.IN_MS).start()
        } else {
            label.alpha = 1f
            label.scaleX = 1f
            label.scaleY = 1f
        }
    }

    private fun hideLabel(motion: Boolean) {
        val label = label ?: return
        label.animate().cancel()
        if (motion) {
            label.animate().alpha(0f).setDuration(LABEL_OUT_MS)
                .withEndAction { label.visibility = View.INVISIBLE }.start()
        } else {
            label.visibility = View.INVISIBLE
        }
    }

    /** Ends whatever plays: the run, the cue (picture back to full size), the label, the vibration. */
    private fun stop() {
        run?.cancel()
        run = null
        allButton?.setText(R.string.yt_battery_preview_all)
        cueView?.stop(restore = true)
        label?.let {
            it.animate().cancel()
            it.alpha = 1f
            it.scaleX = 1f
            it.scaleY = 1f
            it.visibility = View.INVISIBLE
        }
        if (vibrated) {
            vibrated = false
            (context ?: cueView?.context)?.let { YtBatteryVibes.cancel(it) }
        }
    }

    /** A frame that is always 16:9 of its width. */
    private class Ratio(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = width * 9 / 16
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
    }

    companion object {
        const val TAG = "yt_battery_preview"
        private const val LABEL_OUT_MS = 200L
        private const val WHITE = -0x1

        fun show(manager: androidx.fragment.app.FragmentManager) {
            if (manager.findFragmentByTag(TAG) != null) return
            YtBatteryPreviewDialog().show(manager, TAG)
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density + 0.5f).toInt()

        /** [context] is the dialog's themed context, so the colours match the dialog surface. */
        private fun text(context: Context, value: String, primary: Boolean) = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 14f else 13f)
            setTextColor(
                MaterialColors.getColor(
                    context,
                    if (primary) {
                        com.google.android.material.R.attr.colorOnSurface
                    } else {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    },
                    currentTextColor,
                ),
            )
        }
    }
}
