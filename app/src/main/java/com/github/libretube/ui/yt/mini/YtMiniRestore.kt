package com.github.libretube.ui.yt.mini

import android.os.SystemClock
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.util.PlayingQueue

/**
 * YT: brings back the mini player that was swiped away or closed, by a long press on the
 * "Abos" tab of the navigation bar (all designs whose bar has it, and the classic bar).
 *
 * PlayerFragment reports a close ([closed]) with the video, its position and a copy of the
 * playing queue (the closing service clears the queue). For [YtMiniRestoreRules.KEEP_MS] a long
 * press puts the queue back and starts the video at that position, opened straight into the
 * docked mini player: the page takes the path a rotated, docked page uses
 * ([YtPageStart] with docked = true), which is known to dock cleanly after attach. With motion
 * on it then rises onto the bar from behind its edge, the throw-away reversed, and lands in the
 * design's landing (YtMiniHost.armRise); the design's dock haptic plays at the long press (the
 * landing's own one is muted once, so there is exactly one).
 *
 * Kept in memory only: it survives a configuration change (this object outlives activities),
 * not the process. Offline players are not kept (their queue is built by the offline service).
 */
object YtMiniRestore {
    private class Session(
        val videoId: String,
        val startSeconds: Long,
        val queue: List<StreamItem>,
        val closedAt: Long,
    )

    private var saved: Session? = null

    // the next player page for this video starts docked
    private var dockVideoId: String? = null
    private var dockAt = 0L

    // the landing haptic of that page is already played by the long press
    private var muteDockUntil = 0L

    private const val PENDING_MS = 10_000L
    private const val MUTE_MS = 4_000L

    private fun now() = SystemClock.elapsedRealtime()

    /** The player page was closed by the user (swipe away, close button). */
    fun closed(videoId: String?, positionMs: Long, durationMs: Long, offline: Boolean) {
        if (videoId.isNullOrEmpty() || offline) {
            saved = null
            return
        }
        saved = Session(
            videoId = videoId,
            startSeconds = YtMiniRestoreRules.startSeconds(positionMs, durationMs),
            queue = PlayingQueue.getStreams(),
            closedAt = now(),
        )
    }

    /** Another video starts: the closed one is no longer what a long press brings back. */
    fun forget() {
        saved = null
    }

    /**
     * The long press on the tab. Restores the closed player and plays the haptic on [feedback],
     * or returns false (nothing saved, too old, a player is showing) so the press does what it
     * did before.
     */
    fun restore(activity: FragmentActivity, feedback: View): Boolean {
        val session = saved
        val verdict = YtMiniRestoreRules.decide(session?.closedAt, now(), playerShowing(activity))
        if (verdict == YtMiniRestoreRules.Verdict.EXPIRED) saved = null
        if (verdict != YtMiniRestoreRules.Verdict.RESTORE || session == null) return false
        saved = null

        YtHaptics.of(feedback).play(YtMiniDesigns.current().haptic(YtMoment.DOCK))
        muteDockUntil = now() + MUTE_MS

        val start = Runnable {
            if (activity.isFinishing || activity.isDestroyed || playerShowing(activity)) return@Runnable
            PlayingQueue.setStreams(session.queue)
            dockVideoId = session.videoId
            dockAt = now()
            NavigationHelper.navigateVideo(
                activity,
                PlayerData(videoId = session.videoId, keepQueue = true, timestamp = session.startSeconds),
                forceVideo = true,
            )
        }
        val delay = YtMiniRestoreRules.startDelay(session.closedAt, now())
        if (delay > 0) feedback.postDelayed(start, delay) else start.run()
        return true
    }

    /** For a new player page: whether it opens docked (it is the restored one). Asks once. */
    fun takeStartDocked(videoId: String?): Boolean {
        val pending = dockVideoId ?: return false
        dockVideoId = null
        return pending == videoId && now() - dockAt in 0..PENDING_MS
    }

    /** For the mini player's landing: whether its dock haptic was played already. Asks once. */
    fun takeDockMute(): Boolean {
        val muted = now() < muteDockUntil
        muteDockUntil = 0L
        return muted
    }

    private fun playerShowing(activity: FragmentActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.container) != null
}
