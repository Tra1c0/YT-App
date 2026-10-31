package com.github.libretube.ui.yt

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.SparseBooleanArray
import androidx.annotation.RequiresApi

/**
 * YT: plays the designs' haptic textures as vibration compositions (Android 11 and later),
 * only when the vibrator has every primitive of the texture; otherwise the caller plays the
 * texture's touch feedback constant instead. On Android 13 and later the vibration counts as
 * touch feedback (USAGE_TOUCH), so the system's touch feedback setting applies to it; before,
 * that setting is asked here. Main thread only.
 */
internal object YtVibes {
    private var vibrator: Vibrator? = null
    private var looked = false

    /** What the vibrator answered per primitive; the answer does not change. */
    private val supported = SparseBooleanArray()

    /** @return true if [texture] played, false if the caller falls back. */
    fun play(context: Context, texture: YtHapticTexture): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !texture.composableOn(Build.VERSION.SDK_INT)) return false
        return runCatching { compose(context, texture) }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun compose(context: Context, texture: YtHapticTexture): Boolean {
        val vibrator = vibrator(context) ?: return false
        if (!vibrator.hasVibrator()) return false
        if (!texture.steps.all { has(vibrator, it.primitive) }) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !touchFeedbackOn(context)) return false

        val composition = VibrationEffect.startComposition()
        for (step in texture.steps) {
            composition.addPrimitive(step.primitive, step.scale.coerceIn(0f, 1f), step.delayMs.coerceAtLeast(0))
        }
        val effect = composition.compose()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrateAsTouch(vibrator, effect)
        } else {
            vibrator.vibrate(effect)
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun has(vibrator: Vibrator, primitive: Int): Boolean {
        val index = supported.indexOfKey(primitive)
        if (index >= 0) return supported.valueAt(index)
        val answer = vibrator.areAllPrimitivesSupported(primitive)
        supported.put(primitive, answer)
        return answer
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun vibrateAsTouch(vibrator: Vibrator, effect: VibrationEffect) {
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
    }

    /** The system's touch feedback switch (before Android 13, where vibrations know nothing of it). */
    @Suppress("DEPRECATION")
    private fun touchFeedbackOn(context: Context): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    private fun vibrator(context: Context): Vibrator? {
        if (!looked) {
            looked = true
            val app = context.applicationContext
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                app.getSystemService(Vibrator::class.java)
            }
        }
        return vibrator
    }
}
