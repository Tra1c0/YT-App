package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import com.github.libretube.R

/**
 * The player page's MotionLayout: only touches that start on [viewToDetectTouch] (the video
 * container) move it. Opened, a downward drag on the video minimizes the player (OnSwipe of
 * player_scene.xml). Minimized, a tap opens it, a drag up opens it (OnSwipe), a strict swipe
 * down calls the [addSwipeDownListener] listeners (closing it).
 *
 * YT: two delegates can take over parts of these gestures. With both null (as long as no
 * feature sets them), everything above works exactly as before; a delegate set during a gesture
 * counts from the next one.
 *
 * [miniTouch] (the mini player of the designs), only while minimized:
 * - [MiniTouch.onLongPress] after the long-press timeout without movement beyond the touch slop.
 *   True starts a peek: from then on every MOVE goes to [MiniTouch.onPeekMove], and UP or CANCEL
 *   to [MiniTouch.onPeekEnd]; the gesture does nothing else (no OnSwipe, no tap, no swipe down).
 *   False: the gesture goes on as before (a long press opens nothing).
 * - [MiniTouch.onDoubleTap] on the second tap of a double tap. True if it handled it; false opens
 *   the player. With a [miniTouch] that [MiniTouch.takesDoubleTap], a single tap opens the player
 *   once it is sure to be no double tap (after the double-tap timeout) instead of at once.
 * - Coordinates are in this layout's coordinates.
 *
 * [minimizeDrag] (dragging the player down and up under the finger):
 * - Opened: when a downward drag beyond the touch slop starts on the video (where OnSwipe would
 *   start today), [MinimizeDrag.canStart] with fromMini = false and the movement since DOWN.
 * - Minimized: when the finger moves beyond the touch slop in any direction, [MinimizeDrag.canStart]
 *   with fromMini = true. Until then, OnSwipe sees no movement.
 * - True: [MinimizeDrag.onGrab] with the event that passed the slop, then every MOVE goes to
 *   [MinimizeDrag.onMove] and UP or CANCEL to [MinimizeDrag.onRelease] with the finger's velocity
 *   in px per second (screen coordinates). Nothing else sees the gesture: no OnSwipe, no tap, no
 *   swipe down. The layout itself moves while the player shrinks, so use rawX/rawY of the events.
 * - False: the gesture goes on as before (OnSwipe, the swipe down).
 *
 * [peeking]: while true (a peek of the mini player runs on its own transition), the transition
 * listeners added with [addTransitionListener] hear nothing, the [addPeekListener] listeners get
 * the calls instead, and a touch counts as one on the minimized player.
 */
