package com.github.libretube.ui.yt.player

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.util.UnstableApi
import com.github.libretube.ui.views.DismissableTimeBar
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.ui.yt.YtTestHooks

/**
 * YT: sharing onto the timeline. The share action of the video page is held and dragged: a token
 * follows the finger; on the time bar a pin with the storyboard shows the second the link starts
 * at, finer the higher the finger above the bar ([DismissableTimeBar.shareMove]). Letting go
 * there opens the system's share sheet with youtu.be/<id>?t=<s>; anywhere else the token springs
 * back and nothing happens. The controls stay up during the drag, so the bar is there to aim at.
 *
 * The designs' pages drive it through [YtShareScrubBridge] (Modifier.ytShareScrubSource), the
 * classic share button through [attachClassic]; both in window px. Main thread only.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class YtShareScrub(private val host: YtPlayerHost) : YtShareScrubBridge {
    private var active = false
    private var token: YtShareTokenView? = null
    private var haptics: YtHaptics? = null
    private val fling = YtFlingTracker()
    private var startX = 0f
    private var startY = 0f
    private val location = IntArray(2)

    private fun bar(): DismissableTimeBar? = host.controls()?.exoProgress

    override fun begin(x: Float, y: Float) {
        if (active) end(x, y, cancelled = true)
        val root = host.pageBinding()?.root ?: return
        val haptics = YtHaptics.of(root)
        val controller = host.controller()
        val activity = host.fragment().activity
        // a live stream has no second to start at; without a video there is nothing to share
        if (activity == null || controller == null || host.currentVideoId() == null || host.currentStreams() == null ||
            controller.isCurrentMediaItemLive || controller.duration <= 0L || bar() == null
        ) {
            haptics.reject()
            YtTestHooks.log(LOG_TAG) { "share refused" }
            return
        }
        active = true
        this.haptics = haptics
        startX = x
        startY = y
        fling.reset()
        fling.add(SystemClock.uptimeMillis(), x, y)
        haptics.grab()
        host.showControlsPermanently(true)
        token = YtShareTokenView.show(activity, x, y)
        YtTestHooks.log(LOG_TAG) { "share begin x=${x.toInt()} y=${y.toInt()}" }
    }

    override fun move(x: Float, y: Float) {
        if (!active) return
        fling.add(SystemClock.uptimeMillis(), x, y)
        val bar = bar()
        if (bar != null) {
            bar.getLocationInWindow(location)
            bar.shareMove(x - location[0], y - location[1])
        }
        token?.follow(x, y, bar?.shareInZone == true)
    }

    override fun end(x: Float, y: Float, cancelled: Boolean) {
        if (!active) return
        // where the finger lifts counts
        if (!cancelled) move(x, y)
        active = false
        val seconds = bar()?.shareEnd(cancelled)
        host.showControlsPermanently(false)
        val token = token
        this.token = null
        if (seconds != null) {
            haptics?.land(DROP_WEIGHT)
            token?.drop()
            YtTestHooks.log(LOG_TAG) { "share t=$seconds" }
            host.share(seconds, direct = true)
        } else {
            haptics?.reject()
            val v = fling.velocity()
            token?.returnHome(startX, startY, v[0], v[1])
            YtTestHooks.log(LOG_TAG) { "share cancelled" }
        }
        haptics = null
    }

    /**
     * The classic share button: a long press lifts the token, the finger drags it; a plain tap
     * still opens the share dialog (the button's own click).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachClassic(button: View) {
        val downAt = FloatArray(2)
        var fingerDown = false
        var dragging = false
        button.setOnTouchListener { v, event ->
            v.getLocationInWindow(location)
            val wx = location[0] + event.x
            val wy = location[1] + event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt[0] = wx
                    downAt[1] = wy
                    fingerDown = true
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> if (dragging && active) move(wx, wy)

                MotionEvent.ACTION_UP -> {
                    fingerDown = false
                    if (dragging) {
                        dragging = false
                        end(wx, wy, cancelled = false)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    fingerDown = false
                    if (dragging) {
                        dragging = false
                        end(wx, wy, cancelled = true)
                    }
                }
            }
            // the button keeps its own handling (press state, click, long click)
            false
        }
        button.setOnLongClickListener { v ->
            // a long press without a finger (an accessibility action) has nothing to drag
            if (!fingerDown) return@setOnLongClickListener false
            // the scroll view and the player's swipe must not take the finger from here on
            v.parent?.requestDisallowInterceptTouchEvent(true)
            dragging = true
            begin(downAt[0], downAt[1])
            if (!active) dragging = false
            true
        }
    }

    /** The page's view goes: a drag in progress ends without sharing, the token goes at once. */
    fun release() {
        val token = token
        if (active) end(startX, startY, cancelled = true)
        token?.remove()
        this.token = null
    }

    private companion object {
        const val LOG_TAG = "YtShare"
        const val DROP_WEIGHT = 0.45f
    }
}
