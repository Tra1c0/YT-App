package com.github.libretube.ui.yt.mini

import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.view.doOnAttach
import com.github.libretube.ui.yt.YtMotionPrefs

/**
 * YT: how the player page's minimize transition starts once the page is in the window.
 *
 * PlayerFragment sets the page to its end (progress 1) and opens it from there. A fresh page is
 * created inside the attached activity, so that works at once. A page created again for the
 * same player (the activity was created again: a rotation, a new look) is created before the
 * activity is in its window: MotionLayout keeps a progress set then only until it is attached
 * and then jumps to it without a transition, and transitionToStart() before that does nothing
 * (the layout's last position is still 0). So the rotated player came back in the end state
 * without a transition: the page, the activity's container, the bar and the mini player never
 * heard of it, and it lay at the page's foot under the bar, not usable.
 */
object YtPageStart {
    /** Where the docked page restarts from: close enough to the end that nothing visibly moves. */
    private const val DOCK_FROM = 0.98f

    /**
     * Opens [page] once it is attached, or docks it again when it was docked ([docked]). A docked
     * page that is a closed mini player brought back ([rise]) rises onto the bar from behind its
     * edge in the design's motion (YtMiniHost.armRise); with motion off it appears docked.
     */
    fun start(page: MotionLayout, docked: Boolean, rise: Boolean = false) {
        if (page.isAttachedToWindow) {
            run(page, docked, rise)
        } else {
            // the progress kept for the attach is applied in onAttachedToWindow, before this
            page.doOnAttach { run(page, docked, rise) }
        }
    }

    private fun run(page: MotionLayout, docked: Boolean, rise: Boolean) {
        if (!docked) {
            page.transitionToStart()
            return
        }
        // hidden before its first frame; it rises once the transition below has docked it
        if (YtMiniRiseRules.rises(rise, YtMotionPrefs.enabled)) YtMiniHost.of(page)?.armRise()
        // the last bit of a minimize: its listeners hear it end docked, the activity moves the
        // container onto the bar and the mini player takes its form
        page.progress = DOCK_FROM
        page.transitionToEnd()
    }
}
