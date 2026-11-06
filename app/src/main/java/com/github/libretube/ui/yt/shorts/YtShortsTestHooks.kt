package com.github.libretube.ui.yt.shorts

import android.os.Bundle
import android.util.Log
import androidx.media3.common.Player
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.yt.YtTestHandler
import com.github.libretube.ui.yt.YtTestHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * YT: the emulator start parameters of the Shorts view (scripts/gestures/shorts.sh), read by
 * YtTestHooks. Online videos do not play in the emulator, so the view opens on the test video
 * and the made-up videos of the queue test, which are downloads (`--es yt_test_video` and
 * `--ei yt_queue_test <n>` in the same intent store them).
 *
 * - `--ei yt_shorts_test <n>`: the Shorts view opens with the test video and up to n-1 made-up
 *   videos after it (all of them for 0); YtShorts "ready id=..." once the first plays.
 * - `--el yt_shorts_seek <ms>`: once it plays, the view's player seeks there (near the end, to
 *   see the countdown and the autoscroll without waiting for the whole video).
 *
 * Log tag YtShorts (with the gesture log on) and YtTest.
 */
object YtShortsTestHooks : YtTestHandler {
    private const val OPEN = "yt_shorts_test"
    private const val SEEK = "yt_shorts_seek"
    private const val FILLER_PREFIX = "ytTestQ"

    // the view's player is asked every 250 ms, for up to 40 s
    private const val POLL_MS = 250L
    private const val POLLS = 160

    override val keys: List<String> = listOf(OPEN, SEEK)

    override suspend fun test(host: AbstractPlayerHostActivity, extras: Bundle) {
        if (extras.containsKey(OPEN)) open(host, extras.getInt(OPEN, 0))
        if (extras.containsKey(SEEK)) seek(extras.getLong(SEEK, -1L))
    }

    private suspend fun open(host: AbstractPlayerHostActivity, count: Int) {
        val items = withContext(Dispatchers.IO) {
            runCatching { Database.downloadDao().getAll() }.getOrDefault(emptyList())
                .map { it.download }
                .filter { it.videoId == YtTestHooks.TEST_VIDEO_ID || it.videoId.startsWith(FILLER_PREFIX) }
                .sortedWith(compareBy({ it.videoId != YtTestHooks.TEST_VIDEO_ID }, { it.videoId }))
                .map { it.toStreamItem().copy(isShort = true) }
        }.let { if (count > 0) it.take(count) else it }
        if (items.isEmpty()) {
            Log.w("YtTest", "shorts test: no test video stored (needs --es yt_test_video)")
            return
        }
        if (host.supportFragmentManager.isStateSaved) {
            Log.w("YtTest", "shorts test: the screen is in the background")
            return
        }
        val opened = YtShortsView.open(host, items, items.first())
        Log.i("YtTest", "shorts test opened=$opened n=${items.size}")
        if (opened && awaitReady() == null) Log.w("YtTest", "shorts test: the view's player is not ready")
    }

    private suspend fun seek(ms: Long) {
        if (ms < 0) {
            Log.w("YtTest", "shorts seek: give the position in ms as a long (--el)")
            return
        }
        val player = awaitReady() ?: run {
            Log.w("YtTest", "shorts seek: no Shorts view playing")
            return
        }
        Log.i("YtTest", "shorts seek from=${player.currentPosition} to=$ms")
        player.seekTo(ms)
    }

    private suspend fun awaitReady(): Player? {
        repeat(POLLS) {
            val player = YtShortsView.current?.player?.player
            if (player != null && player.playbackState == Player.STATE_READY) return player
            delay(POLL_MS)
        }
        return null
    }
}
