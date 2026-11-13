package com.github.libretube.ui.yt.mini

import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.player.YtPlayerHost
import com.github.libretube.ui.yt.player.YtPlayerModule

/**
 * YT (package B): dragging the video down shrinks it under the finger into the mini player, and
 * up again ([YtMinimizeDrag]). The switch [PREF_KEY] ("Video folgt dem Finger") turns it off;
 * the page then keeps the classic drag of player_scene.xml.
 *
 * For the mini player of each design (package A):
 * - [dockTexture]: the haptic texture the player lands with on the bar after a drag (the
 *   design's own); unset, a medium landing plays.
 * - [landingFromDrag]: true from a drag's release until its landing completes. The mini
 *   player's completion listener runs before this module's, so it reads true there for a
 *   landing the drag already plays the haptic of.
 * - [closing]: the docked player is being thrown away (the page closes right after).
 * - [dragging]: a drag or its release runs on the page.
 * - [fromPage]: that drag began on the open page (not on the docked player): the seamless
 *   minimize does not cut it at the bar's edge.
 */
object YtMinimizeModule : YtPlayerModule {
    /** "Video folgt dem Finger", read per gesture. */
    const val PREF_KEY = "yt_minimize_follow"

    /** Package A: the design's texture for landing on the bar after a drag; null for the default. */
    var dockTexture: (() -> YtHapticTexture?)? = null

    var landingFromDrag = false
        internal set

    var closing = false
        internal set

    var dragging = false
        internal set

    var fromPage = false
        internal set

    private var drag: YtMinimizeDrag? = null
    private var dragHost: YtPlayerHost? = null

    override fun onViewCreated(host: YtPlayerHost) {
        val binding = host.pageBinding() ?: return
        drag?.detach()
        drag = YtMinimizeDrag(host, binding).also { it.attach() }
        dragHost = host
        landingFromDrag = false
        closing = false
        dragging = false
        fromPage = false
    }

    // a back swipe or back takes the transition: a release still running lets go of it
    override fun wantsBack(host: YtPlayerHost): Boolean {
        if (host === dragHost) drag?.yield()
        return false
    }

    override fun onBack(host: YtPlayerHost): Boolean {
        if (host === dragHost) drag?.yield()
        return false
    }

    // turned without a rebuild, or fullscreen left: the drag starts again from the page as it is
    override fun onConfigurationChanged(host: YtPlayerHost) {
        if (host === dragHost) drag?.onConfigurationChanged()
    }

    override fun onDestroyView(host: YtPlayerHost) {
        if (host !== dragHost) return
        drag?.detach()
        drag = null
        dragHost = null
        landingFromDrag = false
        closing = false
        dragging = false
        fromPage = false
    }
}