class SingleViewTouchableMotionLayout(context: Context, attributeSet: AttributeSet? = null) :
    MotionLayout(context, attributeSet) {

    /** YT: long press, peek and double tap on the minimized player (package A). */
    interface MiniTouch {
        /** True if a peek starts; the rest of the gesture then goes to the peek. */
        fun onLongPress(x: Float, y: Float): Boolean
        fun onPeekMove(x: Float, y: Float)
        fun onPeekEnd(x: Float, y: Float, cancelled: Boolean)

        /** True if handled; false opens the player as a tap does. */
        fun onDoubleTap(x: Float, y: Float): Boolean

        /** Whether double taps are wanted; false opens the player at the first tap, as before. */
        val takesDoubleTap: Boolean get() = true
    }

    /** YT: the player shrinking under the finger and growing again (package B). */
    interface MinimizeDrag {
        /** Whether the drag takes this gesture; [dx], [dy] are the movement since DOWN in px. */
        fun canStart(fromMini: Boolean, dx: Float, dy: Float): Boolean
        fun onGrab(fromMini: Boolean, e: MotionEvent)
        fun onMove(e: MotionEvent)

        /** [vx], [vy] in px per second; [cancelled] for CANCEL. */
        fun onRelease(e: MotionEvent, vx: Float, vy: Float, cancelled: Boolean)
    }

    /** YT: who has the current gesture. */
    private enum class Gesture {
        /** No gesture, or one for the views below (the classic paths decide). */
        IDLE,

        /** A delegate may still take it: the finger has not passed the slop yet. */
        PENDING,

        /** The classic paths have it (OnSwipe, tap, swipe down). */
        LEGACY,

        /** [miniTouch] has it (a peek). */
        PEEK,

        /** [minimizeDrag] has it. */
        DRAG,
    }

    private val viewToDetectTouch by lazy {
        findViewById<View>(R.id.main_container) ?: findViewById(R.id.audio_player_container)
    }
    private val isAudioPlayer by lazy {
        viewToDetectTouch.id == R.id.audio_player_container
    }
    private val scaledTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val viewRect = Rect()
    private val transitionListenerList = mutableListOf<TransitionListener?>()
    private val swipeDownListener = mutableListOf<() -> Unit>()
    private val gestureDetector = GestureDetector(context, Listener())

    /** YT: the listeners that hear the transitions while [peeking]. */
    private val peekListenerList = mutableListOf<TransitionListener>()

    /** YT: see the class description; null keeps the classic gestures of the mini player. */
    var miniTouch: MiniTouch? = null

    /** YT: see the class description; null keeps the classic minimize and open drags. */
    var minimizeDrag: MinimizeDrag? = null

    /** YT: true while a peek of the mini player runs (see the class description). */
    var peeking = false

    private var startedMinimized = false
    private var isStrictlyDownSwipe = false
    private var touchInitialY = 0f
    private var isTouchDownInsideHitArea = false
    private var shouldInterceptTouchEvent = false

    // YT: the state of the delegated gestures
    private var delegated = false
    private var gesture = Gesture.IDLE
    private var touchInitialX = 0f
    private var activeMini: MiniTouch? = null
    private var activeDrag: MinimizeDrag? = null
    private var velocityTracker: VelocityTracker? = null

    init {
        super.setTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(p0: MotionLayout?, p1: Int, p2: Int, p3: Float) {
                if (peeking) {
                    peekListenerList.toList().forEach { it.onTransitionChange(p0, p1, p2, p3) }
                    return
                }
                transitionListenerList.filterNotNull()
                    .forEach { it.onTransitionChange(p0, p1, p2, p3) }
            }

            override fun onTransitionCompleted(p0: MotionLayout?, p1: Int) {
                if (peeking) {
                    peekListenerList.toList().forEach { it.onTransitionCompleted(p0, p1) }
                    return
                }
                transitionListenerList.filterNotNull()
                    .forEach { it.onTransitionCompleted(p0, p1) }
            }
        })
    }

    override fun setTransitionListener(listener: TransitionListener?) {
        addTransitionListener(listener)
    }

    override fun addTransitionListener(listener: TransitionListener?) {
        transitionListenerList += listener
    }

    /** YT: hears the transitions (change and completion) only while [peeking]. */
    fun addPeekListener(listener: TransitionListener) {
        peekListenerList += listener
    }

    fun removePeekListener(listener: TransitionListener) {
        peekListenerList -= listener
    }

    private inner class Listener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // YT: with a double tap of its own, the mini player opens once the tap is confirmed
            if (delegated && miniTouch?.takesDoubleTap == true) return false

            setTransitionDuration(200)
            transitionToStart()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!delegated || miniTouch?.takesDoubleTap != true) return false
            setTransitionDuration(200)
            transitionToStart()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!delegated) return false
            val mini = miniTouch ?: return false
            if (!mini.takesDoubleTap) return false
            if (!mini.onDoubleTap(e.x, e.y)) {
                setTransitionDuration(200)
                transitionToStart()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // YT: a long press on the mini player may start its peek
            if (!delegated || gesture != Gesture.PENDING || !startedMinimized) return
            val mini = miniTouch ?: return
            if (mini.onLongPress(e.x, e.y)) {
                gesture = Gesture.PEEK
                activeMini = mini
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (isStrictlyDownSwipe && distanceY > 0) {
                isStrictlyDownSwipe = false
            }

            if (isStrictlyDownSwipe && distanceY < -15F) {
                swipeDownListener.forEach { it.invoke() }
                return true
            }

            return false
        }
    }

    /**
     * Add a listener when the view is swiped down while the current transition's state is in
     * end state (minimized state)
     */
    fun addSwipeDownListener(listener: () -> Unit) = apply {
        swipeDownListener.add(listener)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // The collapsed page covers the feed as a View, but only its visible strip owns taps.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && progress == 1f && !peeking) {
            viewToDetectTouch.getHitRect(viewRect)
            if (!viewRect.contains(ev.x.toInt(), ev.y.toInt())) return false
        }
        // YT: the finger's velocity for the minimize drag, in screen coordinates, since this
        // layout moves under the finger while the player shrinks
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            velocityTracker?.recycle()
            velocityTracker = if (minimizeDrag != null) VelocityTracker.obtain() else null
        }
        velocityTracker?.let { tracker ->
            val screen = MotionEvent.obtain(ev)
            screen.offsetLocation(ev.rawX - ev.x, ev.rawY - ev.y)
            tracker.addMovement(screen)
            screen.recycle()
        }

        val handled = super.dispatchTouchEvent(ev)

        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocityTracker?.recycle()
            velocityTracker = null
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                shouldInterceptTouchEvent = false
                isTouchDownInsideHitArea = false
                // YT: a new gesture; whether delegates take part is decided once per gesture
                gesture = Gesture.IDLE
                activeMini = null
                activeDrag = null
                delegated = miniTouch != null || minimizeDrag != null

                // never intercept touch event if we're currently in audio player mode
                if (isAudioPlayer) return false

                viewToDetectTouch.getHitRect(viewRect)
                isTouchDownInsideHitArea = viewRect.contains(event.x.toInt(), event.y.toInt())
                touchInitialY = event.y
                touchInitialX = event.x
                // YT: during a peek of the mini player it counts as minimized
                startedMinimized = progress == 1F || peeking
                isStrictlyDownSwipe = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!startedMinimized && !shouldInterceptTouchEvent && isTouchDownInsideHitArea) {
                    val deltaY = event.y - touchInitialY

                    // swipe down detected
                    if (deltaY > scaledTouchSlop) {
                        // start intercepting and consume the event ourselves in onTouchEvent()
                        shouldInterceptTouchEvent = true

                        // YT: the minimize drag takes the gesture if it wants it
                        val drag = if (delegated) minimizeDrag else null
                        if (drag != null && drag.canStart(false, event.x - touchInitialX, deltaY)) {
                            startDrag(drag, false, event)
                        } else {
                            if (delegated) gesture = Gesture.LEGACY

                            // inject down MotionEvent from current position to properly trigger
                            // motion scene's swipe action
                            MotionEvent.obtain(event).apply {
                                action = MotionEvent.ACTION_DOWN
                                setLocation(event.x, event.y)
                            }.also { downEvent ->
                                onTouchEvent(downEvent)
                                downEvent.recycle()
                            }
                        }
                    }
                }
            }
        }

        return shouldInterceptTouchEvent
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!delegated) return classicTouchEvent(event)

        when (gesture) {
            Gesture.PEEK -> return peekTouchEvent(event)
            Gesture.DRAG -> return dragTouchEvent(event)
            else -> Unit
        }
        if (!isTouchDownInsideHitArea) return false
        return if (startedMinimized) miniTouchEvent(event) else fullTouchEvent(event)
    }

    /** The gestures as they always were: the only path while no delegate is set. */
    private fun classicTouchEvent(event: MotionEvent): Boolean {
        if (isTouchDownInsideHitArea && startedMinimized) {
            // detect gesture only when the player is in minimized state
            gestureDetector.onTouchEvent(event)
        }

        return isTouchDownInsideHitArea && super.onTouchEvent(event)
    }

    /** YT: minimized, with a delegate: the detector sees everything, OnSwipe only once it is decided. */
    private fun miniTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (gesture == Gesture.IDLE) gesture = Gesture.PENDING

            MotionEvent.ACTION_MOVE -> if (gesture == Gesture.PENDING) {
                val dx = event.x - touchInitialX
                val dy = event.y - touchInitialY
                if (dx * dx + dy * dy <= scaledTouchSlop.toFloat() * scaledTouchSlop) {
                    // within the slop only the detector sees the finger (tap, long press)
                    gestureDetector.onTouchEvent(event)
                    return true
                }
                val drag = minimizeDrag
                if (drag != null && drag.canStart(true, dx, dy)) {
                    cancelDetector(event)
                    startDrag(drag, true, event)
                    return true
                }
                gesture = Gesture.LEGACY
                // The undecided tap belongs to the detector. Prime OnSwipe only when it wins.
                MotionEvent.obtain(event).also { down ->
                    down.action = MotionEvent.ACTION_DOWN
                    down.setLocation(touchInitialX, touchInitialY)
                    super.onTouchEvent(down)
                    down.recycle()
                }
            }
        }

        gestureDetector.onTouchEvent(event)
        val handled = if (gesture == Gesture.PENDING) true else super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gesture = Gesture.IDLE
        }
        return handled
    }

    /**
     * YT: opened, with a delegate, for a touch on the video that no view below took (else the
     * drag starts in [onInterceptTouchEvent]); also gets the injected DOWN of the classic path.
     */
    private fun fullTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && gesture == Gesture.IDLE) {
            gesture = if (minimizeDrag != null) Gesture.PENDING else Gesture.LEGACY
        }
        if (gesture == Gesture.PENDING) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchInitialX
                    val dy = event.y - touchInitialY
                    // within the slop OnSwipe sees no movement, so it cannot start before the drag
                    if (dx * dx + dy * dy <= scaledTouchSlop.toFloat() * scaledTouchSlop) return true
                    val drag = minimizeDrag
                    if (dy > scaledTouchSlop && drag != null && drag.canStart(false, dx, dy)) {
                        startDrag(drag, false, event)
                        return true
                    }
                    gesture = Gesture.LEGACY
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gesture = Gesture.IDLE
            }
        }

        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gesture = Gesture.IDLE
        }
        return handled
    }

    private fun peekTouchEvent(event: MotionEvent): Boolean {
        val mini = activeMini
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> mini?.onPeekMove(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gesture = Gesture.IDLE
                activeMini = null
                cancelDetector(event)
                mini?.onPeekEnd(event.x, event.y, event.actionMasked == MotionEvent.ACTION_CANCEL)
            }
        }
        return true
    }

    private fun dragTouchEvent(event: MotionEvent): Boolean {
        val drag = activeDrag
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> drag?.onMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                var vx = 0f
                var vy = 0f
                velocityTracker?.let { tracker ->
                    tracker.computeCurrentVelocity(1000)
                    vx = tracker.xVelocity
                    vy = tracker.yVelocity
                }
                gesture = Gesture.IDLE
                activeDrag = null
                drag?.onRelease(event, vx, vy, event.actionMasked == MotionEvent.ACTION_CANCEL)
            }
        }
        return true
    }

    private fun startDrag(drag: MinimizeDrag, fromMini: Boolean, event: MotionEvent) {
        gesture = Gesture.DRAG
        activeDrag = drag
        parent?.requestDisallowInterceptTouchEvent(true)
        drag.onGrab(fromMini, event)
    }

    /** Ends what the detector was waiting for (a tap, a long press, a swipe down). */
    private fun cancelDetector(event: MotionEvent) {
        MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }.also { cancel ->
            gestureDetector.onTouchEvent(cancel)
            cancel.recycle()
        }
    }
}
