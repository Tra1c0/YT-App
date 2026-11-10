package com.github.libretube.ui.yt.mini

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.constraintlayout.motion.widget.YtMotionAccess
import com.github.libretube.R
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.views.SingleViewTouchableMotionLayout
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.ui.yt.YtMiniPlayer
import com.github.libretube.ui.yt.YtMotionPrefs
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.player.YtPlayerHost
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * YT (package B): the player page follows the finger between open and docked. Dragging the
 * video down shrinks it under the finger into the mini player on the bar, dragging the mini
 * player up opens it the same way, and dragging the docked player down moves it 1:1 until a
 * release past [closeAt] (or a flick) throws it away.
 *
 * The finger holds a point of the video (of the mini player's box when it starts docked). Each
 * move solves which progress of the minimize transition puts that point under the finger
 * ([YtUnderFinger]: the views move in straight lines, the activity shifts the container in
 * straight lines too), sideways the video follows with a gentle resistance, and past the ends
 * the page rubber-bands. On release the finger's speed decides where it goes ([YtUnderFinger.settle])
 * and a spring takes the point there, starting at the finger's speed; just before the end the
 * transition finishes with transitionToEnd/Start, so the page, the mini player and the bar
 * hear the completion as from any other minimize.
 *
 * Shares the touch with the rest (SingleViewTouchableMotionLayout decides who has a gesture): a
 * tap still opens, the long press is the mini player's (package A), a hold on the video is the
 * player gestures' (package C, which keeps the parent from intercepting), back and predictive
 * back stop a running release ([yield]).
 */
internal class YtMinimizeDrag(
    private val host: YtPlayerHost,
    private val binding: FragmentPlayerBinding,
) : SingleViewTouchableMotionLayout.MinimizeDrag {
    private val motion = binding.playerMotionLayout
    private val density = motion.resources.displayMetrics.density
    private val haptics = YtHaptics.of(motion)
    private val choreographer = Choreographer.getInstance()
    private val at = IntArray(2)
    private var attached = false

    // what moves sideways with the finger: the picture and the mini player's parts around it
    private val sideways: List<View> = listOf(
        binding.mainContainer,
        binding.player,
        binding.closeImageView,
        binding.playImageView,
        binding.titleTextView,
    )

    /** Where the picture and its box were the last time the page rested open or docked. */
    private class Seen(val player: YtBox, val box: YtBox, val pageWidth: Int, val pageHeight: Int, val generation: Int)

    /** Counts the page's configuration changes: a place seen before the last one is not used. */
    private var generation = 0

    private var seenOpen: Seen? = null
    private var seenDocked: Seen? = null

    /** What a gesture needs, worked out when it asks to start. */
    private class Geometry(
        val fromMini: Boolean,
        val tracked: View,
        val start: YtBox,
        val end: YtBox,
        val endFrom: String,
        val pageLeft: Float,
        val pageTop: Float,
        val curve: YtContainerCurve,
        val progress: Float,
    )

    private enum class Zone { STAY, SWITCH, CLOSE }

    /** One gesture, from the grab to the end of its release. */
    private class Session(
        val fromMini: Boolean,
        val tracked: View,
        var finger: YtUnderFinger,
        val endFrom: String,
        /** The progress at the grab: 0 open, 1 docked, between when a release was caught. */
        val startProgress: Float,
        /** Where the page last rested before this gesture (0 open, 1 docked), also across a caught release. */
        val origin: Int,
        val offX: Float,
        val offY: Float,
    ) {
        /** The progress last set (or, untouched, the end the page rests at). */
        var progress = startProgress

        /** The one before, which the page may still show (it lays out at its next draw). */
        var previous = startProgress

        /** Whether this gesture has moved the transition away from where it rested. */
        var touched = startProgress > 0f && startProgress < 1f

        /** False once the transition finishes on its own (or someone else took it over). */
        var ownsProgress = true

        /** Whether the docked place of a model ([endFrom]) was replaced by the one the page lays out. */
        var learned = false

        /** Someone else took the transition during the release: only the offsets go back to 0. */
        var yielded = false

        /** Where the release takes the transition (0 or 1). */
        var endTarget = startProgress

        var tx = 0f
        var ty = 0f
        var below = 0f
        var zone = Zone.STAY

        // the frame check of the emulator runs: what the page shows now and what it showed
        var shown: FloatArray? = null
        var placed: FloatArray? = null
        var maxError = 0f
        var frames = 0
    }

    private var pending: Geometry? = null
    private var session: Session? = null
    private var release: Choreographer.FrameCallback? = null

    /** A drag's release finishes the transition: its completion gets the landing (-1: none). */
    private var landingTarget = -1

    /** Whether that landing changes the state (a return to where the drag began plays nothing). */
    private var landingChanges = false
    private var closing = false

    // the classic drag (OnSwipe) where this one does not take the gesture: a tick at half way
    private var classic = false
    private var classicSince = 0L
    private var classicStart = false
    private var classicSide = false

    private val sidewaysLinear get() = 0.25f * motion.width
    private val sidewaysDimension get() = motion.width.toFloat().coerceAtLeast(1f)
    private val band get() = 56f * density

    /** How far below its place the docked player has to be let go to be thrown away. */
    private val closeAt: Float
        get() = max(56f * density, 0.6f * (seenDocked?.box?.height ?: 0f))

    private val listener = object : TransitionAdapter() {
        override fun onTransitionChange(motionLayout: MotionLayout?, startId: Int, endId: Int, progress: Float) {
            if (!attached || !classic) return
            // a classic drag that never moved the transition ends without a completion: a later
            // minimize by back must not tick for it
            if (SystemClock.uptimeMillis() - classicSince > CLASSIC_DRAG_MS) {
                classic = false
                return
            }
            val side = progress >= 0.5f
            if (side != classicSide) {
                classicSide = side
                haptics.threshold(side != classicStart)
            }
        }

        override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
            if (!attached) return
            classic = false
            val target = landingTarget
            landingTarget = -1
            if (closing) {
                // closing finishes the transition on its way out: the bar no longer carries it
                YtMiniPlayer.undock()
                YtMinimizeModule.landingFromDrag = false
                return
            }
            YtMinimizeModule.landingFromDrag = false
            if (target < 0) return
            val docked = currentId == R.id.end
            log { "minimize end p_end=${if (docked) 1 else 0}" }
            // a landing that changes the state (one back to where the drag began plays nothing)
            if (!landingChanges) return
            if (docked) {
                haptics.play(YtMinimizeModule.dockTexture?.invoke() ?: DOCK)
            } else if (currentId == R.id.start) {
                haptics.play(OPEN)
            }
        }
    }

    // remembers where the page rests open and docked, for gestures that start in between, and
    // learns the docked place from the layout while a drag runs on a model of it
    private val restWatch = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        remember()
        learnEnd()
    }

    private val frameCheck = ViewTreeObserver.OnPreDrawListener {
        checkFrame()
        true
    }

    fun attach() {
        attached = true
        motion.minimizeDrag = this
        motion.addTransitionListener(listener)
        binding.mainContainer.addOnLayoutChangeListener(restWatch)
        binding.player.addOnLayoutChangeListener(restWatch)
    }

    fun detach() {
        // thrown away, the page goes as it is (putting it back would show it for a frame)
        stop(reset = !closing)
        attached = false
        if (motion.minimizeDrag === this) motion.minimizeDrag = null
        binding.mainContainer.removeOnLayoutChangeListener(restWatch)
        binding.player.removeOnLayoutChangeListener(restWatch)
        // the page's own listener list has no removal; [attached] keeps this one quiet
    }

    /** Back or a back swipe begins: a running release lets go of the transition. */
    fun yield() {
        val s = session ?: return
        if (release == null || closing) return
        yieldTo(s)
    }

    /**
     * The page changed its configuration (a rotation without a rebuild, a fullscreen left): its
     * size and its layout change under a gesture, and the places it rested at are another
     * page's. A running drag or release ends where it began (instantly), offsets go, and the
     * places are measured again.
     */
    fun onConfigurationChanged() {
        generation++
        seenOpen = null
        seenDocked = null
        pending = null
        classic = false
        val s = session
        if (s != null && !closing) {
            if (s.ownsProgress && s.touched) finishTransition(s, s.origin, instant = true)
            stop(reset = true)
        } else if (s == null && !closing && motion.progress == 0f) {
            // (docked, the mini player's landing and rise own the offsets)
            clearLeftovers()
        }
    }

    private fun clearLeftovers() {
        if (motion.translationY != 0f || sideways.any { it.translationX != 0f }) {
            log { "minimize leftovers tx=${r1(binding.player.translationX)} ty=${r1(motion.translationY)}" }
            setOffsets(0f, 0f)
        }
    }

    /** The transition is someone else's now; the page's offsets still spring back to 0. */
    private fun yieldTo(s: Session) {
        if (s.yielded) return
        s.yielded = true
        if (s.ownsProgress) {
            s.ownsProgress = false
            landingTarget = -1
            YtMinimizeModule.landingFromDrag = false
        }
    }

    // --- MinimizeDrag

    override fun canStart(fromMini: Boolean, dx: Float, dy: Float): Boolean {
        pending = null
        if (!attached || closing) return false
        // sideways on the docked player is not a drag to open or close
        if (fromMini && abs(dx) > abs(dy)) return false
        // an open page at rest has no offsets of a drag: whatever is left (a gesture that never
        // heard its end) goes, so this drag starts from the page as the transition lays it out
        if (!fromMini && session == null && release == null) clearLeftovers()
        val enabled = PreferenceHelper.getBoolean(YtMinimizeModule.PREF_KEY, true)
        val geometry = if (enabled) geometry(fromMini) else null
        if (geometry == null) {
            // the classic drag takes it (OnSwipe), with the tick at half way
            classic = true
            classicSince = SystemClock.uptimeMillis()
            classicStart = motion.progress >= 0.5f
            classicSide = classicStart
            return false
        }
        pending = geometry
        return true
    }

    override fun onGrab(fromMini: Boolean, e: MotionEvent) {
        val g = pending?.takeIf { it.fromMini == fromMini } ?: geometry(fromMini)
        pending = null
        // a release still running is caught where it is
        val caughtTx = session?.tx ?: 0f
        val caughtTy = session?.ty ?: 0f
        // a caught release keeps the side its gesture began on: landing there changes nothing
        val caughtOrigin = session?.takeIf { !it.yielded }?.origin
        stop(reset = false)
        if (g == null) {
            setOffsets(0f, 0f)
            return
        }
        classic = false
        closing = false
        YtMinimizeModule.closing = false
        landingTarget = -1
        YtMinimizeModule.landingFromDrag = false
        // progress means position from here on (the last minimize or open left its easing set)
        YtMotionAccess.drawProgressLinear(motion)

        // the point under the finger, in the box as the transition lays it out now (without the
        // offsets a caught release still has), where it lies within the picture
        val p = g.progress
        val probe = YtUnderFinger(g.start, g.end, 0f, 0f, g.pageLeft, g.pageTop, g.curve)
        val laid = probe.box(p)
        val left = g.pageLeft + laid.left
        val top = g.pageTop + g.curve.at(p) + laid.top
        val width = laid.width.coerceAtLeast(1f)
        val height = laid.height.coerceAtLeast(1f)
        val u = ((e.rawX - caughtTx - left) / width).coerceIn(0f, 1f)
        val v = ((e.rawY - caughtTy - top) / height).coerceIn(0f, 1f)
        val finger = YtUnderFinger(g.start, g.end, u, v, g.pageLeft, g.pageTop, g.curve)
        // the finger stays where it is relative to that point: no jump when it takes hold, also
        // not when it catches the page in its rubber band or beside the picture
        val offX = e.rawX - (finger.pointX(p) + YtDragMath.unFollow(caughtTx, sidewaysLinear, sidewaysDimension))
        val offY = e.rawY - (finger.pointY(p) + unband(fromMini, caughtTy))
        val origin = caughtOrigin ?: if (p >= 0.5f) 1 else 0
        val s = Session(fromMini, g.tracked, finger, g.endFrom, p, origin, offX, offY)
        s.tx = caughtTx
        s.ty = caughtTy
        s.zone = zoneOf(s, p, 0f)
        session = s
        haptics.grab()
        YtMinimizeModule.dragging = true
        log {
            "minimize grab from=${if (fromMini) "mini" else "full"} p0=${r3(p)} u=${r2(u)} v=${r2(v)} " +
                "start=${boxText(g.start)} end=${boxText(g.end)} endFrom=${g.endFrom} curve=${r1(g.curve.end)}"
        }
        if (YtTestHooks.gestureLog) motion.viewTreeObserver.addOnPreDrawListener(frameCheck)
        move(s, e.rawX - offX, e.rawY - offY, dragging = true)
    }

    override fun onMove(e: MotionEvent) {
        val s = session ?: return
        if (release != null) return
        move(s, e.rawX - s.offX, e.rawY - s.offY, dragging = true)
    }

    override fun onRelease(e: MotionEvent, vx: Float, vy: Float, cancelled: Boolean) {
        val s = session ?: return
        if (release != null) return
        val x = e.rawX - s.offX
        val y = e.rawY - s.offY
        move(s, x, y, dragging = false)
        removeFrameCheck()
        val throwAway = !cancelled && s.fromMini && YtDragMath.closes(s.below, vy, closeAt)
        val target = when {
            // a gesture the system took back goes back to where it began
            cancelled -> s.origin
            else -> s.finger.settle(y, vy, s.progress)
        }
        log {
            val to = if (throwAway) "close" else if (target == 1) "dock" else "open"
            val ok = s.frames == 0 || s.maxError <= MAX_ERROR
            "minimize release p=${r3(s.progress)} vy=${vy.roundToInt()} to=$to cancelled=$cancelled " +
                "maxErr=${r1(s.maxError)} frames=${s.frames} maxErrOk=$ok"
        }
        if (throwAway) throwAway(s, vx, vy) else settle(s, y, vx, vy, target)
    }

    // --- following the finger

    /** Puts the grabbed point at window [x], [y]: the transition's progress, the page's rubber band, sideways. */
    private fun move(s: Session, x: Float, y: Float, dragging: Boolean) {
        placeY(s, y, dragging)
        val p = if (s.ownsProgress) s.progress else s.startProgress
        setSideways(s, YtDragMath.follow(x - s.finger.pointX(p), sidewaysLinear, sidewaysDimension))
        s.placed = floatArrayOf(x, y, s.tx, s.ty, if (s.below != 0f) 1f else 0f)
    }

    /** The y part of [move]; returns the progress the finger asks for (before any clamping). */
    private fun placeY(s: Session, y: Float, dragging: Boolean): Float {
        val fit = s.finger.fit(y, s.progress)
        var drawn = s.progress
        if (s.ownsProgress) {
            if (!s.touched && fit.progress > EDGE && fit.progress < 1f - EDGE) s.touched = true
            if (s.touched) {
                drawn = fit.progress.coerceIn(EDGE, 1f - EDGE)
                if (drawn != s.progress) setProgress(s, drawn)
            }
        } else {
            drawn = s.endTarget
        }
        val rest = y - s.finger.pointY(drawn)
        s.below = rest
        val ty = band(s, rest)
        if (ty != s.ty) {
            s.ty = ty
            motion.translationY = ty
        }
        if (dragging) {
            val zone = zoneOf(s, fit.progress, rest)
            if (zone != s.zone) {
                haptics.threshold(zone != Zone.STAY)
                s.zone = zone
            }
        }
        return fit.progress
    }

    private fun setProgress(s: Session, p: Float) {
        // MotionLayout tells its listeners the progress it had before each one set by hand, so it
        // is set twice: the second time the page, the mini player and the bar hear this frame's
        // value (and the activity's container follows in the same frame)
        motion.progress = p
        motion.progress = p
        (host.fragment().activity as? AbstractPlayerHostActivity)?.setPlayerContainerProgress(p)
        s.previous = s.progress
        s.progress = p
    }

    private fun setSideways(s: Session, tx: Float) {
        if (tx == s.tx) return
        s.tx = tx
        sideways.forEach { it.translationX = tx }
    }

    private fun setOffsets(tx: Float, ty: Float) {
        sideways.forEach { it.translationX = tx }
        motion.translationY = ty
    }

    /** Past an end: the docked player follows 1:1 downwards (towards closing), anything else rubber-bands. */
    private fun band(s: Session, rest: Float): Float = when {
        rest == 0f -> 0f
        s.fromMini && rest > 0f -> rest
        else -> YtDragMath.rubberBand(rest, band)
    }

    /** The rest past the end that [band] turns into [ty]. */
    private fun unband(fromMini: Boolean, ty: Float): Float = when {
        ty == 0f -> 0f
        fromMini && ty > 0f -> ty
        else -> YtDragMath.unRubberBand(ty, band)
    }

    private fun zoneOf(s: Session, fitProgress: Float, rest: Float): Zone = when {
        s.fromMini && rest >= closeAt -> Zone.CLOSE
        (s.origin == 1 && fitProgress < 0.5f) || (s.origin == 0 && fitProgress >= 0.5f) -> Zone.SWITCH
        else -> Zone.STAY
    }

    // --- letting go

    /** Springs the point to [target] (0 open, 1 docked) from the finger's speed, then lets the transition finish. */
    private fun settle(s: Session, y: Float, vx: Float, vy: Float, target: Int) {
        s.endTarget = target.toFloat()
        if (!s.touched && target.toFloat() == s.startProgress) s.ownsProgress = false
        if (!YtMotionPrefs.enabled) {
            // no animation: straight to the end
            if (s.ownsProgress) finishTransition(s, target, instant = true)
            stop(reset = true)
            return
        }
        val yTarget = s.finger.pointY(target.toFloat())
        val ySpring = (if (target == 1) YtMinimizeSprings.dock() else YtMinimizeSprings.open()).apply {
            position = y
            velocity = vy
        }
        val xSpring = YtMinimizeSprings.sideways().apply {
            position = s.tx
            velocity = vx
        }
        // once someone else has the transition, the page's own offset on its way back to 0
        var tySpring: YtSpring? = null
        var last = System.nanoTime()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (release !== this) return
                val dt = ((frameTimeNanos - last) / 1e9f).coerceIn(0f, MAX_STEP)
                last = max(last, frameTimeNanos)
                // someone else set a goal (a tap on a video, back): the transition is theirs
                val goal = motion.targetPosition
                val ours = when {
                    // driven by hand: the last value set (or the one before, not drawn yet)
                    s.ownsProgress && s.touched -> abs(goal - s.progress) <= 1e-4f || abs(goal - s.previous) <= 1e-4f
                    // not moved yet: still where it rested
                    s.ownsProgress -> abs(goal - s.startProgress) <= 1e-4f
                    // handed over: on its way to the release's end
                    else -> abs(goal - s.endTarget) <= 1e-4f
                }
                if (!ours) yieldTo(s)
                xSpring.step(0f, dt)
                setSideways(s, xSpring.position)
                if (s.yielded) {
                    val back = tySpring ?: YtMinimizeSprings.sideways().apply {
                        position = s.ty
                        velocity = ySpring.velocity
                    }.also { tySpring = it }
                    back.step(0f, dt)
                    s.ty = back.position
                    motion.translationY = back.position
                    if (back.isAtRest(0f) && xSpring.isAtRest(0f)) {
                        stop(reset = true)
                        return
                    }
                    choreographer.postFrameCallback(this)
                    return
                }
                ySpring.step(yTarget, dt)
                val asked = placeY(s, ySpring.position, dragging = false)
                if (s.ownsProgress) {
                    val arrived = if (target == 1) asked >= 1f - EDGE else asked <= EDGE
                    if (arrived || ySpring.isAtRest(yTarget)) finishTransition(s, target, instant = false)
                }
                if (!s.ownsProgress && ySpring.isAtRest(yTarget) && xSpring.isAtRest(0f)) {
                    stop(reset = true)
                    return
                }
                choreographer.postFrameCallback(this)
            }
        }
        release = callback
        choreographer.postFrameCallback(callback)
    }

    /**
     * Lets MotionLayout run the last thousandth itself, so its completion (and with it the page,
     * the mini player's landing and the bar) comes as from any other minimize.
     */
    private fun finishTransition(s: Session, target: Int, instant: Boolean) {
        s.ownsProgress = false
        landingTarget = target
        landingChanges = target != s.origin
        // the haptic of a landing on the bar is this drag's; the mini player plays none of its own
        if (target == 1 && landingChanges) YtMinimizeModule.landingFromDrag = true
        val transition = motion.getTransition(R.id.yt_transition)
        val duration = transition?.duration
        if (instant) transition?.duration = 1
        if (target == 1) motion.transitionToEnd() else motion.transitionToStart()
        // the rest runs as linear as the drag did (transitionTo* took the eased interpolator again)
        YtMotionAccess.drawProgressLinear(motion)
        if (instant && transition != null && duration != null) transition.duration = duration
    }

    /** Throws the docked player down off the screen with the finger's speed, then closes it. */
    private fun throwAway(s: Session, vx: Float, vy: Float) {
        closing = true
        YtMinimizeModule.closing = true
        haptics.release()
        // the bar no longer carries it (as the classic swipe down does); the page closes from
        // where it is (killPlayerFragment finishes the transition first)
        YtMiniPlayer.undock()
        s.ownsProgress = false
        if (!YtMotionPrefs.enabled) {
            stop(reset = false)
            host.close()
            return
        }
        s.tracked.getLocationInWindow(at)
        val goal = s.ty + (motion.rootView.height - at[1]) + 8f * density
        var speed = max(vy, MIN_THROW * density)
        var ty = s.ty
        val xSpring = YtMinimizeSprings.sideways().apply {
            position = s.tx
            velocity = vx
        }
        var last = System.nanoTime()
        val started = SystemClock.uptimeMillis()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (release !== this) return
                val dt = ((frameTimeNanos - last) / 1e9f).coerceIn(0f, MAX_STEP)
                last = max(last, frameTimeNanos)
                // a fall that ran too long (frames lost or late) closes from where it is
                if (YtDockMath.throwOverdue(SystemClock.uptimeMillis() - started)) {
                    log { "minimize close overdue ty=${r1(ty)}" }
                    stop(reset = false)
                    host.close()
                    return
                }
                speed += GRAVITY * density * dt
                ty += speed * dt
                s.ty = ty
                motion.translationY = ty
                xSpring.step(0f, dt)
                setSideways(s, xSpring.position)
                if (ty >= goal) {
                    log { "minimize close" }
                    stop(reset = false)
                    host.close()
                    return
                }
                choreographer.postFrameCallback(this)
            }
        }
        release = callback
        choreographer.postFrameCallback(callback)
        // the player is never left half thrown: without frames at all the time limit closes it
        motion.postDelayed({
            if (release === callback) {
                log { "minimize close without frames ty=${r1(ty)}" }
                stop(reset = false)
                host.close()
            }
        }, YtDockMath.THROW_LIMIT_MS + FRAME_GRACE_MS)
    }

    /** Ends the gesture and its release; [reset] puts the page back where the transition has it. */
    private fun stop(reset: Boolean) {
        release?.let { choreographer.removeFrameCallback(it) }
        release = null
        removeFrameCheck()
        if (reset) setOffsets(0f, 0f)
        session = null
        YtMinimizeModule.dragging = false
    }

    // --- geometry

    private fun boxOf(view: View) =
        YtBox(view.left.toFloat(), view.top.toFloat(), view.right.toFloat(), view.bottom.toFloat())

    /** The page's minimize transition rests open or docked: remember where everything is. */
    private fun remember() {
        if (!attached || motion.peeking || binding.player.parent !== motion) return
        if (motion.startState != R.id.start || motion.endState != R.id.end) return
        val p = motion.progress
        if (p != 0f && p != 1f) return
        val seen = Seen(boxOf(binding.player), boxOf(binding.mainContainer), motion.width, motion.height, generation)
        if (p == 0f) seenOpen = seen else seenDocked = seen
    }

    private fun Seen.fits() =
        YtMinimizeGeometry.seenUsable(pageWidth, pageHeight, motion.width, motion.height, generation == this@YtMinimizeDrag.generation)

    private val minTravel get() = YtMinimizeGeometry.MIN_TRAVEL_DP * density

    /**
     * The first drag of a page has only a model of where the picture docks. MotionLayout moves it
     * in a straight line, so once it is a good part of the way the layout tells the true end:
     * the rest of the drag follows that (and the next drags the place it then docks at).
     */
    private fun learnEnd() {
        val s = session ?: return
        if (s.endFrom == "measured" || s.learned || !s.ownsProgress || !s.touched) return
        // the layout MotionLayout just made is the one for this progress
        val p = motion.progress
        if (p < LEARN_FROM || p >= 1f) return
        val now = boxOf(s.tracked)
        val start = s.finger.start
        val end = YtBox(
            start.left + (now.left - start.left) / p,
            start.top + (now.top - start.top) / p,
            start.right + (now.right - start.right) / p,
            start.bottom + (now.bottom - start.bottom) / p,
        )
        val model = s.finger.end
        val f = s.finger
        // a layout that does not take the box down (not the minimize's own) teaches nothing
        if (!YtMinimizeGeometry.drivable(start, end, f.curve, minTravel)) return
        s.finger = YtUnderFinger(start, end, f.u, f.v, f.pageLeft, f.pageTop, f.curve)
        s.learned = true
        log { "minimize learn p=${r3(p)} end=${boxText(end)} model=${boxText(model)}" }
    }

    private fun geometry(fromMini: Boolean): Geometry? {
        if (motion.width == 0 || motion.peeking || binding.player.parent !== motion) return null
        if (motion.startState != R.id.start || motion.endState != R.id.end) return null
        // the minimize transition must be able to run (the classic path has the same checks)
        if (!motion.isInteractionEnabled || motion.getTransition(R.id.yt_transition)?.isEnabled == false) return null
        val activity = host.fragment().activity ?: return null
        if (activity.isInPictureInPictureMode) return null
        val container = motion.parent as? View ?: return null
        val p = motion.progress
        if (p == 0f || p == 1f) remember()
        // the docked player is held by its whole box, the video by the picture
        val tracked: View = if (fromMini) binding.mainContainer else binding.player
        val open = seenOpen?.takeIf { it.fits() } ?: return null
        val start = if (fromMini) open.box else open.player

        // the page's place in the window without the activity's shift of the container
        motion.getLocationInWindow(at)
        val shift = container.translationY
        val pageLeft = at[0] - motion.translationX
        val pageTop = at[1] - motion.translationY - shift
        val bar = activity.findViewById<View>(R.id.bottomNav)
        val curve = when {
            // the activity moves the container only with tabs (MainActivity.setPlayerContainerProgress)
            !NavBarHelper.hasTabs() -> YtContainerCurve(0f, fixed = shift)
            // docked, it is where the activity put it
            p == 1f -> YtContainerCurve(end = shift)
            // the last keyframe MainActivity writes: up by the bar, down again by the dock offset
            bar != null && bar.height > 0 -> YtContainerCurve(end = (YtMiniPlayer.containerDockPx - bar.height).toFloat())
            else -> YtContainerCurve(0f, fixed = shift)
        }
        var endFrom = "measured"
        var end: YtBox? = seenDocked?.takeIf { it.fits() }?.let { if (fromMini) it.box else it.player }
        if (end != null && !YtMinimizeGeometry.drivable(start, end, curve, minTravel)) {
            // a docked place that does not lie below the open one would hold the transition at 0
            val stale = end
            log { "minimize stale end=${boxText(stale)} start=${boxText(start)}" }
            seenDocked = null
            end = null
        }
        if (end == null) {
            if (fromMini) return null
            endFrom = "model"
            end = modelEnd(activity, container) ?: return null
            if (!YtMinimizeGeometry.drivable(start, end, curve, minTravel)) return null
        }
        return Geometry(fromMini, tracked, start, end, endFrom, pageLeft, pageTop, curve, p)
    }

    /**
     * The docked picture before the page has ever docked: the design's end rect
     * (YtMiniPlayer.endPlayerRectPx, window px at the docked shift) back in page px, or the
     * classic end set of player_scene.xml.
     */
    private fun modelEnd(activity: android.app.Activity, container: View): YtBox? {
        val rect = YtMiniPlayer.endPlayerRectPx(activity)
        if (rect != null) {
            motion.getLocationInWindow(at)
            // endPlayerRectPx puts the page at its place now, with the shift swapped for the docked one
            val bar = activity.findViewById<View>(R.id.bottomNav)
            val dockedShift = if (bar != null && bar.visibility == View.VISIBLE && bar.height > 0 && NavBarHelper.hasTabs()) {
                (YtMiniPlayer.containerDockPx - bar.height).toFloat()
            } else {
                container.translationY
            }
            val pageTop = at[1] - container.translationY + dockedShift
            val pageLeft = at[0].toFloat()
            return YtBox(rect.left - pageLeft, rect.top - pageTop, rect.right - pageLeft, rect.bottom - pageTop)
        }
        if (YtDesign.current() != YtDesign.STANDARD) return null
        // player_scene.xml's end set: a 54 dp strip at the bottom of the page, the picture 100 dp wide at its start
        val height = 54f * density
        val width = 100f * density
        val bottom = (motion.height - motion.paddingBottom).toFloat()
        val left = if (motion.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            motion.width - motion.paddingRight - width
        } else {
            motion.paddingLeft.toFloat()
        }
        return YtBox(left, bottom - height, left + width, bottom)
    }

    // --- the emulator runs' check that the point really is under the finger

    private fun removeFrameCheck() {
        val observer = motion.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(frameCheck)
    }

    private fun checkFrame() {
        val s = session ?: return
        val shown = s.shown
        val placed = s.placed ?: return
        s.shown = placed
        if (shown == null) return
        // the page lays out the progress set for this frame at its draw, after this; so what it
        // shows now is the last frame's layout with this frame's offsets. Compared both ways, so
        // an early layout pass (which shows this frame's already) is no error either
        s.tracked.getLocationInWindow(at)
        val x = at[0] + s.finger.u * s.tracked.width
        val y = at[1] + s.finger.v * s.tracked.height
        val lastFrame = y - placed[3] + shown[3] - shown[1]
        val thisFrame = y - placed[1]
        val error = if (abs(lastFrame) <= abs(thisFrame)) lastFrame else thisFrame
        val sideways = x - placed[0]
        val banded = shown[4] != 0f || placed[4] != 0f
        if (!banded) {
            s.maxError = max(s.maxError, abs(error))
            s.frames++
        }
        log {
            "minimize frame p=${r3(s.progress)} finger=${placed[0].roundToInt()},${placed[1].roundToInt()} " +
                "err=${r1(error)} ex=${r1(sideways)}${if (banded) " band" else ""}"
        }
    }

    private inline fun log(message: () -> String) = YtTestHooks.log("YtGesture", message)

    private fun r1(v: Float) = (v * 10f).roundToInt() / 10f
    private fun r2(v: Float) = (v * 100f).roundToInt() / 100f
    private fun r3(v: Float) = (v * 1000f).roundToInt() / 1000f
    private fun boxText(b: YtBox) = "${b.left.roundToInt()},${b.top.roundToInt()},${b.right.roundToInt()},${b.bottom.roundToInt()}"

    private companion object {
        /** The progress a gesture keeps from the ends while it drives, so MotionLayout does not finish under it. */
        const val EDGE = 0.001f

        /** From this progress the layout tells the docked place well enough (rounding / p < 2 px). */
        const val LEARN_FROM = 0.3f

        /** How long a classic drag may take (ms) for its tick at half way. */
        const val CLASSIC_DRAG_MS = 4000L

        /** px between finger and point the emulator check allows. */
        const val MAX_ERROR = 8f

        /** The longest step a late frame takes (s): longer gaps just go on from there. */
        const val MAX_STEP = 0.1f

        // how long after the throw's time limit the fallback without frames closes it
        const val FRAME_GRACE_MS = 100L

        /** The slowest throw (dp/s) and how it speeds up (dp/s²). */
        const val MIN_THROW = 520f
        const val GRAVITY = 2000f

        /** The landing on the bar where the design gives none (package A's YtMinimizeModule.dockTexture). */
        val DOCK = YtHapticTexture.land(0.6f)

        /** The page arriving open. */
        val OPEN = YtHapticTexture.land(0.25f)
    }
}
