package com.github.libretube.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.libretube.util.UpdateChecker
import com.github.libretube.util.YtUpdateNotifier
import com.github.libretube.util.YtUpdateNotifyRules.Outcome
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * YT: the periodic background check for a newer YT build (scheduled by [YtUpdateNotifier]).
 * One GitHub request per run, the same as the in-app check; a found build gets one
 * notification. A failed request is tried again at most [MAX_RETRIES] times (backoff from
 * 10 min), then the next period comes.
 */
class YtUpdateCheckWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        // switched off between scheduling and this run
        if (!YtUpdateNotifier.enabled()) return Result.success()
        val now = System.currentTimeMillis()
        val found = try {
            UpdateChecker.newerRelease()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "check failed: $e")
            YtUpdateNotifier.record(context, now, Outcome.Error(reason(e)))
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
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
        YtUpdateNotifier.record(context, now, outcome)
        return Result.success()
    }

    private fun reason(e: Exception): String =
        if (e is HttpException) "HTTP ${e.code()}" else e.javaClass.simpleName

    private companion object {
        const val TAG = "YtUpdateCheckWorker"
        const val MAX_RETRIES = 2
    }
}
