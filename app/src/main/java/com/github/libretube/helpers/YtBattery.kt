package com.github.libretube.helpers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * YT: the battery settings and readings shared by the quality cap (player service) and the
 * battery cue (player page).
 */
object YtBattery {
    const val KEY_QUALITY = "yt_battery_quality"
    const val KEY_THRESHOLD = "yt_battery_threshold"
    const val KEY_CAP = "yt_battery_cap"
    const val KEY_POWER_SAVE = "yt_battery_power_save"
    const val KEY_CUE = "yt_battery_cue"

    val qualityEnabled: Boolean get() = PreferenceHelper.getBoolean(KEY_QUALITY, true)
    val threshold: Int
        get() = YtBatteryCapRules.parsePercent(
            PreferenceHelper.getString(KEY_THRESHOLD, YtBatteryCapRules.DEFAULT_THRESHOLD.toString()),
            YtBatteryCapRules.DEFAULT_THRESHOLD,
        )
    val capHeight: Int
        get() = YtBatteryCapRules.parseHeight(
            PreferenceHelper.getString(KEY_CAP, YtBatteryCapRules.DEFAULT_CAP.toString()),
            YtBatteryCapRules.DEFAULT_CAP,
        )
    val usePowerSave: Boolean get() = PreferenceHelper.getBoolean(KEY_POWER_SAVE, true)
    val cueEnabled: Boolean get() = PreferenceHelper.getBoolean(KEY_CUE, true)

    /** A battery reading: percent (negative = unknown) and whether it charges. */
    data class Reading(val level: Int, val charging: Boolean)

    fun read(intent: Intent?): Reading {
        if (intent == null) return Reading(-1, false)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        // plugged in counts as charging too: a full battery on the charger reports FULL, not CHARGING
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Reading(percent, charging)
    }

    fun powerSave(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }
            .getOrDefault(false)

    /**
     * Registers [receiver] for battery changes (and, with [powerSave], the power saver switch).
     * The current battery state arrives at once as a sticky broadcast (isInitialStickyBroadcast).
     */
    fun register(context: Context, receiver: BroadcastReceiver, powerSave: Boolean) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (powerSave) filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister(context: Context, receiver: BroadcastReceiver) {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** The current battery state without registering anything. */
    fun current(context: Context): Reading =
        read(runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull())
}
