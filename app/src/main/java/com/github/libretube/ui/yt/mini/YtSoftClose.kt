package com.github.libretube.ui.yt.mini

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.os.bundleOf
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.services.OnlinePlayerService
import com.github.libretube.services.YtServiceModules
import com.github.libretube.services.YtServiceSoftClose
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.util.PlayingQueue
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/**
 * YT: the soft close. A mini player that is swiped away or closed does not end its player at
 * once: for [YtSoftCloseRules.HOLD_MS] the service keeps it paused, with its buffer, decoder and
 * queue, out of sight (no notification, deaf to outside controllers: YtServiceSoftClose). A long
 * press on "Abos" in that minute (YtMiniRestore) brings the same player back: a new page attaches
 * to the running service (the path a page created again after a rotation takes), docked, rises
 * onto the bar and plays on from where it was paused, without a new extraction or buffering.
 *
 * The page itself is removed as before, so nothing (navigation, lists, back, the bar, rotation)
 * sees a player meanwhile; only the service and the queue stay. This object keeps its own
 * connection to the service (the page's controller is released with the page as always, with
 * all the listeners the page had put on it), which keeps a service that was never started from
 * going with the page, and is used to end it.
 *
 * It ends exactly like today's close (stop command, connection released): after the minute,
 * before another video starts ([releaseThen], which waits for the old service to be gone so the
 * new one does not attach to it while it dies), and when its service goes away by itself (task
 * removed, memory, the system). The restart-based restore stays the fallback for ten minutes.
 * Main thread only; kept in memory only.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object YtSoftClose {
    private val main = Handler(Looper.getMainLooper())
    private val onMain = Executor { main.post(it) }

    // the kept player: its video, when it was closed, and the connection that keeps it
    private var videoId: String? = null
    private var closedAt = 0L
    private var keeper: ListenableFuture<MediaController>? = null

    // the long press asked for this video's page to bring the player back
    private var resumeFor: String? = null
    private var resumeAt = 0L

    // what waits for the old service to be gone (another video), and its time limit
    private val afterGone = mutableListOf<Runnable>()
    private val goneLimit = Runnable { runAfterGone() }

    private val expiry = Runnable {
        YtTestHooks.log("YtSoftClose") { "expired" }
        release()
    }
    private val attachLimit = Runnable {
        if (resumeFor == null) return@Runnable
        YtTestHooks.log("YtSoftClose") { "page did not attach, released" }
        release()
    }

    private fun now() = SystemClock.elapsedRealtime()

    /** Whether a closed player is kept now. */
    val holding: Boolean get() = videoId != null

    /**
     * The player page with [controller] is closed by the user. Returns true if its player is
     * kept: it is paused and hidden in the service now, and the page must neither pause nor stop
     * it when it goes (only release its controller).
     */
    fun close(context: Context, controller: MediaController, videoId: String?, offline: Boolean, live: Boolean): Boolean {
        // a player kept before (there should be none while a page shows) goes first
        release()
        val keeps = YtSoftCloseRules.keeps(
            connected = controller.isConnected,
            offline = offline,
            live = live,
            hasMedia = !videoId.isNullOrEmpty() && controller.mediaItemCount > 0,
            playbackState = controller.playbackState,
        )
        if (!keeps || videoId == null) return false
        controller.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            bundleOf(YtServiceModules.KEY to YtServiceSoftClose.CMD_CLOSE),
        )
        val app = context.applicationContext
        this.videoId = videoId
        closedAt = now()
        keeper = MediaController.Builder(app, SessionToken(app, ComponentName(app, OnlinePlayerService::class.java))).buildAsync()
        main.removeCallbacks(expiry)
        main.postDelayed(expiry, YtSoftCloseRules.HOLD_MS)
        YtTestHooks.log("YtSoftClose") { "kept $videoId" }
        return true
    }

    /** Whether a long press now brings back the kept player of [savedVideo] as it is. */
    fun resumes(savedVideo: String?): Boolean =
        YtSoftCloseRules.route(videoId, closedAt, savedVideo, now()) == YtSoftCloseRules.Route.RESUME

    /** The long press brings the kept player back: the next page of [videoId] takes it over. */
    fun beginResume(videoId: String) {
        resumeFor = videoId
        resumeAt = now()
        main.removeCallbacks(expiry)
        main.removeCallbacks(attachLimit)
        main.postDelayed(attachLimit, YtSoftCloseRules.ATTACH_MS)
    }

    /** Whether the navigation to [videoId] is the page that brings the kept player back. */
    fun isResuming(videoId: String?): Boolean = resumeFor != null && resumeFor == videoId

    /**
     * A player page connected to the service with [controller]. While a player is kept, the page
     * ends the soft close: it plays on if the long press asked for it, else (a page created again
     * while the close was still running) it stays paused. The keeping connection goes.
     */
    fun attached(controller: MediaController, pageVideo: String?) {
        if (!holding) return
        val play = YtSoftCloseRules.playsOnAttach(resumeFor, resumeAt, pageVideo, now())
        controller.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            bundleOf(YtServiceModules.KEY to YtServiceSoftClose.CMD_OPEN, YtServiceSoftClose.ARG_PLAY to play),
        )
        if (play) YtMiniRestore.mark("resumed")
        YtTestHooks.log("YtSoftClose") { "page attached play=$play" }
        clear(stop = false)
    }

    /**
     * Lets the kept player go as today's close does (stop command, connection released). True
     * if one was kept; its service is gone a moment later (YtMiniRestore waits for that).
     */
    fun release(): Boolean {
        if (!holding) return false
        YtTestHooks.log("YtSoftClose") { "release" }
        clear(stop = true)
        return true
    }

    /**
     * Another video starts while a player is kept: the kept one goes first and [then] runs once
     * its service is gone (at the latest after the settle time), so the new player does not
     * attach to the dying service. A queue the caller set up ([keepQueue]) is put back then (the
     * dying service clears the queue). False if nothing was kept: the caller goes on at once.
     */
    fun releaseThen(keepQueue: Boolean, then: Runnable): Boolean {
        if (!holding) return false
        val queue = if (keepQueue) PlayingQueue.getStreams() else null
        release()
        afterGone += Runnable {
            if (queue != null) PlayingQueue.setStreams(queue)
            then.run()
        }
        main.removeCallbacks(goneLimit)
        main.postDelayed(goneLimit, YtMiniRestoreRules.SETTLE_MS)
        return true
    }

    /** The service ended the soft close itself (a start of its own, next, a page): nothing is kept. */
    fun serviceOpened() {
        if (holding) clear(stop = false)
    }

    /** A player service went away; [wasKept] if it was the softly closed one. */
    fun serviceGone(wasKept: Boolean) {
        if (wasKept && holding) clear(stop = false)
        if (afterGone.isNotEmpty()) main.post { runAfterGone() }
    }

    private fun runAfterGone() {
        main.removeCallbacks(goneLimit)
        val runs = afterGone.toList()
        afterGone.clear()
        runs.forEach { it.run() }
    }

    private fun clear(stop: Boolean) {
        main.removeCallbacks(expiry)
        main.removeCallbacks(attachLimit)
        videoId = null
        resumeFor = null
        val future = keeper ?: return
        keeper = null
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull()
            if (stop && controller != null && controller.isConnected) {
                controller.sendCustomCommand(AbstractPlayerService.stopServiceCommand, Bundle.EMPTY)
            }
            MediaController.releaseFuture(future)
        }, onMain)
    }
}
