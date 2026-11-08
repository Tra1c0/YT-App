package com.github.libretube.helpers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
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

    /** The keys whose change can change the cap. */
    val CAP_KEYS = setOf(KEY_QUALITY, KEY_THRESHOLD, KEY_CAP, KEY_POWER_SAVE)

    /** The current battery state without registering anything. */
    fun current(context: Context): Reading =
        read(runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull())
}

/**
 * YT: whether the low-battery quality cap applies, kept current for as long as a player lives
 * ([start] to [stop]): the battery and the power saver (broadcasts) and the yt_battery_* settings
 * (preference listener). [refresh] reads the state at once, so a video that starts below the
 * threshold is capped from its first request, not after the next broadcast. [onChange] runs on
 * the main thread when [active] changes, and on every change of a cap setting (the height may
 * have changed while the answer stayed). Used by the player service (YtBatteryQualityModule) and
 * the Shorts view's own player.
 */
class YtBatteryCapWatch(private val onChange: () -> Unit) {
    var active = false
        private set

    private var app: Context? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reading = when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> YtBattery.read(intent)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> YtBattery.current(context)
                else -> return
            }
            if (evaluate(reading, YtBattery.powerSave(context))) onChange()
        }
    }

    // held here: SharedPreferences keeps its listeners only weakly
    private val prefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in YtBattery.CAP_KEYS && app != null) {
            refresh()
            onChange()
        }
    }

    /** Starts watching (once; again only after [stop]) and reads the state at once. */
    fun start(context: Context) {
        if (app != null) return
        val app = context.applicationContext
        this.app = app
        active = false
        runCatching { YtBattery.register(app, receiver, powerSave = true) }
        runCatching { PreferenceHelper.settings.registerOnSharedPreferenceChangeListener(prefs) }
        refresh()
    }

    fun stop() {
        val app = app ?: return
        this.app = null
        YtBattery.unregister(app, receiver)
        runCatching { PreferenceHelper.settings.unregisterOnSharedPreferenceChangeListener(prefs) }
        active = false
    }

    /** Reads the battery and the power saver now; true if [active] changed. No [onChange]. */
    fun refresh(): Boolean {
        val app = app ?: return false
        return evaluate(YtBattery.current(app), YtBattery.powerSave(app))
    }

    private fun evaluate(reading: YtBattery.Reading, powerSave: Boolean): Boolean {
        val now = YtBatteryCapRules.active(
            was = active,
            level = reading.level,
            charging = reading.charging,
            powerSave = powerSave,
            enabled = YtBattery.qualityEnabled,
            threshold = YtBattery.threshold,
            usePowerSave = YtBattery.usePowerSave,
        )
        if (now == active) return false
        active = now
        if (YtGuard.isEmulator) Log.i("YtBattery", "cap ${if (now) "on" else "off"} at ${reading.level} %")
        return true
    }
}
