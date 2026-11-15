package com.github.libretube.services

import android.os.Bundle
import android.util.Log
import androidx.annotation.StringRes
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.extensions.toID
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.YtStreamError
import com.github.libretube.util.YtStreamError.Kind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * YT: a video whose streams could not be loaded (age limit, sign-in block, private, …).
 * The online service tells the page through the player's playlist metadata, the channel that
 * already carries the video id of a new load: the extras get [KEY_KIND] (and [KEY_DETAIL], the
 * extractor's message). A load that starts again sends the video id without them, which ends
 * the error state. The page module (YtStreamErrorModule) shows it; with no page in sight the
 * service shows a short toast instead. Main thread only.
 */
object YtStreamFailure {
    const val KEY_KIND = "yt_stream_error"
    const val KEY_DETAIL = "yt_stream_error_detail"

    /** Player pages in sight (started); counted by YtStreamErrorModule. */
    private var pagesShowing = 0

    fun pageShown() {
        pagesShowing++
    }

    fun pageHidden() {
        pagesShowing = (pagesShowing - 1).coerceAtLeast(0)
    }

    /** True if a player page can show the error itself (then no toast). */
    val pageShowing: Boolean get() = pagesShowing > 0

    /** The failure the playlist metadata [extras] tell, or null for a normal load. */
    fun kindOf(extras: Bundle?): Kind? =
        extras?.getString(KEY_KIND)?.let { name -> Kind.entries.firstOrNull { it.name == name } }

    @StringRes
    fun titleRes(kind: Kind): Int = when (kind) {
        Kind.AGE_RESTRICTED -> R.string.yt_stream_error_age_title
        Kind.MEMBERS_ONLY -> R.string.yt_stream_error_members_title
        Kind.PRIVATE -> R.string.yt_stream_error_private_title
        Kind.BOT_CHECK -> R.string.yt_stream_error_bot_title
        Kind.SIGN_IN_REQUIRED -> R.string.yt_stream_error_signin_title
        Kind.GEO -> R.string.yt_stream_error_geo_title
        Kind.PAID -> R.string.yt_stream_error_paid_title
        Kind.TERMINATED -> R.string.yt_stream_error_terminated_title
        Kind.UNAVAILABLE -> R.string.yt_stream_error_unavailable_title
        Kind.NETWORK -> R.string.yt_stream_error_network_title
        Kind.OTHER -> R.string.yt_stream_error_other_title
    }

    @StringRes
    fun bodyRes(kind: Kind): Int = when (kind) {
        Kind.AGE_RESTRICTED -> R.string.yt_stream_error_age_body
        Kind.MEMBERS_ONLY -> R.string.yt_stream_error_members_body
        Kind.PRIVATE -> R.string.yt_stream_error_private_body
        Kind.BOT_CHECK -> R.string.yt_stream_error_bot_body
        Kind.SIGN_IN_REQUIRED -> R.string.yt_stream_error_signin_body
        Kind.GEO -> R.string.yt_stream_error_geo_body
        Kind.PAID -> R.string.yt_stream_error_paid_body
        Kind.TERMINATED -> R.string.yt_stream_error_terminated_body
        Kind.UNAVAILABLE -> R.string.yt_stream_error_unavailable_body
        Kind.NETWORK -> R.string.yt_stream_error_network_body
        Kind.OTHER -> R.string.yt_stream_error_other_body
    }

    /**
     * The queue as it was before a fresh player start cleared it ([take]); put back by [failed]
     * if the first video fails and nothing else filled the queue meanwhile.
     */
    class QueueBefore private constructor(
        private val items: List<StreamItem>,
        private val current: StreamItem?,
    ) {
        fun restoreIfEmpty() {
            if (items.isEmpty() || !PlayingQueue.isEmpty()) return
            PlayingQueue.setStreams(items)
            current?.let { PlayingQueue.updateCurrent(it) }
        }

        companion object {
            fun take() = QueueBefore(PlayingQueue.getStreams(), PlayingQueue.getCurrent())
        }
    }

    /**
     * A video gone for everyone ([YtStreamError.removesFromFeed]) leaves the cached subscription
     * feed (home, Abos, News read it), and its later copies leave the queue; the current item
     * stays while its page shows the error and goes with [leave]. Watch history and positions
     * stay; an archived copy is marked vanished and kept (YtArchive.onVanished). Main thread.
     */
    fun forget(videoId: String, kind: Kind) {
        if (!YtStreamError.removesFromFeed(kind)) return
        synchronized(this) { leaving = videoId }
        val ids = PlayingQueue.getStreams().map { it.url?.toID() }
        YtStreamError.upcomingCopies(ids, PlayingQueue.currentIndex(), videoId).forEach { PlayingQueue.remove(it) }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { DatabaseHolder.Database.feedDao().ytDeleteVideo(videoId) }
                .onFailure { Log.w("YtStreamFailure", "feed cleanup failed: $it") }
        }
        // an archived copy is kept for good from now on (round 31)
        com.github.libretube.ui.yt.archive.YtArchive.onVanished(com.github.libretube.LibreTubeApp.instance, videoId, kind)
    }

    /** The failed video that leaves the queue once another one is picked. */
    private var leaving: String? = null

    /** Another video ([nextId]) starts: a video that was gone for everyone leaves the queue now. */
    fun leave(nextId: String) {
        val gone = synchronized(this) { leaving.also { if (it != nextId) leaving = null } } ?: return
        if (gone == nextId) return
        val at = PlayingQueue.getStreams().indexOfFirst { it.url?.toID() == gone }
        if (at >= 0) PlayingQueue.remove(at)
    }

    /**
     * The streams of [videoId] could not be loaded ([error]). Keeps the queue (the previous one
     * if a fresh start had cleared it; the failed video becomes the current item if it is in the
     * queue, so "next" goes on after it), and returns the extras for the playlist metadata.
     */
    fun failed(videoId: String, error: Throwable, queueBefore: QueueBefore?): Bundle {
        val kind = YtStreamError.classify(error)
        queueBefore?.restoreIfEmpty()
        PlayingQueue.getStreams().firstOrNull { it.url?.toID() == videoId }?.let { PlayingQueue.updateCurrent(it) }
        return Bundle().apply {
            putString(KEY_KIND, kind.name)
            putString(KEY_DETAIL, error.localizedMessage.orEmpty().take(300))
        }
    }
}
