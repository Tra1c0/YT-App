package com.github.libretube.ui.yt

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.github.libretube.helpers.YtBatteryCue
import com.github.libretube.helpers.YtGuard

/**
 * YT: the vibration of a battery cue ([YtBatteryCue]): a waveform with amplitudes where the
 * vibrator can vary its strength, else the plain on/off pattern. Follows the switch
 * "Eigene Haptik" (yt_haptics): off, the cue does not vibrate. It is a notice rather than
 * touch feedback, so it plays as a notification vibration (Android 13+).
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

    private fun vibrator(context: Context): Vibrator? {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            app.getSystemService(Vibrator::class.java)
        }
    }
}
