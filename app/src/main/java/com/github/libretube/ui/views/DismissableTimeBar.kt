package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.TimeBar
import androidx.media3.ui.TimeBar.OnScrubListener
import com.github.libretube.R
import com.github.libretube.ui.yt.YtHaptics
import com.github.libretube.ui.yt.YtPlaybackClock
import com.github.libretube.ui.yt.YtSeekReason
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.player.YtPlayerGesturePrefs
import com.github.libretube.ui.yt.player.YtPlayerHudStyle
import com.github.libretube.ui.yt.player.YtScrubLensView
import com.github.libretube.ui.yt.player.YtScrubLevel
import com.github.libretube.ui.yt.player.YtScrubLive
import com.github.libretube.ui.yt.player.YtScrubPrecision
import com.github.libretube.ui.yt.player.YtScrubStep
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A time bar that seeks only when dragged (a tap does nothing), with YT's time magnifier
 * ("Zeit unter der Lupe"): the higher the finger above the bar, the finer the position follows it
 * (1×, ½, ¼, ⅛, then single frames), shown by the lens ([YtScrubLensView]) and, while fine, by
 * the video itself ([YtScrubLive]). Letting go seeks exactly; far below the bar, or back at the
 * start, it changes nothing.
 *
 * The bar runs the scrub itself: media3's DefaultTimeBar only draws the thumb, fed with
 * synthetic events at the bar's centre (so its own 3× fine mode never starts), and its callbacks
 * are ignored meanwhile; the listeners get the exact position from here.
 *
 * It also marks the explanation loop ([loopMarks]): a long press without moving sets A where it
 * presses, dragging on sets B with the same levels; a drag that starts on an end of the running
 * loop moves that end. Marking never seeks; the page makes the loop of it.
 */
