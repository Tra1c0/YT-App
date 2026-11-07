package com.github.libretube.ui.listeners

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.activity.viewModels
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.interfaces.PlayerGestureOptions
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.yt.player.YtPlayerTouch
import java.time.Instant
import kotlin.math.abs

class PlayerGestureController(activity: BaseActivity, private val listener: PlayerGestureOptions) {

    private val orientation get() = Resources.getSystem().configuration.orientation

    private val commonPlayerViewModel: CommonPlayerViewModel by activity.viewModels()
    private val handler = Handler(Looper.getMainLooper())

    private val gestureDetector: GestureDetector
    private val scaleGestureDetector: ScaleGestureDetector
    private val doubleTapSlop = ViewConfiguration.get(activity).scaledDoubleTapSlop

    private var isFullscreen = false
    private var scaleGestureWasInProgress = false
    private var isMoving = false

    // YT: the finger and its long press, ended on every path (see YtPlayerTouch)
    private val touch = YtPlayerTouch()
    val longPressInProgress get() = touch.longPress

    // YT: where the long press began, for the drag after it
    private var longPressX = 0f
    private var longPressY = 0f
    var lastDoublePressTime: Instant? = null

    // YT: where across the width the last double tap landed (0 = left), for the chapter jump at the edge
    var ytDoubleTapFraction = 0.5f
        private set

    var areControlsLocked = false

    init {
        gestureDetector = GestureDetector(activity, GestureListener(), handler)
        scaleGestureDetector = ScaleGestureDetector(activity, ScaleGestureListener(), handler)

        commonPlayerViewModel.isFullscreen.observe(activity) {
            isFullscreen = it
            listener.onFullscreenChange(it)
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        // YT: by the masked action, so a second finger is seen as such
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scaleGestureWasInProgress = false
                // YT: a hold an earlier gesture left running ends before this one starts
                if (touch.down()) listener.onLongPressEnd(0f, 0f, cancelled = true)
                if (isMoving) {
                    isMoving = false
                    listener.onSwipeEnd()
                }

                val (_, height) = listener.getViewMeasures()
                if (event.y < height * 0.1f && orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    // when in landscape mode, don't consume this event if touch down area is at the
                    // top of the player
                    touch.end()
                    return false
                }

                if (areControlsLocked) {
                    // notify the listener that the player controls are currently locked
                    listener.onSingleTap(true)

                    // controls locked, no need to consume this event
                    touch.end()
                    return false
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // YT: a second finger (a pinch) cancels the hold
                if (touch.secondFinger()) listener.onLongPressEnd(0f, 0f, cancelled = true)
            }

            MotionEvent.ACTION_MOVE -> {
                // YT: the GestureDetector stops reporting moves once a long press fired; the hold follows the finger here
                if (longPressInProgress) {
                    listener.onLongPressDrag(event.x - longPressX, event.y - longPressY)
                }

                if (!scaleGestureWasInProgress && scaleGestureDetector.isInProgress) {
                    scaleGestureWasInProgress = true

                    // scale gesture is triggered, cancel any other ongoing gesture detections
                    MotionEvent.obtain(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_CANCEL,
                        event.x,
                        event.y,
                        event.metaState
                    ).also { cancelEvent ->
                        gestureDetector.onTouchEvent(cancelEvent)
                        cancelEvent.recycle()
                    }
                }
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                // YT: state first, then the callbacks: ending a swipe can move the player into
                // fullscreen, which detaches this view and calls abort() from inside
                val wasMoving = isMoving
                isMoving = false
                val wasHolding = touch.end()
                if (wasMoving) listener.onSwipeEnd()

                if (wasHolding) {
                    listener.onLongPressEnd(
                        event.x - longPressX,
                        event.y - longPressY,
                        cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL,
                    )
                }
            }
        }

        scaleGestureDetector.onTouchEvent(event)
        if (!scaleGestureWasInProgress) gestureDetector.onTouchEvent(event)

        return true
    }

    /**
     * YT: ends the current gesture without an event from the system: the view stopped taking
     * touches mid-gesture (controls turned off by the minimize drag before the parent's CANCEL
     * arrived) or left the window. The detectors drop their pending long press and tap, a running
     * hold ends cancelled. Nothing happens when no gesture runs.
     */
    fun abort() {
        if (!touch.active && !isMoving) return
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { cancel ->
            scaleGestureDetector.onTouchEvent(cancel)
            gestureDetector.onTouchEvent(cancel)
            cancel.recycle()
        }
        scaleGestureWasInProgress = false
        val wasMoving = isMoving
        isMoving = false
        val wasHolding = touch.end()
        if (wasMoving) listener.onSwipeEnd()
        if (wasHolding) listener.onLongPressEnd(0f, 0f, cancelled = true)
    }

