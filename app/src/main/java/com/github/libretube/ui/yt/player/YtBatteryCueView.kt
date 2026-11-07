package com.github.libretube.ui.yt.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.libretube.R
import com.github.libretube.helpers.YtBatteryCue
import com.github.libretube.helpers.YtBatteryCues
import kotlin.math.max
import kotlin.math.min

/**
 * YT: the battery cue on the video. The video (the player's content frame, a SurfaceView
 * inside, which cannot be masked) is scaled in a little and back as a plain View; this view,
 * its sibling right above it, paints the room that opens around the picture in the cue's
 * colour, with rounded inner corners that also round the picture. The colour fades from the
 * level before to the cue's own. With the animations off nothing moves: only a colour frame
 * shows briefly over the picture's edge. The same in every design and in the mini player
 * (all sizes are relative to the picture).
 *
 * The frame is restored exactly (scale, pivot) when the cue ends or is cut short: when this
 * view leaves the window (fullscreen, the page goes), when the player or the picture changes
 * size (rotation, minimize drag, docking), or when the hold tiles take the frame.
 */
class YtBatteryCueView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val path = Path()
    private val outer = RectF()
    private val inner = RectF()

    private var frame: View? = null
    private var cue: YtBatteryCue? = null
    private var motion = true
    private var startedAt = 0L

    // what the frame had before the cue, put back afterwards
    private var savedScaleX = 1f
    private var savedScaleY = 1f
    private var savedPivotX = 0f
    private var savedPivotY = 0f
    private var savedPivotSet = false
    private var scaling = false
    private var startW = 0
    private var startH = 0

    private val sizeWatch = OnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
        if (cue != null && (r - l != orr - ol || b - t != ob - ot)) stop(restore = true)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    val running: Boolean get() = cue != null

    /** Plays [cue] on [frame] (the content frame, this view's sibling); [motion] false = flash only. */
    fun play(frame: View, cue: YtBatteryCue, motion: Boolean) {
        stop(restore = true)
        if (frame.width <= 0 || frame.height <= 0) return
        this.frame = frame
        this.cue = cue
        startW = frame.width
        startH = frame.height
        // only a frame nobody else transforms is scaled (the hold tiles move it themselves)
        val idle = frame.scaleX == 1f && frame.scaleY == 1f && frame.translationX == 0f &&
            frame.translationY == 0f && !holdTilesShowing()
        this.motion = motion && idle
        if (this.motion) {
            savedScaleX = frame.scaleX
            savedScaleY = frame.scaleY
            savedPivotX = frame.pivotX
            savedPivotY = frame.pivotY
            savedPivotSet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) frame.isPivotSet else true
            frame.pivotX = frame.width / 2f
            frame.pivotY = frame.height / 2f
            scaling = true
        }
        frame.addOnLayoutChangeListener(sizeWatch)
        (parent as? View)?.addOnLayoutChangeListener(sizeWatch)
        startedAt = SystemClock.uptimeMillis()
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    /** Ends the cue at once; [restore] puts the frame back as it was. */
    fun stop(restore: Boolean) {
        val frame = frame
        frame?.removeOnLayoutChangeListener(sizeWatch)
        (parent as? View)?.removeOnLayoutChangeListener(sizeWatch)
        if (frame != null && scaling && restore) {
            frame.scaleX = savedScaleX
            frame.scaleY = savedScaleY
            if (savedPivotSet) {
                frame.pivotX = savedPivotX
                frame.pivotY = savedPivotY
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                frame.resetPivot()
            }
        }
        scaling = false
        this.frame = null
        cue = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // a window change (fullscreen, the page goes) must never leave the video small
        stop(restore = true)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cue = cue ?: return
        val frame = frame ?: return
        if (scaling && holdTilesShowing()) {
            // the hold tiles took the frame: leave it to them
            scaling = false
            stop(restore = false)
            return
        }
        if (frame.width != startW || frame.height != startH) {
            stop(restore = true)
            return
        }
        val t = SystemClock.uptimeMillis() - startedAt
        val end = if (motion) YtBatteryCues.TOTAL_MS else YtBatteryCues.COLOR_FADE_MS
        if (t >= end) {
            stop(restore = true)
            return
        }
        val color = YtBatteryCues.blend(cue.startColor, cue.endColor, YtBatteryCues.colorFraction(t))
        // the frame as laid out (its transform aside), in this view's px
        outer.set(
            (frame.left - left).toFloat(),
            (frame.top - top).toFloat(),
            (frame.right - left).toFloat(),
            (frame.bottom - top).toFloat(),
        )
        val w = outer.width()
        val h = outer.height()
        val corner = min(CORNER_DP * density, CORNER_SHARE * min(w, h))

        if (motion) {
            val p = YtBatteryCues.inset(t)
            val s = 1f - (1f - YtBatteryCues.INSET_SCALE) * p
            frame.scaleX = s
            frame.scaleY = s
            inner.set(outer)
            inner.inset(w * (1f - s) / 2f, h * (1f - s) / 2f)
            drawRing(canvas, color, corner * p, 255)
            rim.color = lighten(color)
            rim.alpha = (160 * p).toInt()
            if (p > 0.01f) canvas.drawRoundRect(inner, corner * p, corner * p, rim)
        } else {
            // no movement: a frame of the colour over the picture's edge, on and off
            val stroke = (FLASH_SHARE * min(w, h)).coerceIn(4f * density, 12f * density)
            inner.set(outer)
            inner.inset(stroke, stroke)
            drawRing(canvas, color, corner, 235)
        }
        postInvalidateOnAnimation()
    }

    private fun drawRing(canvas: Canvas, color: Int, radius: Float, alpha: Int) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(outer, Path.Direction.CW)
        path.addRoundRect(inner, max(radius, 0f), max(radius, 0f), Path.Direction.CW)
        fill.color = color
        fill.alpha = alpha
        canvas.drawPath(path, fill)
    }

    private fun holdTilesShowing(): Boolean =
        (parent as? ViewGroup)?.findViewById<YtHoldTilesView>(R.id.yt_hold_tiles)?.isShowing == true

    // a light edge on the picture's side of the ring
    private fun lighten(color: Int): Int {
        fun ch(shift: Int): Int {
            val c = color shr shift and 0xFF
            return c + ((255 - c) * 0.45f).toInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        private const val CORNER_DP = 14f
        private const val CORNER_SHARE = 0.07f
        private const val FLASH_SHARE = 0.03f

        /** The cue view of [player] if it has one already. */
        fun find(player: FrameLayout): YtBatteryCueView? {
            val frame = player.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return null
            val parent = frame.parent as? ViewGroup ?: return null
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child is YtBatteryCueView) return child
            }
            return null
        }

        /** The cue view of [player] (a PlayerView), added right above its content frame on first use. */
        fun of(player: FrameLayout): Pair<YtBatteryCueView, View>? {
            val frame = player.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return null
            val parent = frame.parent as? ViewGroup ?: return null
            find(player)?.let { return it to frame }
            val view = YtBatteryCueView(player.context)
            val index = parent.indexOfChild(frame) + 1
            parent.addView(
                view,
                index,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            return view to frame
        }
    }
}
