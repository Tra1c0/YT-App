package com.github.libretube.ui.yt.player

import androidx.media3.session.MediaController
import com.github.libretube.api.obj.Streams
import com.github.libretube.ui.yt.mini.YtMiniModule
import com.github.libretube.ui.yt.mini.YtMinimizeModule
import com.github.libretube.ui.yt.scene.YtSceneModule

/**
 * YT: a feature of round 12 on the video player page. The page calls every module at the same
 * points of its life; each hook does nothing unless the module fills it.
 */
interface YtPlayerModule {
    /** The page's view exists (after the mini player took its form). */
    fun onViewCreated(host: YtPlayerHost) {}

    /** The media controller connected (again, after the page was recreated). */
    fun onController(host: YtPlayerHost, controller: MediaController) {}

    /** The details of a video arrived: the first one or the next one. */
    fun onVideo(host: YtPlayerHost, videoId: String, streams: Streams) {}

    /**
     * Back was pressed while the page is open (not while it is minimized: the page's back
     * callback is off then). True if the module handled it; back then does nothing else, and
     * the page opens fully again if a back swipe had already shrunk it.
     */
    fun onBack(host: YtPlayerHost): Boolean = false

    /**
     * True while back would go to this module's [onBack] (e.g. while it shows something that
     * back closes). Asked when a back swipe starts: the page then does not shrink with the
     * swipe, as back will not minimize it.
     */
    fun wantsBack(host: YtPlayerHost): Boolean = false

    /** The page's view goes, while it still exists. */
    fun onDestroyView(host: YtPlayerHost) {}

    /**
     * The page's configuration changed without a rebuild (a rotation the activity handles
     * itself, fullscreen left): gestures that run end, and what was measured on the page before
     * is measured again.
     */
    fun onConfigurationChanged(host: YtPlayerHost) {}

    /** The bridge for dragging the share action onto the timeline, if this module makes one. */
    fun shareScrub(host: YtPlayerHost): YtShareScrubBridge? = null
}

/**
 * YT: dragging the share action of the video page onto the timeline. The page's composable
 * reports the finger in window px; the module that built the bridge does the rest.
 */
interface YtShareScrubBridge {
    fun begin(x: Float, y: Float)
    fun move(x: Float, y: Float)
    fun end(x: Float, y: Float, cancelled: Boolean)
}

/**
 * YT: the modules of the player page, a fixed list with one module per feature package, each
 * in its owner's folder. The page calls them in this order.
 */
object YtPlayerModules {
    private val modules: List<YtPlayerModule> = listOf(
        YtMiniModule,
        YtMinimizeModule,
        YtPlayerGestureModule,
        YtSceneModule,
        YtBatteryCueModule,
    )

    fun onViewCreated(host: YtPlayerHost) = modules.forEach { it.onViewCreated(host) }

    fun onController(host: YtPlayerHost, controller: MediaController) =
        modules.forEach { it.onController(host, controller) }

    fun onVideo(host: YtPlayerHost, videoId: String, streams: Streams) =
        modules.forEach { it.onVideo(host, videoId, streams) }

    /** True if a module handled back; the first one that does wins. */
    fun onBack(host: YtPlayerHost): Boolean = modules.any { it.onBack(host) }

    /** True if a module wants the next back. */
    fun wantsBack(host: YtPlayerHost): Boolean = modules.any { it.wantsBack(host) }

    fun onDestroyView(host: YtPlayerHost) = modules.forEach { it.onDestroyView(host) }

    fun onConfigurationChanged(host: YtPlayerHost) = modules.forEach { it.onConfigurationChanged(host) }

    /** The share-to-timeline bridge of the first module that makes one, else null. */
    fun shareScrub(host: YtPlayerHost): YtShareScrubBridge? =
        modules.firstNotNullOfOrNull { it.shareScrub(host) }
}
