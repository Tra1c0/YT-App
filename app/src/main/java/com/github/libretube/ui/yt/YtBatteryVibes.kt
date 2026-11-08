package com.github.libretube.ui.yt

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import com.github.libretube.R
import com.github.libretube.helpers.YtBattery
import com.github.libretube.helpers.YtBatteryCue
import com.github.libretube.helpers.YtBatteryVibeRules
import com.github.libretube.helpers.YtGuard

/**
 * YT: the vibration of a battery cue ([YtBatteryCue]): a waveform with amplitudes where the
 * vibrator can vary its strength, else the plain on/off pattern. Follows the switch
 * "Eigene Haptik" (yt_haptics): off, the cue does not vibrate. It is a notice rather than
 * touch feedback, so it plays as a notification vibration (Android 13+). Android drops it in
 * battery saver and (13+) on a silent ringer; [blocked] tells, and the cue then shows stronger.
 */
object YtBatteryVibes {
    fun play(context: Context, cue: YtBatteryCue) {
        if (YtGuard.isEmulator) Log.i("YtHaptic", "battery ${cue.mark}")
        if (!YtHapticsPrefs.enabled) return
        runCatching {
            val vibrator = vibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            val effect = if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(cue.timings, cue.amplitudes, -1)
            } else {
                VibrationEffect.createWaveform(cue.plainTimings, -1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
            } else {
                vibrator.vibrate(effect)
            }
        }
    }

    /**
     * Why a cue's vibration would not be felt now, or null if it vibrates (see
     * [YtBatteryVibeRules]). The ringer and the system's notification vibration only matter
     * where the cue plays as a notification vibration (Android 13+).
     */
    fun blocked(context: Context): YtBatteryVibeRules.Block? {
        val notificationUsage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        return YtBatteryVibeRules.blocked(
            haptics = YtHapticsPrefs.enabled,
            hasVibrator = hasVibrator(context),
            powerSave = YtBattery.powerSave(context),
            ringerSilent = notificationUsage && ringerSilent(context),
            systemOff = notificationUsage && notificationVibrationOff(context),
        )
    }

    /**
     * The summary of the cue's switch: its text, plus why it would not vibrate right now
     * (battery saver, silent) so the user is not left wondering. "Custom haptics" off is in
     * the text already.
     */
    fun cueSummary(context: Context): CharSequence {
        val base = context.getString(R.string.yt_battery_cue_summary)
        val note = when (blocked(context)) {
            YtBatteryVibeRules.Block.NO_VIBRATOR -> R.string.yt_battery_preview_no_vibrator
            YtBatteryVibeRules.Block.POWER_SAVE -> R.string.yt_battery_preview_power_save
            YtBatteryVibeRules.Block.SILENT -> R.string.yt_battery_preview_silent
            YtBatteryVibeRules.Block.SYSTEM_OFF -> R.string.yt_battery_preview_system_off
            YtBatteryVibeRules.Block.HAPTICS_OFF, null -> return base
        }
        return base + "\n" + context.getString(note)
    }

    private fun ringerSilent(context: Context): Boolean = runCatching {
        context.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_SILENT
    }.getOrDefault(false)

    // Settings.System.NOTIFICATION_VIBRATION_INTENSITY is not public: read by name, 0 = off;
    // unreadable (newer Android may refuse) counts as on
    private fun notificationVibrationOff(context: Context): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, "notification_vibration_intensity", -1) == 0
    }.getOrDefault(false)

    /** Whether the device can vibrate at all. */
    fun hasVibrator(context: Context): Boolean =
        runCatching { vibrator(context)?.hasVibrator() == true }.getOrDefault(false)

    /** Whether the vibrator can vary its strength (else the plain on/off patterns play). */
    fun hasAmplitudeControl(context: Context): Boolean =
        runCatching { vibrator(context)?.hasAmplitudeControl() == true }.getOrDefault(false)

    /** Stops a cue's vibration that is still running (the preview closes or plays another). */
    fun cancel(context: Context) {
        runCatching { vibrator(context)?.cancel() }
    }

    private fun vibrator(context: Context): Vibrator? {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            app.getSystemService(Vibrator::class.java)
        }
    }
}
