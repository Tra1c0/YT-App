package com.github.libretube.ui.yt.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * YT: the rewind memory's chip on the video, "↩ 12:34" with a ring that runs down while it is
 * offered; a tap returns exactly to where the jump back started (YtRewindChip decides when it
 * shows and what a tap does). A View in the player's template, so it is there in fullscreen too
 * and while the controls are hidden; styled by the design (YtPlayerHudStyle).
 */
class YtReturnChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density

    /** How much of the offer's time is left, 1 to 0, read every frame for the ring. */
    var remaining: () -> Float = { 1f }

    /** Told every frame the chip's presence changes (0 gone, 1 fully there), for the bar's ghost marker. */
    var onPresence: ((Float) -> Unit)? = null

    private var style: YtPlayerHudStyle? = null
    private var label = ""
    private var labelWidth = 0f
    private var ringColor = Color.WHITE
    private var showing = false
    private var lastPresence = -1f
    private var lastFrameAt = 0L

    private val appear = YtSpring(0f)
    private val press = YtSpring(1f, stiffness = 1500f, dampingRatio = 0.7f)

    private val chipHeight = 34f * density
    private val padStart = 9f * density
    private val padEnd = 13f * density
    private val ringSize = 18f * density
    private val gap = 7f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        // digits that keep their width
        fontFeatureSettings = "tnum"
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chip = RectF()
    private val ring = RectF()
    private val arrow = Path()

    init {
        isClickable = true
        isFocusable = true
        visibility = GONE
    }

    /** Offers the way back to [time] ("12:34"); [description] is what a screen reader says. */
    fun show(time: String, description: String, style: YtPlayerHudStyle) {
        this.style = style
        label = time
        textPaint.typeface = style.typeface ?: style.mono
        labelWidth = textPaint.measureText(label)
        fillPaint.color = style.fill
        strokePaint.color = style.stroke
        strokePaint.strokeWidth = style.strokeDp * density
        val opaqueFill = ColorUtils.setAlphaComponent(style.fill, 255)
        ringColor = if (ColorUtils.calculateContrast(ColorUtils.setAlphaComponent(style.accent, 255), opaqueFill) >= 2.5) {
            style.accent
        } else {
            style.text
        }
        contentDescription = description
        // a screen reader says it when it appears
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        appear.stiffness = style.stiffness
        appear.dampingRatio = style.damping
        if (!showing && visibility != VISIBLE) appear.snapTo(0f)
        showing = true
        visibility = VISIBLE
        isEnabled = true
        if (YtPlayerHudStyle.motion) appear.animateTo(1f) else appear.snapTo(1f)
        press.snapTo(1f)
        lastFrameAt = 0L
        requestLayout()
        invalidate()
    }

    /** The offer ended (its time, a tap, a scrub): the chip goes. */
    fun hide() {
        if (!showing) return
        showing = false
        isEnabled = false
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        if (YtPlayerHudStyle.motion && visibility == VISIBLE) {
            appear.animateTo(0f)
            invalidate()
        } else {
            appear.snapTo(0f)
            gone()
        }
    }

    val isOffered: Boolean get() = showing

    private fun gone() {
        visibility = GONE
        lastFrameAt = 0L
        report(0f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (padStart + ringSize + gap + labelWidth + padEnd).roundToInt()
        setMeasuredDimension(
            resolveSize(w + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(chipHeight.roundToInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!showing) return false
        // it gives under the finger, and springs back
        val motion = YtPlayerHudStyle.motion
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (motion) press.animateTo(PRESSED_SCALE) else press.snapTo(PRESSED_SCALE)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (motion) press.animateTo(1f) else press.snapTo(1f)
        }
        invalidate()
        return super.onTouchEvent(event)
    }

    // the click itself comes from View.onTouchEvent (and from accessibility through here)
    override fun performClick(): Boolean = super.performClick()

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val style = style ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1000f
        lastFrameAt = now
        val moving = appear.step(dt) or press.step(dt)
        val presence = appear.value.coerceIn(0f, 1f)
        report(presence)
        if (!showing && presence <= 0.001f && appear.atRest) {
            gone()
            return
        }

        val alpha = presence
        val scale = (0.82f + 0.18f * appear.value) * press.value
        val save = canvas.save()
        canvas.scale(scale, scale, width / 2f, height / 2f)

        chip.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            (width - paddingRight).toFloat(),
            (height - paddingBottom).toFloat(),
        )
        val radius = style.corner * chip.height()
        fillPaint.alpha = (Color.alpha(style.fill) * alpha).toInt()
        canvas.drawRoundRect(chip, radius, radius, fillPaint)
        if (style.stroke != 0 && style.strokeDp > 0f) {
            strokePaint.alpha = (Color.alpha(style.stroke) * alpha).toInt()
            canvas.drawRoundRect(chip, radius, radius, strokePaint)
        }

        // the ring that runs down, with the way-back arrow in it
        val cx = chip.left + padStart + ringSize / 2f
        val cy = chip.centerY()
        val r = ringSize / 2f - ringPaint.strokeWidth / 2f
        ring.set(cx - r, cy - r, cx + r, cy + r)
        trackPaint.color = ColorUtils.setAlphaComponent(style.text, (0x40 * alpha).toInt())
        canvas.drawOval(ring, trackPaint)
        val left = remaining().coerceIn(0f, 1f)
        if (left > 0f) {
            ringPaint.color = ColorUtils.setAlphaComponent(ringColor, (255 * alpha).toInt())
            canvas.drawArc(ring, -90f, 360f * left, false, ringPaint)
        }
        val u = density
        arrow.rewind()
        arrow.moveTo(cx + 3.5f * u, cy + 3.5f * u)
        arrow.lineTo(cx + 3.5f * u, cy + 0.5f * u)
        arrow.quadTo(cx + 3.5f * u, cy - 2.5f * u, cx + 0.5f * u, cy - 2.5f * u)
        arrow.lineTo(cx - 3.5f * u, cy - 2.5f * u)
        arrow.moveTo(cx - 1.2f * u, cy - 4.8f * u)
        arrow.lineTo(cx - 3.5f * u, cy - 2.5f * u)
        arrow.lineTo(cx - 1.2f * u, cy - 0.2f * u)
        arrowPaint.color = ColorUtils.setAlphaComponent(style.text, (255 * alpha).toInt())
        canvas.drawPath(arrow, arrowPaint)

        textPaint.color = ColorUtils.setAlphaComponent(style.text, (255 * alpha).toInt())
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx + ringSize / 2f + gap, baseline, textPaint)
        canvas.restoreToCount(save)

        // the ring runs down all the time it is offered
        if (moving || showing) postInvalidateOnAnimation()
    }

    private fun report(presence: Float) {
        if (presence == lastPresence) return
        lastPresence = presence
        onPresence?.invoke(presence)
    }

    private companion object {
        const val PRESSED_SCALE = 0.94f
    }
}
