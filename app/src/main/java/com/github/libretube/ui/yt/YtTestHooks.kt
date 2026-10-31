package com.github.libretube.ui.yt

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadChapter
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.enums.FileType
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.YtGuard
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.fragments.DownloadTab
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * YT: start parameters of the emulator checks (scripts/crash-check.sh, scripts/gestures/), read
 * on every intent of the main screen. They work only in the emulator ([YtGuard.isEmulator]), so
 * no other app on a phone can use them. [apply] only reads the parameters and runs their
 * handlers one after the other (the packages of round 12 add theirs there, a line or two each);
 * a handler that needs the playing video awaits it with [awaitPlayer].
 *
 * - `--es yt_test_video <path>`: the local test video (scripts/fixtures/yt-test.mp4, which the
 *   check puts into the app's files) becomes a download with chapters and opens as the
 *   downloads screen opens one. Online videos do not play in the emulator.
 * - `--ez yt_gesture_log true`: the gesture code logs what it does ([log]).
 * - `--ei yt_queue_test <n>`: n made-up videos in the queue, all playing the test video.
 * - `--el yt_seek_test <ms>`: the player seeks there once it is ready.
 *
 * Log tag YtTest (queue: YtQueue), for crash-check.sh to show and the checks to grep.
 */
object YtTestHooks {
    private const val TAG = "YtTest"

    /** The test video's id in the downloads. */
    const val TEST_VIDEO_ID = "ytTestVid01"

    // what the test video holds (180 s), in case its length cannot be read
    private const val TEST_VIDEO_SECONDS = 180L

    // the chapters as (start in seconds, name); fixed row ids, so a second start replaces them
    // instead of adding more (the chapters table has no link to the download that would clean up)
    private val TEST_CHAPTERS = listOf(0L to "Intro", 45L to "Teil 1", 90L to "Teil 2", 135L to "Ende")
    private const val TEST_CHAPTER_ID = 9_000_000_000L

    // the player is looked for every 250 ms, for up to a minute (a cold start in the emulator is slow)
    private const val POLL_MS = 250L
    private const val POLLS = 240

    /**
     * Whether the gesture code logs what it does (tags YtGesture, YtMini, YtQueue, YtScrub,
     * YtScene, YtHaptic), so the checks can compare it with their screenshots. Set by
     * `--ez yt_gesture_log true`; it stays for the app's process. Always off on a phone.
     */
    var gestureLog = false

    /** A line for the checks' log, written only while [gestureLog] is on. */
    inline fun log(tag: String, message: () -> String) {
        if (gestureLog) Log.i(tag, message())
    }

    fun apply(activity: Activity, intent: Intent) {
        if (!YtGuard.isEmulator) return
        if (intent.hasExtra("yt_gesture_log")) {
            gestureLog = intent.getBooleanExtra("yt_gesture_log", false)
        }
        val host = activity as? AbstractPlayerHostActivity ?: return

        // each of these once per intent: a recreated screen (rotation, dark mode) gets the same
        // intent again and would open the video anew
        val testVideo = intent.getStringExtra("yt_test_video")
        val queueSize = intent.getIntExtra("yt_queue_test", 0)
        val seekMs = if (intent.hasExtra("yt_seek_test")) intent.getLongExtra("yt_seek_test", -1L) else null
        listOf("yt_test_video", "yt_queue_test", "yt_seek_test").forEach(intent::removeExtra)
        if (testVideo == null && queueSize <= 0 && seekMs == null) return

        // one after the other: what follows the test video works on it once it plays
        host.lifecycleScope.launch {
            if (testVideo != null && !openTestVideo(host, testVideo)) return@launch
            if (queueSize > 0) fillQueue(queueSize)
            if (seekMs != null) seekTest(host, seekMs)
        }
    }

    /**
     * Stores the test video as a download and opens it as the downloads screen opens one
     * (DownloadsAdapter). Returns once the player has it ready: opening a downloaded video
     * empties the queue and starts anew, which would undo a queue filled or a seek made earlier.
     */
    private suspend fun openTestVideo(host: AbstractPlayerHostActivity, path: String): Boolean {
        val stored = withContext(Dispatchers.IO) {
            runCatching { storeTestVideo(host, File(path)) }
                .onFailure { Log.w(TAG, "test video not stored", it) }
                .getOrDefault(false)
        }
        if (!stored) return false
        // the screen went to the background meanwhile; a fragment change would throw now
        if (host.supportFragmentManager.isStateSaved) {
            Log.w(TAG, "test video stored, not opened: the screen is in the background")
            return false
        }
        // every start of a video gets a new track number (setMetadata), even the same video again
        val before = currentPlayer(host)?.mediaMetadata?.trackNumber
        NavigationHelper.navigateVideo(
            host,
            PlayerData(videoId = TEST_VIDEO_ID, downloadTab = DownloadTab.VIDEO, isOffline = true)
        )
        val player = awaitPlayer(host) {
            it.mediaMetadata.extras?.getString(IntentData.videoId) == TEST_VIDEO_ID &&
                it.mediaMetadata.trackNumber != before
        }
        if (player == null) {
            Log.w(TAG, "test video opened, but not ready after ${POLL_MS * POLLS / 1000} s")
            return false
        }
        Log.i(TAG, "test video $TEST_VIDEO_ID playing from $path, ${player.duration} ms")
        return true
    }

    private suspend fun seekTest(host: AbstractPlayerHostActivity, ms: Long) {
        if (ms < 0) {
            Log.w(TAG, "seek test: give the position in ms as a long (--el)")
            return
        }
        val player = awaitPlayer(host) ?: run {
            Log.w(TAG, "seek test: no ready player after ${POLL_MS * POLLS / 1000} s")
            return
        }
        Log.i(TAG, "seek test from=${player.currentPosition} to=$ms")
        player.seekTo(ms)
    }

    /**
     * The test video as a finished download: the rows the download service writes (the video,
     * its file, its chapters), so the offline player, the downloads list and the chapters work
     * as for a real one. False if the file is not the app's own or cannot be read.
     */
    private suspend fun storeTestVideo(context: Context, file: File): Boolean {
        // only a file in the app's own storage: the path comes from outside the app
        val canonical = file.canonicalPath
        val own = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
            .any { canonical.startsWith(it.canonicalPath + File.separator) }
        if (!own || !file.canRead()) {
            Log.w(TAG, "test video not usable (own=$own, readable=${file.canRead()}): $file")
            return false
        }
        val dao = Database.downloadDao()
        val path = file.toPath()
        val size = file.length()

        val existing = dao.findById(TEST_VIDEO_ID)
        val item = existing?.downloadItems?.singleOrNull()
        val complete = existing != null && item != null && item.type == FileType.VIDEO && item.path == path &&
            item.downloadSize == size && existing.downloadChapters.size == TEST_CHAPTERS.size
        if (complete) {
            Log.i(TAG, "test video already stored")
            return true
        }
        // a file from an earlier start elsewhere would be played instead (the first video file wins)
        existing?.downloadItems?.forEach { dao.deleteDownloadItemById(it.id) }

        val seconds = durationSeconds(file)
        dao.insertDownload(
            Download(
                videoId = TEST_VIDEO_ID,
                title = "YT Testvideo",
                description = "Testvideo der Emulator-Prüfung: Bildnummer und Zeit im Bild, 220-Hz-Ton.",
                uploader = "Kanal Test",
                duration = seconds
            )
        )
        dao.insertDownloadItem(
            DownloadItem(
                type = FileType.VIDEO,
                videoId = TEST_VIDEO_ID,
                fileName = file.name,
                path = path,
                // as NewPipe names the format of a real download
                format = "MPEG_4",
                quality = "360p",
                // the full size marks the download as finished
                downloadSize = size
            )
        )
        TEST_CHAPTERS.forEachIndexed { index, (start, name) ->
            dao.insertDownloadChapter(
                DownloadChapter(
                    id = TEST_CHAPTER_ID + index,
                    videoId = TEST_VIDEO_ID,
                    name = name,
                    start = start,
                    thumbnailUrl = ""
                )
            )
        }
        Log.i(TAG, "test video stored: $seconds s, $size bytes, ${TEST_CHAPTERS.size} chapters")
        return true
    }

    // the length in whole seconds, as the download service stores it
    private fun durationSeconds(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.div(1000)?.takeIf { it > 0 } ?: TEST_VIDEO_SECONDS
        } catch (e: Exception) {
            TEST_VIDEO_SECONDS
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun fillQueue(count: Int) {
        val n = count.coerceIn(1, 50)
        val items = (1..n).map { i ->
            StreamItem(
                // the bare id, as the downloads put their videos into the queue
                url = TEST_VIDEO_ID,
                type = StreamItem.TYPE_STREAM,
                title = "Testvideo $i",
                uploaderName = "Kanal Test",
                // 60 to 300 s, spread evenly
                duration = 60L + if (n > 1) 240L * (i - 1) / (n - 1) else 0L
            )
        }
        PlayingQueue.add(*items.toTypedArray())
        Log.i("YtQueue", "test fill n=$n")
    }

    /**
     * The video player once it is ready and [accept]s, or null after a minute. Asked every
     * [POLL_MS]: a video that just opened takes a while, and a listener would have to follow
     * the player fragment being replaced meanwhile. For the handlers in [apply]'s coroutine.
     */
    suspend fun awaitPlayer(
        host: AbstractPlayerHostActivity,
        accept: (Player) -> Boolean = { true }
    ): Player? {
        repeat(POLLS) {
            val player = currentPlayer(host)
            if (player != null && player.playbackState == Player.STATE_READY && accept(player)) return player
            delay(POLL_MS)
        }
        return null
    }

    // the video player's controller, through its view (null while there is no player view)
    fun currentPlayer(host: AbstractPlayerHostActivity): Player? {
        var found: Player? = null
        host.runOnPlayerFragment {
            // the binding exists only while the fragment has its view
            if (view != null) found = binding.player.player
            true
        }
        return found
    }
}
