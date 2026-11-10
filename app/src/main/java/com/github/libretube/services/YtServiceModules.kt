package com.github.libretube.services

import android.os.Bundle
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * YT: a feature of round 12 in the player service (the loop, live scrubbing, the time box).
 * The service calls every module at the same points; each hook does nothing unless the
 * module fills it. Main thread only. The modules are shared by the online and the offline
 * service, which can be alive at the same time while one hands over to the other.
 */
@androidx.annotation.OptIn(UnstableApi::class)
interface YtServiceModule {
    /**
     * A command sent with [YtServiceModules.KEY] in its arguments (through
     * AbstractPlayerService.runPlayerActionCommand). True if this module handled it.
     */
    fun command(service: AbstractPlayerService, exo: ExoPlayer?, args: Bundle): Boolean = false

    /**
     * The service created its player; [handler] runs on the main thread. It may come while the
     * module still holds the player of a service that is going away: take the new one.
     */
    fun onPlayer(exo: ExoPlayer, handler: Handler) {}

    /** The service switches to another video (next, previous, a link, autoplay). */
    fun onNavigate(videoId: String) {}

    /** True to keep autoplay from starting the next video when one ends. */
    fun holdAutoplay(): Boolean = false

    /**
     * The service of [exo] is going away: let go of what belongs to it (listeners, player
     * messages). Comes once, and only for the player handed over last with [onPlayer]; even so,
     * drop only what is held for this [exo] (if (exo !== held) return).
     */
    fun release(exo: ExoPlayer) {}
}

/**
 * YT: the modules of the player service, a fixed list with one module per feature, each owned
 * by its package. The upstream commands (PlayerCommand) stay as they are; the modules' commands
 * come under their own key.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object YtServiceModules {
    /** The key of a module command in the command arguments; its value names the command. */
    const val KEY = "yt_cmd"

    private val modules: List<YtServiceModule> = listOf(
        YtLoopModule,
        YtScrubModule,
        YtSessionModule,
        YtSoftCloseModule,
        YtBatteryQualityModule,
        YtSoundModule,
    )

    /** True if a module handled the command; the first one that does wins. */
    fun command(service: AbstractPlayerService, exo: ExoPlayer?, args: Bundle): Boolean =
        modules.any { it.command(service, exo, args) }

    /** The player handed to the modules last; a release for any other one is stale. */
    private var current: ExoPlayer? = null

    fun onPlayer(exo: ExoPlayer, handler: Handler) {
        current = exo
        modules.forEach { it.onPlayer(exo, handler) }
    }

    fun onNavigate(videoId: String) = modules.forEach { it.onNavigate(videoId) }

    /** True if any module holds autoplay back. */
    fun holdAutoplay(): Boolean = modules.any { it.holdAutoplay() }

    /**
     * The service of [exo] is going away. A service tears down late (50 ms after onDestroy,
     * which can run several times), so a newer service may have handed its player over in the
     * meantime: then this release is stale and the modules keep the newer player.
     */
    fun release(exo: ExoPlayer?) {
        if (exo == null || exo !== current) return
        current = null
        modules.forEach { it.release(exo) }
    }
}
