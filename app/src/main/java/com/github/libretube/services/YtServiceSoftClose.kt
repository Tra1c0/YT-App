package com.github.libretube.services

import android.os.Bundle
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.mini.YtSoftClose
import com.github.libretube.ui.yt.mini.YtSoftCloseRules

/**
 * YT: the service side of the soft close (YtSoftClose). A closed mini player keeps its player,
 * buffer and queue here for a minute, and the closed player must feel closed meanwhile:
 * - paused, and the audio focus let go (a paused ExoPlayer keeps it, so it can resume);
 * - no notification and out of the foreground: the session is taken out of the service's
 *   notification handling (removeSession), and media3 then removes the notification itself
 *   (that also counts away a late thumbnail callback, which would post it again);
 * - deaf to outside controllers: play, next and previous from the lock screen, Bluetooth or a
 *   media button do nothing, and outside controllers cannot connect (AbstractPlayerService);
 * - it ends itself [YtSoftCloseRules.BACKSTOP_MS] after the minute if nobody ended it before.
 * Anything that starts a video in this service (a new start, next, a video by id) ends the soft
 * close first, as does the page that connects again ([open]).
 *
 * Network: a paused ExoPlayer goes on loading until its buffer target is reached and then stops;
 * nothing else is stopped, so the player comes back with that buffer.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class YtServiceSoftClose(
    private val service: AbstractPlayerService,
    private val session: () -> MediaSession?,
) {
    /** Whether the player is closed softly now. */
    var closed = false
        private set

    private val backstop = Runnable {
        if (!closed) return@Runnable
        YtTestHooks.log("YtSoftClose") { "backstop release" }
        service.onDestroy()
    }

    private val handler: Handler get() = service.handler

    /** The mini player was closed: pause and hide, keep everything else. */
    fun close() {
        val exo = service.exoPlayer ?: return
        if (closed) return
        closed = true
        exo.pause()
        exo.setAudioAttributes(exo.audioAttributes, false)
        YtResumeNotification.cancel(service)
        session()?.let { s ->
            if (service.isSessionAdded(s)) service.removeSession(s)
            // with the session out of the service, media3 removes the notification and leaves
            // the foreground (AbstractPlayerService.onUpdateNotification lets this one through)
            service.onUpdateNotification(s, false)
        }
        handler.removeCallbacks(backstop)
        handler.postDelayed(backstop, YtSoftCloseRules.HOLD_MS + YtSoftCloseRules.BACKSTOP_MS)
        YtTestHooks.log("YtSoftClose") { "closed" }
    }

    /**
     * The soft close ends: the player is the service's normal player again (focus handling,
     * notification); [play] resumes it where it was paused.
     */
    fun open(play: Boolean) {
        val exo: ExoPlayer? = service.exoPlayer
        if (closed) {
            closed = false
            handler.removeCallbacks(backstop)
            exo?.setAudioAttributes(exo.audioAttributes, true)
            session()?.let { if (!service.isSessionAdded(it)) service.addSession(it) }
            service.triggerNotificationUpdate()
            YtTestHooks.log("YtSoftClose") { "opened play=$play" }
            // the page side lets go of its keeping connection (if a start of its own came first)
            YtSoftClose.serviceOpened()
        }
        if (play) exo?.play()
    }

    /** The service goes away. */
    fun gone() {
        val wasClosed = closed
        closed = false
        handler.removeCallbacks(backstop)
        YtSoftClose.serviceGone(wasClosed)
    }

    /** A module command: true if it was one of the soft close's. */
    fun command(args: Bundle): Boolean {
        when (args.getString(YtServiceModules.KEY)) {
            CMD_CLOSE -> close()
            CMD_OPEN -> open(play = args.getBoolean(ARG_PLAY))
            else -> return false
        }
        return true
    }

    companion object {
        const val CMD_CLOSE = "soft_close"
        const val CMD_OPEN = "soft_open"
        const val ARG_PLAY = "soft_play"
    }
}

/** YT: routes the soft close's commands (sent with [YtServiceModules.KEY]) to the service. */
@androidx.annotation.OptIn(UnstableApi::class)
object YtSoftCloseModule : YtServiceModule {
    override fun command(service: AbstractPlayerService, exo: ExoPlayer?, args: Bundle): Boolean =
        service.ytSoftClose.command(args)
}
