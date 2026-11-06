package com.github.libretube.ui.yt

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Bundle
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
import com.github.libretube.ui.yt.feed.YtHomeTestHooks
import com.github.libretube.ui.yt.mini.YtMiniTestHooks
import com.github.libretube.ui.yt.mini.YtMinimizeTestHooks
import com.github.libretube.ui.yt.player.YtPlayerGestureTestHooks
import com.github.libretube.ui.yt.scene.YtSceneTestHooks
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * YT: start parameters of the emulator checks (scripts/crash-check.sh, scripts/gestures/), read
 * on every intent of the main screen. They work only in the emulator ([YtGuard.isEmulator]), so
 * no other app on a phone can use them. [apply] reads the shared parameters below, then hands
 * the intent's extras to each round-12 package's own [YtTestHandler]; this file stays as it is
 * when a package adds its own. A handler that needs the playing video awaits it with [awaitPlayer].
 *
 * - `--es yt_test_video <path>`: the local test video (scripts/fixtures/yt-test.mp4, which the
 *   check puts into the app's files) becomes a download with chapters and opens as the
 *   downloads screen opens one. Online videos do not play in the emulator.
 * - `--ez yt_gesture_log true`: the gesture code logs what it does ([log]).
 * - `--ei yt_queue_test <n>`: n made-up videos in the queue after the test video, each a
 *   download of its own that plays the test video's file. With the test video in the same
 *   intent, or while it plays; a test video opened without it gets an empty queue again.
 * - `--el yt_seek_test <ms>`: the player seeks there once it is ready.
 * - `--ez yt_audio_test true`: after that, the video player switches to the audio player (as
 *   its audio button does), so a queue's end can be checked in audio mode (scripts/gestures/f.sh).
 *
 * Log tag YtTest (queue: YtQueue), for crash-check.sh to show and the checks to grep; "test
 * hooks done" once everything an intent asked for ran.
 */
object YtTestHooks {
    private const val TAG = "YtTest"

    /** The test video's id in the downloads. */
    const val TEST_VIDEO_ID = "ytTestVid01"

    // the made-up videos of the queue test: ids ytTestQ0001 and on (11 characters, as a
    // YouTube id), each a link to the test video in a folder of their own
    private const val FILLER_PREFIX = "ytTestQ"
    private const val FILLER_DIR = "yt-test-queue"
    private const val MAX_FILLERS = 50

    // what the test video holds (180 s), in case its length cannot be read
    private const val TEST_VIDEO_SECONDS = 180L

    // the chapters as (start in seconds, name); fixed row ids, so a second start replaces them
    // instead of adding more (the chapters table has no link to the download that would clean up)
    private val TEST_CHAPTERS = listOf(0L to "Intro", 45L to "Teil 1", 90L to "Teil 2", 135L to "Ende")
    private const val TEST_CHAPTER_ID = 9_000_000_000L

    // the player is looked for every 250 ms, for up to a minute (a cold start in the emulator is slow)
    private const val POLL_MS = 250L
    private const val POLLS = 240

    // the test video gets a second chance after 20 s, then 30 s more (the check waits a minute)
    private const val FIRST_POLLS = 80
    private const val RETRY_POLLS = 120

    private const val EXTRA_TEST_VIDEO = "yt_test_video"
    private const val EXTRA_QUEUE_TEST = "yt_queue_test"
    private const val EXTRA_SEEK_TEST = "yt_seek_test"
    private const val EXTRA_AUDIO_TEST = "yt_audio_test"

    // the packages' own parameters, one object each in its owner's folder (A, B, C, D, E)
    private val packages: List<YtTestHandler> = listOf(
        YtMiniTestHooks,
        YtMinimizeTestHooks,
        YtPlayerGestureTestHooks,
        YtHomeTestHooks,
        YtSceneTestHooks,
    )

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
        val testVideo = intent.getStringExtra(EXTRA_TEST_VIDEO)
        val queueSize = if (intent.hasExtra(EXTRA_QUEUE_TEST)) {
            intent.getIntExtra(EXTRA_QUEUE_TEST, 0).coerceIn(0, MAX_FILLERS)
        } else {
            null
        }
        val seekMs = if (intent.hasExtra(EXTRA_SEEK_TEST)) intent.getLongExtra(EXTRA_SEEK_TEST, -1L) else null
        val audio = intent.getBooleanExtra(EXTRA_AUDIO_TEST, false)
        val asked = packages.filter { handler -> handler.keys.any(intent::hasExtra) }
        // a copy, taken before the extras go
        val extras = intent.extras ?: Bundle()
        (listOf(EXTRA_TEST_VIDEO, EXTRA_QUEUE_TEST, EXTRA_SEEK_TEST, EXTRA_AUDIO_TEST) + packages.flatMap { it.keys })
            .forEach(intent::removeExtra)
        if (testVideo == null && queueSize == null && seekMs == null && !audio && asked.isEmpty()) return

        // one after the other: what follows the test video works on it once it plays
        host.lifecycleScope.launch {
            val fillers = when {
                testVideo != null -> openTestVideo(host, testVideo, queueSize ?: 0) ?: return@launch
                queueSize != null -> fillersForPlaying(host, queueSize)
                else -> null
            }
            if (fillers != null) fillQueue(fillers)
            if (seekMs != null) seekTest(host, seekMs)
            if (audio) audioTest(host)
            asked.forEach { it.test(host, extras) }
            Log.i(TAG, "test hooks done")
        }
    }

    /**
     * Stores the test video as a download, with [fillerCount] made-up videos for the queue, and
     * opens it as the downloads screen opens one (DownloadsAdapter). Returns the fillers' queue
     * items once the player has the video ready, null if it did not open: opening a downloaded
     * video empties the queue and starts anew, which would undo a queue filled or a seek made
     * earlier.
     */
    private suspend fun openTestVideo(
        host: AbstractPlayerHostActivity,
        path: String,
        fillerCount: Int
    ): List<StreamItem>? {
        val file = File(path)
        val stored = withContext(Dispatchers.IO) {
            runCatching { storeTestVideo(host, file) }
                .onFailure { Log.w(TAG, "test video not stored", it) }
                .getOrDefault(false)
        }
        if (!stored) return null
        // before the video opens: a new offline player puts every downloaded video into the queue
        val fillers = withContext(Dispatchers.IO) {
            runCatching { storeFillers(host, file, fillerCount) }
                .onFailure { Log.w(TAG, "queue test: the made-up videos not stored", it) }
                .getOrDefault(emptyList())
        }
        // the screen went to the background meanwhile; a fragment change would throw now
        if (host.supportFragmentManager.isStateSaved) {
            Log.w(TAG, "test video stored, not opened: the screen is in the background")
            return null
        }
        // every start of a video gets a new track number (setMetadata), even the same video again
        val before = currentPlayer(host)?.mediaMetadata?.trackNumber
        NavigationHelper.navigateVideo(
            host,
            PlayerData(videoId = TEST_VIDEO_ID, downloadTab = DownloadTab.VIDEO, isOffline = true)
        )
        val fresh: (Player) -> Boolean = { playingId(it) == TEST_VIDEO_ID && it.mediaMetadata.trackNumber != before }
        var player = awaitPlayer(host, FIRST_POLLS, fresh)
        if (player == null) {
            // the emulator's video decoder sometimes stalls at a start (h264 errors in the log):
            // the stalled player prepares and seeks again, a player that never came opens again
            val stuck = currentPlayer(host)
            Log.i(
                TAG,
                "retry: the video is not ready after ${POLL_MS * FIRST_POLLS / 1000} s " +
                    "(state=${stuck?.playbackState}, error=${stuck?.playerError?.errorCode})"
            )
            if (stuck != null && fresh(stuck)) {
                stuck.prepare()
                stuck.seekTo(stuck.currentPosition.coerceAtLeast(0L))
                stuck.play()
            } else if (!host.supportFragmentManager.isStateSaved) {
                NavigationHelper.navigateVideo(
                    host,
                    PlayerData(videoId = TEST_VIDEO_ID, downloadTab = DownloadTab.VIDEO, isOffline = true)
                )
            }
            player = awaitPlayer(host, RETRY_POLLS, fresh)
        }
        if (player == null) {
            Log.w(TAG, "test video opened, but not ready after ${POLL_MS * (FIRST_POLLS + RETRY_POLLS) / 1000} s")
            return null
        }
        Log.i(TAG, "test video $TEST_VIDEO_ID playing from $path, ${player.duration} ms")
        return fillers
    }

    /**
     * The queue test without the test video in the same intent: only on top of the test video
     * playing already, since the made-up videos are downloads (an online queue must not get
     * offline videos). Null if it does not play.
     */
    private suspend fun fillersForPlaying(host: AbstractPlayerHostActivity, count: Int): List<StreamItem>? {
        val playing = awaitPlayer(host)?.let { playingId(it) }
        if (playing != TEST_VIDEO_ID) {
            Log.w(
                TAG,
                "queue test: needs the test video playing (--es $EXTRA_TEST_VIDEO), playing: ${playing ?: "nothing"}"
            )
            return null
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val video = Database.downloadDao().findById(TEST_VIDEO_ID)?.downloadItems
                    ?.firstOrNull { it.type == FileType.VIDEO }?.path?.toFile()
                    ?: error("the test video has no file")
                storeFillers(host, video, count)
            }
                .onFailure { Log.w(TAG, "queue test: the made-up videos not stored", it) }
                .getOrNull()
        }
    }

    private fun playingId(player: Player): String? =
        player.mediaMetadata.extras?.getString(IntentData.videoId)

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

    // the video player hands over to the audio player, as its audio button does
    private fun audioTest(host: AbstractPlayerHostActivity) {
        if (host.supportFragmentManager.isStateSaved) {
            Log.w(TAG, "audio test: the screen is in the background")
            return
        }
        val switched = host.runOnPlayerFragment {
            if (view == null) return@runOnPlayerFragment false
            switchToAudioMode()
            true
        }
        Log.i(TAG, "audio test switched=$switched")
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

    /**
     * Exactly [count] made-up videos as finished downloads, "Testvideo 1..n" of "Kanal Test",
     * 60 to 300 s, and their queue items. The queue tells its videos apart only by id, so each
     * has its own; the downloads' paths are unique, so each has its own file, a link to the test
     * video (a copy where no link can be made). Those of an earlier start go first.
     */
    private suspend fun storeFillers(context: Context, video: File, count: Int): List<StreamItem> {
        val dao = Database.downloadDao()
        dao.getAll().filter { it.download.videoId.startsWith(FILLER_PREFIX) }.forEach { old ->
            old.downloadItems.forEach { dao.deleteDownloadItemById(it.id) }
            dao.deleteDownload(old.download)
        }
        val dir = File(context.filesDir, FILLER_DIR)
        dir.listFiles()?.forEach { it.delete() }
        if (count <= 0) return emptyList()
        dir.mkdirs()

        val target = video.absoluteFile
        val size = target.length()
        val items = (1..count).mapNotNull { i ->
            val id = FILLER_PREFIX + i.toString().padStart(4, '0')
            val file = File(dir, "$id.mp4")
            if (!linkOrCopy(target, file)) return@mapNotNull null
            val download = Download(
                videoId = id,
                title = "Testvideo $i",
                uploader = "Kanal Test",
                // 60 to 300 s, spread evenly (the file itself is the test video's length)
                duration = 60L + if (count > 1) 240L * (i - 1) / (count - 1) else 0L
            )
            dao.insertDownload(download)
            dao.insertDownloadItem(
                DownloadItem(
                    type = FileType.VIDEO,
                    videoId = id,
                    fileName = file.name,
                    path = file.toPath(),
                    format = "MPEG_4",
                    quality = "360p",
                    downloadSize = size
                )
            )
            download.toStreamItem()
        }
        Log.i(TAG, "queue test: ${items.size} made-up videos stored")
        return items
    }

    // a symbolic link to the test video, or a copy of it where links are not allowed
    private fun linkOrCopy(target: File, file: File): Boolean = try {
        Files.createSymbolicLink(file.toPath(), target.toPath())
        true
    } catch (e: Exception) {
        runCatching { target.copyTo(file, overwrite = true) }
            .onFailure { Log.w(TAG, "queue test: no link and no copy at $file", it) }
            .isSuccess
    }

    // the queue as the check asked: what is there (the test video) without earlier made-up
    // videos, then the new ones in their order
    private fun fillQueue(fillers: List<StreamItem>) {
        val kept = PlayingQueue.getStreams().filterNot { it.url?.startsWith(FILLER_PREFIX) == true }
        PlayingQueue.setStreams(kept + fillers)
        if (fillers.isNotEmpty()) Log.i("YtQueue", "test fill n=${fillers.size}")
    }

    /**
     * The video player once it is ready and [accept]s, or null after a minute. Asked every
     * [POLL_MS]: a video that just opened takes a while, and a listener would have to follow
     * the player fragment being replaced meanwhile. For the handlers in [apply]'s coroutine.
     */
    suspend fun awaitPlayer(
        host: AbstractPlayerHostActivity,
        polls: Int = POLLS,
        accept: (Player) -> Boolean = { true }
    ): Player? {
        repeat(polls) {
            val player = currentPlayer(host)
            if (player != null && player.playbackState == Player.STATE_READY && accept(player)) return player
            delay(POLL_MS)
        }
        return null
    }

    /** The video player's controller, through its view (null while there is no player view). */
    fun currentPlayer(host: AbstractPlayerHostActivity): Player? {
        var found: Player? = null
        host.runOnPlayerFragment {
            // the binding exists only while the fragment has its view, and not after a failed
            // attach either: NavigationHelper.navigateVideo then calls onDestroy by hand, which
            // drops the binding while the view stays until the fragment is replaced
            if (view != null) found = runCatching { binding.player.player }.getOrNull()
            true
        }
        return found
    }
}

/**
 * YT: the emulator start parameters of one round-12 package, which [YtTestHooks.apply] reads
 * after the shared ones. Each package has one object in its own folder, listed in [YtTestHooks];
 * every hook does nothing by default, so filling one changes nothing outside the package.
 */
interface YtTestHandler {
    /** The extras this package reads; [YtTestHooks.apply] takes them off the intent once read. */
    val keys: List<String> get() = emptyList()

    /**
     * Runs once for an intent that has one of [keys], in [YtTestHooks.apply]'s coroutine after
     * the shared parameters: with the test video once it plays, not at all if it did not open.
     * [extras] are the intent's extras as they came, the shared ones too.
     */
    suspend fun test(host: AbstractPlayerHostActivity, extras: Bundle) {}
}