@UnstableApi
open class DismissableTimeBar(
    context: Context,
    attributeSet: AttributeSet? = null
): DefaultTimeBar(context, attributeSet) {
    var exoPlayer: Player? = null

    private val listeners = mutableListOf<OnScrubListener>()

    /** YT: the time magnifier's lens over the controls (CustomExoPlayerView sets it). */
    var lens: YtScrubLensView? = null

    /** YT: whether the video plays from a download, so the live picture may seek anywhere. */
    var playsOffline: () -> Boolean = { false }

    /** YT: the explanation loop's marks on the bar. */
    interface LoopMarks {
        /** The running loop's ends as [a, b] in ms, or null without one. */
        fun range(): LongArray?

        /** Whether a long press may mark a new loop now. */
        fun canMark(): Boolean

        /** A mark began: [editing] an end of the running loop, or a new loop from a long press. */
        fun onMarkStart(editing: Boolean)

        /** The finger let go: [fixedMs] stayed where it was, the other end is at [movingMs]. */
        fun onMarked(fixedMs: Long, movingMs: Long, editing: Boolean)

        /** Let go back at the start or far below the bar: nothing changes. */
        fun onMarkCancelled(editing: Boolean)
    }

    /** YT: the page's explanation loop (YtLoopController sets it); null: no marking. */
    var loopMarks: LoopMarks? = null

    // Drag-only seeking state
    private var initialX: Float = 0f
    private var initialY: Float = 0f
    private var waitingForDrag: Boolean = false
    private var dragStarted: Boolean = false
    private val touchSlopPx: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    // YT: the running scrub (null outside of one: then super's own callbacks count, e.g. keys)
    private var scrub: YtScrubPrecision? = null
    private val live = YtScrubLive()
    private var haptics: YtHaptics? = null
    private var downTime = 0L
    private var lastX = 0f
    private var reportedMs = Long.MIN_VALUE
    private var lastHapticAt = 0L
    private var lensDx = 0f
    private var lensDy = 0f
    private var withHours = false

    // the lens says "Start" only once the scrub came back there, not while it leaves it
    private var leftOrigin = false

    // YT: the running loop mark (null outside of one): the end that moves, with the same levels
    private var mark: YtScrubPrecision? = null
    private var markFixedMs = 0L
    private var markEditing = false
    private var pendingHandle = HANDLE_NONE
    private val longPress = Runnable { onLongPress() }

    // as PlayerControlView sets them
    private var barDurationMs: Long = C.TIME_UNSET
    private var barPositionMs: Long = 0L

    // the thumb's padding inside the bar, as DefaultTimeBar computes it from the largest
    // scrubber size of the layout (scrubber_dragged_size 16dp in exo_styled_player_control_view)
    private val scrubberPaddingPx = ((SCRUBBER_MAX_DP * density + 0.5f).toInt() + 1) / 2

    /** YT: where the video was when the running scrub began, or -1 outside of one. */
    var scrubOriginMs: Long = -1L
        private set

    /** YT: the video itself shows the scrub's position now, so the storyboard can step aside. */
    val showsLivePicture: Boolean
        get() {
            val engine = scrub ?: return false
            return YtPlayerGesturePrefs.livePicture && engine.level.livePicture && live.covers(engine.liveTarget())
        }

    init {
        super.addListener(object : OnScrubListener {
            // a scrub by touch is told by the bar itself; these are the keys' and accessibility's
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                if (scrub != null) return
                listeners.forEach { it.onScrubStart(timeBar, position) }
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                if (scrub != null) return
                listeners.forEach { it.onScrubMove(timeBar, position) }
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                if (scrub != null) return
                listeners.forEach { it.onScrubStop(timeBar, position, canceled) }

                if (canceled) return
                exoPlayer?.seekTo(position)
            }
        })
    }

    override fun setDuration(duration: Long) {
        super.setDuration(duration)
        barDurationMs = duration
    }

    override fun setPosition(position: Long) {
        super.setPosition(position)
        barPositionMs = position
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = event.x
                initialY = event.y
                downTime = event.downTime
                waitingForDrag = true
                dragStarted = false
                // YT: on an end of the running loop a drag moves it; elsewhere a still hold marks a new one
                removeCallbacks(longPress)
                pendingHandle = handleAt(event.x)
                if (pendingHandle == HANDLE_NONE && mayMark()) postDelayed(longPress, LOOP_HOLD_MS)
                // the bar keeps the finger from the start: dragging down to cancel a scrub must
                // not minimize the player instead
                parent?.requestDisallowInterceptTouchEvent(true)
                // Consume without forwarding to prevent tap-to-seek or thumb jump
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mark != null) {
                    moveMark(event)
                    return true
                }
                if (waitingForDrag) {
                    // make sure that the user dragged at least touchSlopPx pixels
                    // which is the minimum amount to trigger a drag event
                    val dx = abs(event.x - initialX)
                    val dy = abs(event.y - initialY)
                    if (dx <= touchSlopPx && dy <= touchSlopPx) return true
                    waitingForDrag = false
                    removeCallbacks(longPress)
                    if (pendingHandle != HANDLE_NONE) {
                        if (!beginMark(event.x, event.y, editing = true)) parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                    dragStarted = beginScrub(event)
                    if (!dragStarted) parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (dragStarted) moveScrub(event)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                removeCallbacks(longPress)
                if (mark != null) {
                    endMark(event, cancelled)
                    return true
                }
                if (dragStarted) {
                    dragStarted = false
                    endScrub(event, cancelled)
                    return true
                }

                // It's a tap without drag: do nothing (prevent seek and thumb jump)
                val tap = waitingForDrag && !cancelled
                waitingForDrag = false
                parent?.requestDisallowInterceptTouchEvent(false)
                // Optionally report click for accessibility without side effects
                if (tap) performClick()
                return true
            }
        }
        // further fingers: the first one scrubs
        return true
    }

    override fun onDetachedFromWindow() {
        // a loop mark cut off changes nothing
        removeCallbacks(longPress)
        if (mark != null) {
            mark = null
            lens?.end()
            loopMarks?.onMarkCancelled(markEditing)
        }
        // the player moves into or out of fullscreen mid-scrub: nothing changes, the picture goes back
        scrub?.let { engine ->
            live.end(null, engine.originMs)
            listeners.forEach { it.onScrubStop(this, engine.originMs, true) }
            // super ends its scrub too, while it is still ignored
            toSuper(MotionEvent.ACTION_CANCEL, xFor(engine.originMs.toDouble()), SystemClock.uptimeMillis())
            scrub = null
            scrubOriginMs = -1L
            lens?.end()
        }
        dragStarted = false
        waitingForDrag = false
        super.onDetachedFromWindow()
    }

    // YT: the scrub begins once the finger left the slop; false if the bar cannot scrub now
    private fun beginScrub(event: MotionEvent): Boolean {
        val duration = barDurationMs
        val width = progressWidthPx()
        if (!isEnabled || duration <= 0L || width <= 0f) return false

        val player = exoPlayer
        val origin = player?.currentPosition?.takeIf { it >= 0L } ?: barPositionMs
        val liveStream = player?.isCurrentMediaItemLive == true
        val levels = YtPlayerGesturePrefs.magnifier && !liveStream
        locateLens()
        lens?.markLabel = null
        val lines = levelLines()
        val engine = YtScrubPrecision(
            levelStartsPx = lines,
            hysteresisPx = HYSTERESIS_DP * density,
            framePx = FRAME_DP * density,
            magnetPx = MAGNET_DP * density,
            cancelBelowPx = CANCEL_BELOW_DP * density,
            levels = levels,
        )
        // it starts where the finger touched the bar, as before; from there only the deltas count
        val startMs = ((initialX - progressLeftPx()) / width * duration).toLong().coerceIn(0L, duration)
        engine.start(startMs, origin, duration, width, frameMs(player), heightAbove(initialY), chapterStartsMs())
        scrub = engine
        scrubOriginMs = engine.originMs
        haptics = YtHaptics.of(this)
        withHours = duration >= HOUR_MS
        lastX = initialX
        lastHapticAt = 0L
        leftOrigin = !engine.atOrigin

        // super draws the thumb: a DOWN at the bar's centre starts its scrub (inside its seek bounds)
        toSuper(MotionEvent.ACTION_DOWN, xFor(engine.positionMs), event.eventTime)
        live.begin(player, engine.originMs, playsOffline())
        lens?.begin(YtPlayerHudStyle.current(context), height / 2f + lensDy, lines, levels)

        reportedMs = engine.positionMs.toLong()
        listeners.forEach { it.onScrubStart(this, reportedMs) }
        YtTestHooks.log(LOG_TAG) {
            "scrub start at=$startMs origin=${engine.originMs} duration=$duration " +
                "frameMs=${(engine.frameMs * 1000).roundToLong() / 1000.0} levels=$levels " +
                "lines=${lines.joinToString(",") { (it / density).toInt().toString() }}dp"
        }
        moveScrub(event)
        return true
    }

    private fun moveScrub(event: MotionEvent) {
        val engine = scrub ?: return
        val dx = event.x - lastX
        lastX = event.x
        val before = engine.positionMs
        val step = engine.move(dx, heightAbove(event.y))
        toSuper(MotionEvent.ACTION_MOVE, xFor(engine.positionMs), event.eventTime)

        val shown = engine.positionMs.toLong()
        if (shown != reportedMs) {
            reportedMs = shown
            listeners.forEach { it.onScrubMove(this, shown) }
        }
        if (!engine.atOrigin) leftOrigin = true
        feel(step)
        live.update(engine.level, engine.liveTarget())
        lens?.update(
            event.x + lensDx,
            event.y + lensDy,
            engine.level,
            engine.positionMs,
            engine.pxPerMs,
            engine.frameMs,
            engine.frameOffset,
            engine.atOrigin && leftOrigin,
            engine.cancelling,
            withHours,
        )
        if (step.levelChanged) {
            YtTestHooks.log(LOG_TAG) {
                "level=${engine.level.logName} h=${(heightAbove(event.y) / density).toInt()}dp pos=$shown"
            }
        }
        YtTestHooks.log(LOG_TAG) {
            "move dx=${dx.toInt()} dms=${(engine.positionMs - before).roundToLong()} " +
                "level=${engine.level.logName} pos=$shown frames=${engine.frameOffset}"
        }
    }

    private fun endScrub(event: MotionEvent, cancelled: Boolean) {
        val engine = scrub ?: return
        // where the finger lifts counts: below the bar, back at the start
        if (!cancelled) engine.move(event.x - lastX, heightAbove(event.y))
        val target = if (cancelled) null else engine.seekTarget()
        val stopAt = target ?: engine.originMs

        // exact (no SeekParameters are set): a frame lands as the lens said
        if (target != null) {
            YtPlaybackClock.tagNextSeek(YtSeekReason.USER_SCRUB)
            exoPlayer?.seekTo(target)
        }
        live.end(target, engine.originMs)
        listeners.forEach { it.onScrubStop(this, stopAt, target == null) }
        // super stops its scrub while it is still ignored
        toSuper(if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, xFor(stopAt.toDouble()), event.eventTime)
        scrub = null
        scrubOriginMs = -1L
        lens?.end()
        if (target != null && engine.level == YtScrubLevel.FRAME) haptics?.land(FRAME_LAND_WEIGHT)
        haptics = null
        YtTestHooks.log(LOG_TAG) {
            "scrub end target=${target ?: "none"} level=${engine.level.logName} " +
                "frames=${engine.frameOffset} cancelled=${cancelled || engine.cancelling} origin=${engine.atOrigin}"
        }
        invalidate()
    }

    // YT: the hold went on without moving: A is where it pressed, dragging on sets B
    private fun onLongPress() {
        if (!waitingForDrag || mark != null || scrub != null || !mayMark()) return
        waitingForDrag = false
        if (!beginMark(initialX, initialY, editing = false)) parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun mayMark(): Boolean =
        loopMarks?.canMark() == true && YtPlayerGesturePrefs.loopHold && isEnabled &&
            barDurationMs > 0L && exoPlayer?.isCurrentMediaItemLive != true

    // which end of the running loop is under [x], the nearer one if both are
    private fun handleAt(x: Float): Int {
        if (!isEnabled) return HANDLE_NONE
        val range = loopMarks?.range() ?: return HANDLE_NONE
        if (range.size < 2) return HANDLE_NONE
        val xa = xOf(range[0]) ?: return HANDLE_NONE
        val xb = xOf(range[1]) ?: return HANDLE_NONE
        val hit = HANDLE_HIT_DP * density
        val da = abs(x - xa)
        val db = abs(x - xb)
        val handle = when {
            da > hit && db > hit -> return HANDLE_NONE
            da < db -> HANDLE_A
            else -> HANDLE_B
        }
        // the thumb nearer than the end (or on it): a plain scrub, as without a loop
        val thumb = xOf(barPositionMs) ?: return handle
        return if (abs(x - thumb) <= minOf(da, db)) HANDLE_NONE else handle
    }

    private fun beginMark(x: Float, y: Float, editing: Boolean): Boolean {
        val duration = barDurationMs
        val width = progressWidthPx()
        if (duration <= 0L || width <= 0f) return false
        val range = loopMarks?.range()
        val fixed: Long
        val start: Long
        if (editing && range != null && range.size >= 2) {
            fixed = if (pendingHandle == HANDLE_A) range[1] else range[0]
            start = if (pendingHandle == HANDLE_A) range[0] else range[1]
        } else if (!editing) {
            start = ((initialX - progressLeftPx()) / width * duration).toLong().coerceIn(0L, duration)
            fixed = start
        } else {
            return false
        }
        val levels = YtPlayerGesturePrefs.magnifier
        locateLens()
        val lines = levelLines()
        val engine = YtScrubPrecision(
            levelStartsPx = lines,
            hysteresisPx = HYSTERESIS_DP * density,
            framePx = FRAME_DP * density,
            magnetPx = MAGNET_DP * density,
            cancelBelowPx = CANCEL_BELOW_DP * density,
            levels = levels,
        )
        // the moving end starts where it was: letting go there changes nothing
        engine.start(start, start, duration, width, frameMs(exoPlayer), heightAbove(y), chapterStartsMs())
        mark = engine
        markFixedMs = fixed
        markEditing = editing
        haptics = YtHaptics.of(this)
        withHours = duration >= HOUR_MS
        lastX = x
        lastHapticAt = 0L
        haptics?.grab()
        lens?.markLabel = context.getString(
            if (editing && pendingHandle == HANDLE_A) R.string.yt_r12_c_loop_mark_a else R.string.yt_r12_c_loop_mark_b,
        )
        lens?.begin(YtPlayerHudStyle.current(context), height / 2f + lensDy, lines, levels)
        loopMarks?.onMarkStart(editing)
        updateMarkLens(x, y)
        YtTestHooks.log(LOOP_LOG_TAG) { "mark start editing=$editing fixed=$fixed at=$start" }
        invalidate()
        return true
    }

    private fun moveMark(event: MotionEvent) {
        val engine = mark ?: return
        val dx = event.x - lastX
        lastX = event.x
        feel(engine.move(dx, heightAbove(event.y)))
        updateMarkLens(event.x, event.y)
        invalidate()
    }

    private fun updateMarkLens(x: Float, y: Float) {
        val engine = mark ?: return
        lens?.update(
            x + lensDx,
            y + lensDy,
            engine.level,
            engine.positionMs,
            engine.pxPerMs,
            engine.frameMs,
            engine.frameOffset,
            engine.atOrigin,
            engine.cancelling,
            withHours,
        )
    }

    private fun endMark(event: MotionEvent, cancelled: Boolean) {
        val engine = mark ?: return
        if (!cancelled) engine.move(event.x - lastX, heightAbove(event.y))
        // back at the start, far below the bar or cut off: nothing
        val target = if (cancelled) null else engine.seekTarget()
        mark = null
        lens?.end()
        haptics = null
        val editing = markEditing
        if (target == null) {
            loopMarks?.onMarkCancelled(editing)
        } else {
            loopMarks?.onMarked(markFixedMs, target, editing)
        }
        YtTestHooks.log(LOOP_LOG_TAG) { "mark end fixed=$markFixedMs to=${target ?: "none"} editing=$editing" }
        invalidate()
    }

    /** YT: while a loop mark runs, its range as [from, to] in ms (for the band), else null. */
    protected fun loopPreviewMs(): LongArray? {
        val engine = mark ?: return null
        val moving = engine.positionMs.toLong()
        return longArrayOf(minOf(markFixedMs, moving), maxOf(markFixedMs, moving))
    }

    /** YT: whether the running loop's end [handle] (0 A, 1 B) is being dragged. */
    protected fun draggedLoopEnd(): Int = when {
        mark == null || !markEditing -> -1
        pendingHandle == HANDLE_A -> 0
        else -> 1
    }

    // the strongest thing this move did, at most one small one every 30 ms
    private fun feel(step: YtScrubStep) {
        val haptics = haptics ?: return
        val now = SystemClock.uptimeMillis()
        if (step.cancel != 0) {
            // the cancel zone always answers
            haptics.threshold(step.cancel > 0)
            lastHapticAt = now
            return
        }
        val small = step.levelChanged || step.frames != 0L || step.magnet > 0 || step.limit
        if (!step.chapter && !small) return
        if (now - lastHapticAt < HAPTIC_GAP_MS) return
        lastHapticAt = now
        if (step.chapter) haptics.segment() else haptics.detent()
    }

    private fun toSuper(action: Int, x: Float, eventTime: Long) {
        val synthetic = MotionEvent.obtain(downTime, eventTime, action, x, height / 2f, 0)
        super.onTouchEvent(synthetic)
        synthetic.recycle()
    }

    private fun heightAbove(y: Float): Float = height / 2f - y

    /** YT: where the bar's progress line begins, in this view's px. */
    protected fun progressLeftPx(): Float = (paddingLeft + scrubberPaddingPx).toFloat()

    /** YT: how long the bar's progress line is, in px. */
    protected fun progressWidthPx(): Float =
        (width - paddingLeft - paddingRight - 2 * scrubberPaddingPx).toFloat()

    /** YT: the x of [positionMs] on the bar (as DefaultTimeBar draws the thumb), or null without a duration. */
    protected fun xOf(positionMs: Long): Float? {
        val duration = barDurationMs
        if (duration <= 0L) return null
        return progressLeftPx() + positionMs.coerceIn(0L, duration).toFloat() / duration * progressWidthPx()
    }

    private fun xFor(positionMs: Double): Float {
        val duration = barDurationMs.coerceAtLeast(1L)
        return progressLeftPx() + (positionMs / duration * progressWidthPx()).toFloat()
    }

    /** YT: the chapters' starts in ms, felt while scrubbing (ChapterTimeBar knows them). */
    protected open fun chapterStartsMs(): LongArray = LongArray(0)

    // the bar's place in the lens's coordinates
    private fun locateLens() {
        val lens = lens ?: return
        val bar = IntArray(2)
        val other = IntArray(2)
        getLocationInWindow(bar)
        lens.getLocationInWindow(other)
        lensDx = (bar[0] - other[0]).toFloat()
        lensDy = (bar[1] - other[1]).toFloat()
    }

    // where ½, ¼, ⅛ and the frames begin: closer in the page's small player, never above the picture
    private fun levelLines(): FloatArray {
        val lensHeight = lens?.height ?: 0
        var scale = if (lensHeight / density >= FULL_LINES_MIN_HEIGHT_DP) 1f else SMALL_LINES_SCALE
        if (lensHeight > 0) {
            val room = (height / 2f + lensDy) / density - LINES_TOP_MARGIN_DP
            if (room > 0f) scale = minOf(scale, room / LINES_DP.last()).coerceAtLeast(MIN_LINES_SCALE)
        }
        return FloatArray(LINES_DP.size) { LINES_DP[it] * scale * density }
    }

    // one frame of the playing video; 30 fps if it does not say
    private fun frameMs(player: Player?): Double {
        val tracks = player?.currentTracks ?: return YtScrubPrecision.DEFAULT_FRAME_MS
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return YtScrubPrecision.frameMsFor(group.getTrackFormat(i).frameRate)
            }
        }
        return YtScrubPrecision.DEFAULT_FRAME_MS
    }

    /**
     * DO NOT CALL THIS METHOD DIRECTLY. Use [addSeekBarListener] instead!
     */
    @Deprecated("Use addSeekBarListener instead")
    override fun addListener(listener: OnScrubListener) {
        // do nothing, see below on how listeners should be set
    }

    /**
     * DO NOT CALL THIS METHOD DIRECTLY. Use [removeSeekBarListener] instead!
     */
    @Deprecated("Use removeSeekBarListener instead")
    override fun removeListener(listener: OnScrubListener) {
        // do nothing
    }

    /**
     * Wrapper to circumvent adding the listener created by [PlayerControlView]
     */
    fun addSeekBarListener(listener: OnScrubListener) {
        listeners.add(listener)
    }

    /**
     * Wrapper to circumvent removing the listener created by [PlayerControlView]
     */
    fun removeSeekBarListener(listener: OnScrubListener) {
        listeners.remove(listener)
    }

    fun setPlayer(player: Player) {
        this.exoPlayer = player
    }

    companion object {
        private const val LOG_TAG = "YtScrub"
        private const val LOOP_LOG_TAG = "YtLoop"

        // a still hold this long on the bar marks a loop's A
        private const val LOOP_HOLD_MS = 450L

        // how near an end of the loop a touch takes that end
        private const val HANDLE_HIT_DP = 20f
        private const val HANDLE_NONE = 0
        private const val HANDLE_A = 1
        private const val HANDLE_B = 2

        // where ½, ¼, ⅛ and the frames begin above the bar, in fullscreen
        private val LINES_DP = floatArrayOf(40f, 90f, 140f, 190f)

        // the page's small player has them closer, where the thumb reaches
        private const val SMALL_LINES_SCALE = 0.75f
        private const val MIN_LINES_SCALE = 0.5f
        private const val FULL_LINES_MIN_HEIGHT_DP = 320f
        private const val LINES_TOP_MARGIN_DP = 16f

        private const val HYSTERESIS_DP = 8f
        private const val FRAME_DP = 12f
        private const val MAGNET_DP = 6f

        // letting go this far below the bar cancels (in fullscreen there is less room: the
        // magnet at the start is the other way out)
        private const val CANCEL_BELOW_DP = 48f

        private const val SCRUBBER_MAX_DP = 16f
        private const val HAPTIC_GAP_MS = 30L
        private const val FRAME_LAND_WEIGHT = 0.35f
        private const val HOUR_MS = 3_600_000L
    }
}
