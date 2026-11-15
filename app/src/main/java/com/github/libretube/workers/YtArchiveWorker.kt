package com.github.libretube.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.libretube.ui.yt.archive.YtArchive
import com.github.libretube.ui.yt.archive.YtArchiveRunner

/**
 * YT: a run of the archive of vanished videos ([YtArchive] schedules it, [YtArchiveRunner] does
 * the work). [KEY_MODE] names the run; [KEY_VIDEO] the video of a run by hand. The runner paces
 * and limits itself; a failed run is not retried here (the next period comes).
 */
class YtArchiveWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        // switched off between scheduling and this run
        if (!YtArchive.enabled()) return Result.success()
        val mode = inputData.getString(KEY_MODE) ?: YtArchiveRunner.MODE_TEXT
        YtArchiveRunner(applicationContext).run(mode, inputData.getString(KEY_VIDEO))
        return Result.success()
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_VIDEO = "video"
    }
}