    private inner class ScaleGestureListener : ScaleGestureDetector.OnScaleGestureListener {
        var scaleFactor = 1f

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            when {
                scaleFactor < 0.8 -> listener.onMinimize()
                scaleFactor > 1.2 -> listener.onZoom()
            }
            scaleFactor = 1f
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        private var touchGestureDownX = 0f
        private var touchGestureDownY = 0f

        override fun onDown(e: MotionEvent): Boolean {
            touchGestureDownX = e.x
            touchGestureDownY = e.y

            return true
        }

        override fun onLongPress(e: MotionEvent) {
            super.onLongPress(e)

            // YT: a long press whose finger is gone (its CANCEL never came) starts nothing
            if (!touch.longPressFired()) return
            longPressX = e.x
            longPressY = e.y
            listener.onLongPress(e.x, e.y)
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_UP -> {
                    if (
                        abs(e.y - touchGestureDownY) > doubleTapSlop ||
                        abs(e.x - touchGestureDownX) > doubleTapSlop
                    ) {
                        // not considered a double tap
                        return false
                    }

                    val (width, _) = listener.getViewMeasures()
                    val eventPositionPercentageX = e.x / width
                    ytDoubleTapFraction = eventPositionPercentageX

                    when {
                        eventPositionPercentageX < LEFT_AREA_VIEW_PERCENTAGE -> {
                            listener.onDoubleTapLeftScreen()
                        }

                        eventPositionPercentageX > RIGHT_AREA_VIEW_PERCENTAGE -> {
                            listener.onDoubleTapRightScreen()
                        }

                        else -> listener.onDoubleTapCenterScreen()
                    }
                }
            }

            lastDoublePressTime = Instant.now()

            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // if there has been a recent double click within the threshold, single clicks
            // are being treated as a repetition of the double click
            // e.g. this allows to continue seeking forward using single clicks after double-clicking once
            lastDoublePressTime?.takeIf {
                val millisElapsedSinceDoubleClick = Instant.now().toEpochMilli() - it.toEpochMilli()
                millisElapsedSinceDoubleClick < DOUBLE_TAP_REPEAT_TIME_THRESHOLD_MILLIS
            }?.let {
                val upEvent = MotionEvent.obtain(e).apply {
                    action = MotionEvent.ACTION_UP
                }
                onDoubleTapEvent(upEvent)
                upEvent.recycle()

                lastDoublePressTime = Instant.now()
                return true
            }

            listener.onSingleTap(false)

            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val (width, height) = listener.getViewMeasures()
            val insideThreshHold = abs(e2.y - e1!!.y) <= MOVEMENT_THRESHOLD
            val insideBorder =
                (e1.x < BORDER_THRESHOLD || e1.y < BORDER_THRESHOLD || e1.x > width - BORDER_THRESHOLD || e1.y > height - BORDER_THRESHOLD)

            // If the movement is inside threshold or scroll is horizontal then return false
            if (!isMoving && (insideThreshHold || insideBorder || abs(distanceX) > abs(distanceY))) {
                return false
            }

            isMoving = true

            if (!isFullscreen && distanceY > 0) {
                // Allow swipe up on the entire area if the player is not currently in fullscreen
                listener.onSwipeCenterScreen(distanceY, e2.y)
                return true
            }

            when {
                e1.x < width * LEFT_AREA_VIEW_PERCENTAGE -> {
                    if (isFullscreen) listener.onSwipeLeftScreen(distanceY, e2.y)
                }

                e1.x > width * RIGHT_AREA_VIEW_PERCENTAGE -> {
                    if (isFullscreen) listener.onSwipeRightScreen(distanceY, e2.y)
                }

                else -> listener.onSwipeCenterScreen(distanceY, e2.y)
            }

            return true
        }
    }

    companion object {
        private const val MOVEMENT_THRESHOLD = 30
        private const val BORDER_THRESHOLD = 90
        private const val LEFT_AREA_VIEW_PERCENTAGE = 0.35f
        private const val RIGHT_AREA_VIEW_PERCENTAGE = 0.65f

        /**
         * After a double click was processed, the user may use single clicks to trigger another double click.
         */
        private const val DOUBLE_TAP_REPEAT_TIME_THRESHOLD_MILLIS = 700
    }
}
