package com.github.libretube.ui.yt

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.Fragment
import com.github.libretube.R
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.mini.YtMiniDesign
import com.github.libretube.ui.yt.mini.YtMiniDesigns
import com.github.libretube.ui.yt.mini.YtMiniHost
import com.github.libretube.ui.yt.mini.YtMiniJoint
import com.github.libretube.ui.yt.mini.YtMiniLayout
import kotlin.math.roundToInt

/**
 * YT: the mini player as a part of the design's navigation bar. Minimized, the player docks on
 * the bar instead of floating above it, in one of three ways (YtMiniJoint): stacked on it as one
 * surface, bulging out of it, or tucked onto its blobs.
 *
 * Each design is its own object in ui/yt/mini (YtMiniDesign): its geometry, the View constraints,
 * a composable under the picture and one over it, its motion, its long-press preview and its
 * haptics. This object routes the page and the activity to the current design's player.
 */
object YtMiniPlayer {
    /** The current design's mini player. */
    private fun current(): YtMiniDesign = YtMiniDesigns.current()

    /** The designs that draw the player themselves; the classic layout keeps its own. */
    private fun designed(design: YtMiniDesign): Boolean = !design.classic && design.design.palette != null

    /** Whether player and bar of [design] form one surface, so the bar squares its top corners. */
    fun stacks(design: YtDesign): Boolean {
        val mini = YtMiniDesigns.of(design)
        return designed(mini) && mini.spec.joint == YtMiniJoint.STACK
    }

    /** The duration of minimizing in the selected design; the classic 250 ms otherwise. */
    fun minimizeDuration(): Int {
        val mini = current()
        return if (designed(mini)) mini.motion.duration else 250
    }

    /**
     * How much lower than the top of the navigation view the minimized player ends, in px: down
     * to the bar's surface and as far into it as the design's joint reaches.
     */
    fun dockOffsetPx(context: Context): Int {
        val mini = current()
        if (!designed(mini)) return 0
        return ((mini.spec.navTop + mini.spec.overlap) * mini.density(context)).roundToInt()
    }

    /**
     * The offset the activity last gave the player container for docking, in px. The container
     * also carries the audio player, which keeps its classic place above the bar by this much.
     */
    var containerDockPx = 0

    /** How far the docked player reaches above the top of the navigation view, in px. */
    fun dockedExtentPx(context: Context): Int {
        val mini = current()
        if (!designed(mini)) return 0
        return ((mini.spec.height - mini.spec.navTop) * mini.density(context)).roundToInt().coerceAtLeast(0)
    }

    /**
     * Where the picture ends up once docked, in window px: the rect the design's geometry gives
     * the player in the end state, at the place the activity moves the player container to when
     * the player is minimized. Null for the classic layout or while no player page is laid out.
     */
    fun endPlayerRectPx(context: Context): RectF? {
        val mini = current()
        if (!designed(mini)) return null
        val spec = mini.spec
        val activity = ContextHelper.tryUnwrapActivity<Activity>(context) ?: return null
        val container = activity.findViewById<View>(R.id.container) ?: return null
        val page = container.findViewById<View>(R.id.playerMotionLayout) ?: return null
        if (!page.isAttachedToWindow || page.width == 0 || page.height == 0) return null
        val density = mini.density(context)
        fun dp(value: Int) = (value * density).roundToInt()

        // the page without the shift the container has now, plus the shift it has when docked
        // (the last keyframe MainActivity writes: up by the bar, down again by the dock offset)
        val at = IntArray(2)
        page.getLocationInWindow(at)
        val bar = activity.findViewById<View>(R.id.bottomNav)
        val dockedShift = if (bar != null && bar.visibility == View.VISIBLE && bar.height > 0 && NavBarHelper.hasTabs()) {
            (containerDockPx - bar.height).toFloat()
        } else {
            container.translationY
        }
        val pageTop = at[1] - container.translationY + dockedShift
        val pageLeft = at[0].toFloat()

        // main_container in the end set: at the bottom of the page, between the margins of the
        // design (or as wide as the bar and centered)
        val rtl = page.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val boxHeight = dp(spec.height + spec.overlap)
        val boxBottom = pageTop + page.height - page.paddingBottom
        val boxTop = boxBottom - boxHeight
        val boxLeft: Float
        val boxRight: Float
        if (spec.width != null) {
            val width = dp(spec.width)
            boxLeft = pageLeft + page.paddingLeft + (page.width - page.paddingLeft - page.paddingRight - width) / 2f
            boxRight = boxLeft + width
        } else {
            val start = dp(spec.start)
            val end = dp(spec.end)
            boxLeft = pageLeft + page.paddingLeft + if (rtl) end else start
            boxRight = pageLeft + page.width - page.paddingRight - if (rtl) start else end
        }

        // the picture where the geometry puts it in the box
        val boxWidth = boxRight - boxLeft
        val picture = YtMiniLayout.directed(mini.geometry(boxWidth, density).player, boxWidth, rtl)
        return RectF(boxLeft + picture.left, boxTop + picture.top, boxLeft + picture.right, boxTop + picture.bottom)
    }

    /** Gives the mini player of [binding] the form of the current design. */
    fun attach(fragment: Fragment, binding: FragmentPlayerBinding) {
        val design = YtDesign.current()
        YtMiniHost.attach(fragment, binding, YtMiniDesigns.of(design), design.palette)
    }

    /** Called as the mini player is swiped away: the bar no longer carries it. */
    fun undock() {
        YtDock.docked = false
        YtDock.dip = 0f
        YtDock.peek = 0f
        YtDrag.miniBounds = null
        releaseDock()
    }

    private var settle: ValueAnimator? = null

    /** A player follows the minimize progress again: a running release stops. */
    fun keepDock() {
        settle?.cancel()
        settle = null
    }

    /**
     * The player is gone: the minimize progress (and the slab behind it) run back to 0 with the
     * player's own exit, so the bar rounds its corners again without a jump.
     */
    fun releaseDock() {
        settle?.cancel()
        settle = null
        val from = YtDock.progress
        if (from <= 0f || !YtMotionPrefs.enabled) {
            YtDock.progress = 0f
            YtDock.slabPx = 0f
            return
        }
        val slab = YtDock.slabPx
        settle = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 300L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Float
                YtDock.progress = value
                YtDock.slabPx = if (from > 0f) slab * value / from else 0f
            }
            start()
        }
    }
}
