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
import androidx.core.view.ViewCompat
import kotlin.math.roundToInt

/**
 * YT: the explanation loop's chip on the video: a loop sign and "Durchgang 2 · 0,75×", and an ×
 * that ends the loop. While the loop thinks at B, a ring runs down in place of the sign, and a tap
 * starts the next pass at once. A View in the player's template, so it is there in fullscreen too;
 * styled by the design (YtPlayerHudStyle). YtLoopController decides what it says and does.
 */
class YtLoopChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density

    /** A tap on the chip's body (the sheet, or the next pass while it thinks). */
    var onBody: (() -> Unit)? = null

    /** A tap on the × (or the screen reader's action): the loop ends. */
    var onClose: (() -> Unit)? = null

    /** While it thinks: how much of the pause is left, 1 to 0, read every frame for the ring; null otherwise. */
    var pauseLeft: (() -> Float)? = null

    private var style: YtPlayerHudStyle? = null
    private var label = ""
    private var labelWidth = 0f
    private var accentColor = Color.WHITE
    private var showing = false
    private var lastFrameAt = 0L
    private var closeActionId = -1
    private var pressedClose = false

    private val appear = YtSpring(0f)
    private val press = YtSpring(1f, stiffness = 1500f, dampingRatio = 0.7f)
    private val pausing = YtSpring(0f)

    private val chipHeight = 34f * density
    private val padStart = 9f * density
    private val iconSize = 18f * density
    private val gap = 7f * density
    private val closeWidth = 34f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        // digits that keep their width while they count
        fontFeatureSettings = "tnum"
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
    private val chip = RectF()
    private val ring = RectF()
    private val path = Path()

    init {
        isClickable = true
        isFocusable = true
        visibility = GONE
    }

    /**
     * Shows or updates the chip: [text] ("Durchgang 2 · 0,75×"), [description] for a screen
     * reader, [closeLabel] for its action that ends the loop.
     */
    fun show(text: String, description: String, closeLabel: String, style: YtPlayerHudStyle) {
        this.style = style
        val widthChanged = text.length != label.length
        label = text
        textPaint.typeface = style.typeface ?: style.mono
        labelWidth = textPaint.measureText(label)
        fillPaint.color = style.fill
        strokePaint.color = style.stroke
        strokePaint.strokeWidth = style.strokeDp * density
        val opaqueFill = ColorUtils.setAlphaComponent(style.fill, 255)
        accentColor = if (ColorUtils.calculateContrast(ColorUtils.setAlphaComponent(style.accent, 255), opaqueFill) >= 2.5) {
            style.accent
        } else {
            style.text
        }
        contentDescription = description
        if (closeActionId != -1) ViewCompat.removeAccessibilityAction(this, closeActionId)
        closeActionId = ViewCompat.addAccessibilityAction(this, closeLabel) { _, _ ->
            onClose?.invoke()
            true
        }
        appear.stiffness = style.stiffness
        appear.dampingRatio = style.damping
        pausing.stiffness = style.stiffness
        pausing.dampingRatio = style.damping
        val target = if (pauseLeft != null) 1f else 0f
        if (YtPlayerHudStyle.motion && showing) pausing.animateTo(target) else pausing.snapTo(target)
        if (!showing && visibility != VISIBLE) {
            appear.snapTo(0f)
            press.snapTo(1f)
        }
        showing = true
        visibility = VISIBLE
        isEnabled = true
        if (YtPlayerHudStyle.motion) appear.animateTo(1f) else appear.snapTo(1f)
        lastFrameAt = 0L
        if (widthChanged) requestLayout() else measureIfWider()
        invalidate()
    }

    /** The loop ended: the chip goes. */
    fun hide() {
        if (!showing) return
        showing = false
        isEnabled = false
        if (closeActionId != -1) {
            ViewCompat.removeAccessibilityAction(this, closeActionId)
            closeActionId = -1
        }
        if (YtPlayerHudStyle.motion && visibility == VISIBLE) {
            appear.animateTo(0f)
            invalidate()
        } else {
            appear.snapTo(0f)
            visibility = GONE
        }
    }

    val isOffered: Boolean get() = showing

    private fun measureIfWider() {
        if (measuredWidth < wantedWidth()) requestLayout()
    }

    private fun wantedWidth(): Int =
        (padStart + iconSize + gap + labelWidth + gap + closeWidth).roundToInt() + paddingLeft + paddingRight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(wantedWidth(), widthMeasureSpec),
            resolveSize(chipHeight.roundToInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!showing) return false
        val motion = YtPlayerHudStyle.motion
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedClose = event.x >= width - paddingRight - closeWidth
                if (motion) press.animateTo(PRESSED_SCALE) else press.snapTo(PRESSED_SCALE)
            }

            MotionEvent.ACTION_UP -> if (motion) press.animateTo(1f) else press.snapTo(1f)

            MotionEvent.ACTION_CANCEL -> {
                pressedClose = false
                if (motion) press.animateTo(1f) else press.snapTo(1f)
            }
        }
        invalidate()
        return super.onTouchEvent(event)
    }

    // the click comes from View.onTouchEvent (and from accessibility, always the body then)
    override fun performClick(): Boolean {
        val close = pressedClose
        pressedClose = false
        super.performClick()
        if (close) onClose?.invoke() else onBody?.invoke()
        return true
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val style = style ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1000f
        lastFrameAt = now
        val moving = appear.step(dt) or press.step(dt) or pausing.step(dt)
        val presence = appear.value.coerceIn(0f, 1f)
        if (!showing && presence <= 0.001f && appear.atRest) {
            visibility = GONE
            lastFrameAt = 0L
            return
        }

        val alpha = presence
        val scale = (0.82f + 0.18f * appear.value) * press.value
        val save = canvas.save()
        canvas.scale(scale, scale, width / 2f, height / 2f)

        chip.set(paddingLeft.toFloat(), paddingTop.toFloat(), (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        val radius = style.corner * chip.height()
        fillPaint.alpha = (Color.alpha(style.fill) * alpha).toInt()
        canvas.drawRoundRect(chip, radius, radius, fillPaint)
        if (style.stroke != 0 && style.strokeDp > 0f) {
            strokePaint.alpha = (Color.alpha(style.stroke) * alpha).toInt()
            canvas.drawRoundRect(chip, radius, radius, strokePaint)
        }

        val cx = chip.left + padStart + iconSize / 2f
        val cy = chip.centerY()
        val think = pausing.value.coerceIn(0f, 1f)
        val textAlpha = (255 * alpha).toInt()

        // the loop sign: an open circle with an arrow head, turning into the pause's ring
        val r = iconSize / 2f - ringPaint.strokeWidth / 2f
        ring.set(cx - r, cy - r, cx + r, cy + r)
        if (think < 0.999f) {
            linePaint.color = ColorUtils.setAlphaComponent(accentColor, (textAlpha * (1f - think)).toInt())
            val sr = r * 0.82f
            ring.set(cx - sr, cy - sr, cx + sr, cy + sr)
            canvas.drawArc(ring, -60f, 300f, false, linePaint)
            val u = density
            // the head at the arc's end (-60°)
            val hx = cx + sr * 0.5f
            val hy = cy - sr * 0.866f
            path.rewind()
            path.moveTo(hx - 3.2f * u, hy - 1.6f * u)
            path.lineTo(hx, hy)
            path.lineTo(hx - 0.6f * u, hy + 3.4f * u)
            canvas.drawPath(path, linePaint)
            ring.set(cx - r, cy - r, cx + r, cy + r)
        }
        if (think > 0.001f) {
            trackPaint.color = ColorUtils.setAlphaComponent(style.text, (0x40 * alpha * think).toInt())
            canvas.drawOval(ring, trackPaint)
            val left = pauseLeft?.invoke()?.coerceIn(0f, 1f) ?: 0f
            if (left > 0f) {
                ringPaint.color = ColorUtils.setAlphaComponent(accentColor, (textAlpha * think).toInt())
                canvas.drawArc(ring, -90f, 360f * left, false, ringPaint)
            }
        }

        textPaint.color = ColorUtils.setAlphaComponent(style.text, textAlpha)
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        val textX = cx + iconSize / 2f + gap
        canvas.drawText(label, textX, baseline, textPaint)

        // the ×, behind a hairline
        val closeLeft = chip.right - closeWidth
        linePaint.color = ColorUtils.setAlphaComponent(style.text, (0x33 * alpha).toInt())
        canvas.drawLine(closeLeft, cy - 9f * density, closeLeft, cy + 9f * density, linePaint)
        val xc = closeLeft + closeWidth / 2f - 2f * density
        val arm = 4f * density
        linePaint.color = ColorUtils.setAlphaComponent(style.textSecondary, textAlpha)
        canvas.drawLine(xc - arm, cy - arm, xc + arm, cy + arm, linePaint)
        canvas.drawLine(xc - arm, cy + arm, xc + arm, cy - arm, linePaint)
        canvas.restoreToCount(save)

        // the ring runs down all through the pause
        if (moving || (showing && pauseLeft != null)) postInvalidateOnAnimation()
    }

    private companion object {
        const val PRESSED_SCALE = 0.94f
    }
}
