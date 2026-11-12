package com.github.libretube.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.libretube.util.UpdateChecker
import com.github.libretube.util.YtPushRules
import com.github.libretube.util.YtUpdateNotifier
import com.github.libretube.util.YtUpdateNotifyRules.Outcome
import com.github.libretube.util.YtUpdateWatch
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * YT: the background check for a newer YT build. One GitHub request per run, the same as the
 * in-app check; a found build gets one notification. Runs in four ways ([KEY_MODE]):
 * - periodic (scheduled by [YtUpdateNotifier]): a failed request is tried again at most
 *   [MAX_RETRIES] times (backoff from 10 min), then the next period comes; with nothing new it
 *   also looks whether a build is running ([YtUpdateWatch.probe]);
 * - [MODE_PUSH]: right after a "published" push ([com.github.libretube.util.YtPush]); the push is
 *   only the trigger, this check decides. When GitHub does not show the named build yet, it is
 *   tried again a few times ([YtPushRules.retryPublished]);
 * - [MODE_SLOT]: one check of the attention mode; nothing new → the next slot;
 * - [MODE_PROBE]: only the look at the running builds (app start).
 */
class YtUpdateCheckWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val mode = inputData.getString(KEY_MODE)
        // switched off between scheduling and this run
        if (!YtUpdateNotifier.enabled()) {
            if (mode == MODE_SLOT) YtUpdateWatch.stop(context, cancel = false)
            return Result.success()
        }
        if (mode == MODE_PROBE) {
            YtUpdateWatch.probe(context)
            return Result.success()
        }
        val now = System.currentTimeMillis()
        val found = try {
            UpdateChecker.newerRelease()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "check failed: $e")
            YtUpdateNotifier.record(context, now, Outcome.Error(reason(e)), via(mode))
            return when (mode) {
                MODE_SLOT -> {
                    YtUpdateWatch.next(context)
                    Result.success()
                }
                else -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            }
        }
        val outcome = if (found == null) {
            Outcome.None
        } else {
            val (release, build) = found
            Outcome.Found(
                build,
                YtUpdateNotifier.offer(context, UpdateChecker.installedBuild(), build, release.body)
            )
        }
        YtUpdateNotifier.record(context, now, outcome, via(mode))
        if (found != null) {
            // the watched build (or a newer one) is out: no more extra checks
            YtUpdateWatch.stop(context)
            return Result.success()
        }
        when (mode) {
            MODE_SLOT -> YtUpdateWatch.next(context)
            MODE_PUSH -> {
                val expected = inputData.getInt(KEY_EXPECTED, 0).takeIf { it > 0 }
                if (YtPushRules.retryPublished(expected, UpdateChecker.installedBuild(), runAttemptCount)) {
                    return Result.retry()
                }
            }
            else -> YtUpdateWatch.probe(context)
        }
        return Result.success()
    }

    private fun via(mode: String?): String? = when (mode) {
        MODE_PUSH -> "push"
        MODE_SLOT -> "watch"
        else -> null
    }

    private fun reason(e: Exception): String =
        if (e is HttpException) "HTTP ${e.code()}" else e.javaClass.simpleName

    companion object {
        private const val TAG = "YtUpdateCheckWorker"
        private const val MAX_RETRIES = 2

        /** Input: how this run was started (absent for the periodic check). */
        const val KEY_MODE = "mode"
        const val MODE_PUSH = "push"
        const val MODE_SLOT = "slot"
        const val MODE_PROBE = "probe"

        /** Input of [MODE_PUSH]: the build the push named (0 when none). */
        const val KEY_EXPECTED = "expected"
    }
}
