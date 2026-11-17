package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * YT: the follow-up chain of SSI's background runs (round 38). While passages wait for their
 * vectors, a finished run enqueues the next one instead of waiting for the next period. A unique
 * work cannot be kept from inside its own running worker, so the chain alternates between two
 * names; the next run is never the one that is running.
 */
object YtSsiChain {
    private const val TAG = "YtSsiChain"
    private const val NAMES = 2
    fun name(slot: Int) = "YtSsiChain$slot"
    const val KEY_SLOT = "slot"
    const val KEY_IDLE = "idle"

    /** Enqueues the next run after a run in [slot] (-1 = periodic or "Jetzt indexieren"); true when it did. */
    fun follow(context: Context, slot: Int, idleBefore: Int, backlog: Int, progress: Boolean): Boolean {
        val idle = YtSsiIndexRules.idleCount(idleBefore, progress)
        if (!YtSsiIndexRules.followUp(backlog, charging(context), batteryLow(context), hot(context), idle)) return false
        val next = if (slot >= 0) (slot + 1) % NAMES else 0
        return runCatching {
            val request = OneTimeWorkRequestBuilder<YtSsiWorker>()
                .setInitialDelay(YtSsiIndexRules.FOLLOW_UP_DELAY_MIN, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build(),
                )
                .setInputData(workDataOf(YtSsiChain.KEY_SLOT to next, KEY_IDLE to idle))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name(next), ExistingWorkPolicy.KEEP, request)
            true
        }.onFailure { Log.w(TAG, "could not chain", it) }.getOrDefault(false)
    }

    private fun battery(context: Context): Intent? =
        context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    fun charging(context: Context): Boolean = battery(context)?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false

    fun batteryLow(context: Context): Boolean {
        val i = battery(context) ?: return false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return level >= 0 && scale > 0 && level * 100 / scale < 15
    }

    fun hot(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }
}
