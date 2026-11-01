package com.github.libretube.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.marginLeft
import androidx.media3.common.util.UnstableApi
import com.github.libretube.api.obj.Segment
import com.github.libretube.extensions.dpToPx
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.ui.yt.player.YtPlayerHudStyle
import com.github.libretube.ui.yt.player.YtSpring
import com.google.android.material.R

/**
 * TimeBar that can be marked with SponsorBlock Segments, and (YT) with the explanation loop's
 * band from A to B and a handle at each end.
 */
@UnstableApi
open class MarkableTimeBar(
    context: Context,
    attributeSet: AttributeSet? = null
) : DismissableTimeBar(context, attributeSet) {
    private var segments = listOf<Segment>()
    private var length: Int = 0

    private val progressBarHeight = 2f.dpToPx()

    // YT: the explanation loop on the bar; it fades in and out with the design's spring
    private val loopDensity = resources.displayMetrics.density
    private var loopAMs = -1L
    private var loopBMs = -1L
    private var loopStyle: YtPlayerHudStyle? = null
    private val loopPresence = YtSpring(0f)
    private val loopGrab = YtSpring(0f, stiffness = 1500f, dampingRatio = 0.7f)
    private var loopFrameAt = 0L
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val loopRect = RectF()

    /** YT: ChapterTimeBar draws the handles above its chapter breaks, so they are not cut. */
    protected var drawsLoopHandlesItself = true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawSegments(canvas)
        drawLoopBand(canvas)
        if (drawsLoopHandlesItself) drawLoopHandles(canvas)
    }

    /**
     * YT: the explanation loop from [aMs] to [bMs]; a negative [aMs] takes it away. The design's
     * accent and corners come with it.
     */
    fun setLoop(aMs: Long, bMs: Long) {
        val show = aMs >= 0L && bMs > aMs
        val motion = YtPlayerHudStyle.motion
        if (show) {
            if (loopAMs < 0L || loopStyle == null) {
                val style = YtPlayerHudStyle.current(context)
                loopStyle = style
                loopPresence.stiffness = style.stiffness
                loopPresence.dampingRatio = style.damping
            }
            loopAMs = aMs
            loopBMs = bMs
        }
        val target = if (show) 1f else 0f
        if (loopPresence.target != target) {
            if (motion) loopPresence.animateTo(target) else loopPresence.snapTo(target)
            loopFrameAt = 0L
        }
        // without motion it is gone at once; with it, it fades where it was
        if (!show && !motion) loopAMs = -1L
        invalidate()
    }

    // the band: the loop, or while an end is marked, what it will be
    private fun drawLoopBand(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (loopFrameAt == 0L) 0f else (now - loopFrameAt) / 1000f
        loopFrameAt = now
        val preview = loopPreviewMs()
        loopGrab.animateTo(if (draggedLoopEnd() >= 0 || preview != null) 1f else 0f)
        if (!YtPlayerHudStyle.motion) loopGrab.snapTo(loopGrab.target)
        val moving = loopPresence.step(dt) or loopGrab.step(dt)
        if (loopPresence.value <= 0.001f && loopPresence.target == 0f && preview == null) {
            loopAMs = -1L
            return
        }
        val range = preview ?: longArrayOf(loopAMs, loopBMs)
        if (range[0] < 0L) return
        val style = loopStyle ?: YtPlayerHudStyle.current(context).also { loopStyle = it }
        val x1 = xOf(range[0]) ?: return
        val x2 = xOf(range[1]) ?: return
        val presence = if (preview != null) 1f else loopPresence.value.coerceIn(0f, 1f)
        val cy = height / 2f
        // a little thicker than the line, thicker still while an end is held
        val half = (BAND_DP + BAND_GRAB_DP * loopGrab.value) * loopDensity / 2f * (0.6f + 0.4f * presence)
        loopRect.set(minOf(x1, x2), cy - half, maxOf(x1, x2), cy + half)
        val radius = style.corner * 2f * half
        bandPaint.color = ColorUtils.setAlphaComponent(style.accent, (BAND_ALPHA * presence).toInt())
        canvas.drawRoundRect(loopRect, radius, radius, bandPaint)
        if (moving) postInvalidateOnAnimation()
    }

    /** YT: the loop's two handles, small upright pills (square ones where the design is square). */
    protected fun drawLoopHandles(canvas: Canvas) {
        val preview = loopPreviewMs()
        val range = preview ?: longArrayOf(loopAMs, loopBMs)
        if (range[0] < 0L) return
        val presence = if (preview != null) 1f else loopPresence.value.coerceIn(0f, 1f)
        if (presence <= 0.001f) return
        val style = loopStyle ?: return
        val dragged = draggedLoopEnd()
        val cy = height / 2f
        val w = HANDLE_WIDTH_DP * loopDensity
        handleEdge.strokeWidth = loopDensity
        for (end in 0..1) {
            val x = xOf(range[end]) ?: continue
            // the held end grows under the finger
            val grow = if (end == dragged || (preview != null && dragged < 0 && end == 1)) loopGrab.value else 0f
            val h = (HANDLE_HEIGHT_DP + HANDLE_GROW_DP * grow) * loopDensity * (0.5f + 0.5f * presence)
            loopRect.set(x - w / 2f, cy - h / 2f, x + w / 2f, cy + h / 2f)
            val radius = style.corner * w
            handlePaint.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (255 * presence).toInt())
            canvas.drawRoundRect(loopRect, radius, radius, handlePaint)
            handleEdge.color = ColorUtils.setAlphaComponent(style.accent, (255 * presence).toInt())
            canvas.drawRoundRect(loopRect, radius, radius, handleEdge)
        }
    }

    private fun drawSegments(canvas: Canvas) {
        if (exoPlayer == null) return

        canvas.save()
        val horizontalOffset = (parent as View).marginLeft
        length = canvas.width - horizontalOffset * 2
        val marginY =  (canvas.height - progressBarHeight) / 2
        val themeColor = ThemeHelper.getThemeColor(context, R.attr.colorOnSecondary)

        segments.forEach {
            val (start, end) = it.segmentStartAndEnd

            canvas.drawRect(
                Rect(
                    start.toLength() + horizontalOffset,
                    marginY,
                    end.toLength() + horizontalOffset,
                    marginY + progressBarHeight
                ),
                Paint().apply {
                    color = if (PreferenceHelper.getBoolean("sb_enable_custom_colors", false)) {
                        PreferenceHelper.getInt(it.category + "_color", themeColor)
                    } else {
                        themeColor
                    }
                }
            )
        }
        canvas.restore()
    }

    private fun Float.toLength(): Int {
        return (this * 1000 / exoPlayer!!.duration * length).toInt()
    }

    fun setSegments(segments: List<Segment>) {
        this.segments = segments
    }

    fun clearSegments() {
        segments = listOf()
    }

    private companion object {
        const val BAND_DP = 6f
        const val BAND_GRAB_DP = 3f
        const val BAND_ALPHA = 0x8C
        const val HANDLE_WIDTH_DP = 4f
        const val HANDLE_HEIGHT_DP = 14f
        const val HANDLE_GROW_DP = 6f
    }
}
