package com.github.libretube.util

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.util.YtUpdateWatchRules.Run
import com.github.libretube.workers.YtUpdateCheckWorker
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * YT: the "attention mode". When YT learns that a build of main runs on GitHub (a "started"
 * push, or a look at the Actions API during a normal check or at app start), it checks for the
 * release a few times shortly after the build should be out ([YtUpdateWatchRules.SLOT_MINUTES]),
 * one one-time WorkManager job at a time (unique name [WORK_NAME]). The watch ends when a newer
 * build is found, after the last slot, or when the update notification is switched off.
 * No notification of its own: a found build gets the usual one from [YtUpdateNotifier].
 */
object YtUpdateWatch {
    private const val TAG = "YtUpdateWatch"
    const val WORK_NAME = "YtUpdateWatch"
    private const val PROBE_WORK_NAME = "YtUpdateProbe"

    private const val PREFS = "yt_update_watch"
    private const val KEY_BUILD = "build"
    private const val KEY_START = "start"
    private const val KEY_SLOT = "slot"
    private const val KEY_REASON = "reason"
    private const val KEY_LAST_PROBE = "last_probe"
    private const val KEY_PROBE_RESULT = "probe_result"

    /**
     * Watches the build [build] (null when unknown) that started at [runStart]; [reason] is for
     * the diagnosis page ("push", "actions"). Does nothing when the notification is off, the
     * build is not news, or a watch for the same or a newer build runs already. True when the
     * watch (re)started.
     */
    fun start(context: Context, build: Int?, runStart: Long, reason: String): Boolean {
        val app = context.applicationContext
        if (!YtUpdateNotifier.enabled()) return false
        if (!YtPushRules.isNews(build, UpdateChecker.installedBuild(), YtUpdateNotifier.lastNotified(app))) return false
        val now = System.currentTimeMillis()
        val prefs = prefs(app)
        val activeStart = prefs.getLong(KEY_START, 0).takeIf { it > 0 }
        val activeBuild = prefs.getInt(KEY_BUILD, 0).takeIf { it > 0 }
        if (!YtUpdateWatchRules.replaces(build, activeBuild, activeStart, now)) return false
        val slot = YtUpdateWatchRules.nextSlot(runStart, now) ?: return false
        prefs.edit {
            putLong(KEY_START, runStart)
            if (build != null) putInt(KEY_BUILD, build) else remove(KEY_BUILD)
            putLong(KEY_SLOT, slot)
            putString(KEY_REASON, reason)
        }
        enqueue(app, slot, now, ExistingWorkPolicy.REPLACE)
        return true
    }

    /**
     * A slot ran and found nothing: the next slot is scheduled, or the watch ends after the last.
     * Called from inside the running slot job, so the next one is appended to it.
     */
    fun next(context: Context) {
        val app = context.applicationContext
        val prefs = prefs(app)
        val start = prefs.getLong(KEY_START, 0).takeIf { it > 0 } ?: return stop(app, cancel = false)
        val now = System.currentTimeMillis()
        val after = prefs.getLong(KEY_SLOT, 0).takeIf { it > 0 }
        val slot = YtUpdateWatchRules.nextSlot(start, now, after)
        if (slot == null || !YtUpdateNotifier.enabled()) return stop(app, cancel = false)
        prefs.edit { putLong(KEY_SLOT, slot) }
        enqueue(app, slot, now, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    /** Ends the watch (a newer build was found, the last slot passed, or switched off). */
    fun stop(context: Context, cancel: Boolean = true) {
        val app = context.applicationContext
        runCatching {
            if (cancel) WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            prefs(app).edit { remove(KEY_START).remove(KEY_BUILD).remove(KEY_SLOT).remove(KEY_REASON) }
        }
    }

    fun active(context: Context): Boolean = prefs(context).getLong(KEY_START, 0) > 0

    private fun enqueue(context: Context, slot: Long, now: Long, policy: ExistingWorkPolicy) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<YtUpdateCheckWorker>()
                .setInputData(workDataOf(YtUpdateCheckWorker.KEY_MODE to YtUpdateCheckWorker.MODE_SLOT))
                .setInitialDelay(YtUpdateWatchRules.delay(slot, now), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }.onFailure { Log.w(TAG, "could not schedule a watch slot", it) }
    }

    /**
     * App start: a look at the running builds in the background, at most every
     * [YtUpdateWatchRules.PROBE_GAP_MS] (no network on the calling thread).
     */
    fun probeSoon(context: Context) {
        val app = context.applicationContext
        if (!YtUpdateNotifier.enabled()) return
        if (!YtUpdateWatchRules.mayProbe(prefs(app).getLong(KEY_LAST_PROBE, 0), System.currentTimeMillis())) return
        runCatching {
            val request = OneTimeWorkRequestBuilder<YtUpdateCheckWorker>()
                .setInputData(workDataOf(YtUpdateCheckWorker.KEY_MODE to YtUpdateCheckWorker.MODE_PROBE))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(PROBE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "could not schedule the look at running builds", it) }
    }

    /**
     * Looks at the latest runs of the build workflow on main (one request, at most every
     * [YtUpdateWatchRules.PROBE_GAP_MS]) and starts the watch for one that is running. Never
     * throws; called after a check found no newer build.
     */
    suspend fun probe(context: Context) {
        val app = context.applicationContext
        if (!YtUpdateNotifier.enabled()) return
        val prefs = prefs(app)
        val now = System.currentTimeMillis()
        if (!YtUpdateWatchRules.mayProbe(prefs.getLong(KEY_LAST_PROBE, 0), now)) return
        prefs.edit { putLong(KEY_LAST_PROBE, now) }
        val result = try {
            val runs = RetrofitInstance.externalApi.getBuildRuns().runs.map {
                Run(
                    it.runNumber,
                    it.status,
                    it.headBranch,
                    YtUpdateWatchRules.parseTime(it.runStartedAt) ?: YtUpdateWatchRules.parseTime(it.createdAt)
                )
            }
            val run = YtUpdateWatchRules.pickRun(runs, UpdateChecker.installedBuild(), YtUpdateNotifier.lastNotified(app), now)
            when {
                run == null -> "no build running"
                start(app, run.number, run.startedAt ?: now, "actions") -> "build ${run.number} running, watching"
                else -> "build ${run.number} running"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "error: ${if (e is retrofit2.HttpException) "HTTP ${e.code()}" else e.javaClass.simpleName}"
        }
        prefs.edit { putString(KEY_PROBE_RESULT, result) }
    }

    /** The diagnosis line: the watch (build, next slot, why) and the last look at the runs. */
    fun diagnosis(context: Context): String {
        val prefs = prefs(context)
        val start = prefs.getLong(KEY_START, 0).takeIf { it > 0 }
        val watch = if (start == null) {
            "idle"
        } else {
            val build = prefs.getInt(KEY_BUILD, 0).takeIf { it > 0 }?.toString() ?: "?"
            val slot = prefs.getLong(KEY_SLOT, 0).takeIf { it > 0 }?.let(YtCrashLog::formatTime) ?: "?"
            "build $build (${prefs.getString(KEY_REASON, "?")}), next check ≈ $slot"
        }
        val probe = prefs.getLong(KEY_LAST_PROBE, 0).takeIf { it > 0 }?.let {
            "last look ${YtCrashLog.formatTime(it)}: ${prefs.getString(KEY_PROBE_RESULT, "-")}"
        } ?: "no look yet"
        return "$watch; $probe"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
