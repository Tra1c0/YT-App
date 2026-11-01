package com.github.libretube.ui.yt.player

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.github.libretube.R

/**
 * YT: the token of a share drag: the share action lifted off the page, following the finger
 * above everything in the window until it reaches the time bar, where it sinks into the bar's pin.
 * Let go outside the bar, it springs back to the action with the finger's speed. Styled by the
 * design (YtPlayerHudStyle); takes no touches; removes itself once it is gone.
 */
internal class YtShareTokenView private constructor(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val hud = YtPlayerHudStyle.current(context)
    private val motion = YtPlayerHudStyle.motion

    // px springs settle at half a px; the others at a thousandth
    private val posX = YtSpring(0f, hud.stiffness, hud.damping, threshold = 0.5f)
    private val posY = YtSpring(0f, hud.stiffness, hud.damping, threshold = 0.5f)
    private val lift = YtSpring(0f, hud.stiffness, hud.damping)
    private val appear = YtSpring(0f, hud.stiffness, hud.damping)
    private val sink = YtSpring(0f, hud.stiffness, 1f)
    private val springs = arrayOf(posX, posY, lift, appear, sink)
    private var lastFrameAt = 0L

    // what it does now: follows, flies home, or goes into the bar
    private var following = true
    private var leaving = false

    private val size = TOKEN_DP * density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hud.fill }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = hud.stroke
        strokeWidth = hud.strokeDp * density
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val icon: Drawable? = AppCompatResources.getDrawable(context, R.drawable.ic_share)?.mutate()
    private val iconColor: Int
    private val location = IntArray(2)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // the accent where it reads on the token's fill, else its text colour
        val opaqueFill = ColorUtils.setAlphaComponent(hud.fill, 255)
        val accent = ColorUtils.setAlphaComponent(hud.accent, 255)
        iconColor = if (ColorUtils.calculateContrast(accent, opaqueFill) >= 2.5) accent else hud.text
        icon?.setTint(iconColor)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    // window px to this view's px
    private fun local(wx: Float, wy: Float): FloatArray {
        getLocationInWindow(location)
        return floatArrayOf(wx - location[0], wy - location[1])
    }

    private fun start(wx: Float, wy: Float) {
        val p = local(wx, wy)
        posX.snapTo(p[0])
        posY.snapTo(p[1])
        if (motion) {
            // it comes out of the action under the finger and rises above it
            appear.animateTo(1f)
            lift.animateTo(1f)
        } else {
            appear.snapTo(1f)
            lift.snapTo(1f)
        }
        invalidate()
    }

    /** The finger is at ([wx], [wy]) in window px; [overBar] sinks the token into the bar's pin. */
    fun follow(wx: Float, wy: Float, overBar: Boolean) {
        if (!following) return
        val p = local(wx, wy)
        // 1:1 with the finger; only the lift and the sinking spring
        posX.snapTo(p[0])
        posY.snapTo(p[1])
        val target = if (overBar) 1f else 0f
        if (motion) sink.animateTo(target) else sink.snapTo(target)
        invalidate()
    }

    /**
     * Let go outside the bar: back to ([wx], [wy]) where the drag began, carried on by the
     * finger's speed ([vx], [vy] px per second), then it fades into the action.
     */
    fun returnHome(wx: Float, wy: Float, vx: Float, vy: Float) {
        following = false
        if (!motion) {
            remove()
            return
        }
        val p = local(wx, wy)
        posX.animateTo(p[0])
        posX.kick(vx)
        posY.animateTo(p[1])
        posY.kick(vy)
        lift.animateTo(0f)
        sink.animateTo(0f)
        lastFrameAt = 0L
        invalidate()
    }

    /** Let go over the bar: the token goes into it. */
    fun drop() {
        following = false
        leaving = true
        if (!motion) {
            remove()
            return
        }
        sink.animateTo(1f)
        appear.animateTo(0f)
        invalidate()
    }

    /** Gone at once (the page goes). */
    fun remove() {
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1000f
        lastFrameAt = now
        var moving = false
        for (spring in springs) moving = spring.step(dt) or moving

        // home again: it fades into the action
        if (!following && !leaving && posX.atRest && posY.atRest && lift.atRest) {
            leaving = true
            appear.animateTo(0f)
            moving = true
        }
        val presence = appear.value.coerceIn(0f, 1.2f)
        if (leaving && appear.atRest && presence <= 0.01f) {
            post { remove() }
            return
        }

        val sunk = sink.value.coerceIn(0f, 1f)
        val alpha = presence.coerceAtMost(1f) * (1f - sunk)
        if (alpha > 0.004f) {
            val scale = presence * (1f - SINK_SHRINK * sunk)
            val cx = posX.value
            val cy = posY.value - lift.value * LIFT_DP * density
            val half = size / 2f * scale
            rect.set(cx - half, cy - half, cx + half, cy + half)
            val radius = hud.corner * size * scale
            // a soft shadow under it: lifted things cast one, without a blur per frame
            val drop = (2f + 4f * lift.value.coerceIn(0f, 1f)) * density
            shadowPaint.color = ColorUtils.setAlphaComponent(Color.BLACK, (0x40 * alpha).toInt())
            canvas.drawRoundRect(rect.left - density, rect.top + drop, rect.right + density, rect.bottom + drop + density, radius, radius, shadowPaint)
            fillPaint.alpha = (Color.alpha(hud.fill) * alpha).toInt()
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            if (hud.stroke != 0 && hud.strokeDp > 0f) {
                strokePaint.alpha = (Color.alpha(hud.stroke) * alpha).toInt()
                canvas.drawRoundRect(rect, radius, radius, strokePaint)
            }
            icon?.let {
                val iconHalf = ICON_DP / 2f * density * scale
                it.setBounds((cx - iconHalf).toInt(), (cy - iconHalf).toInt(), (cx + iconHalf).toInt(), (cy + iconHalf).toInt())
                it.alpha = (255 * alpha).toInt()
                it.draw(canvas)
            }
        }
        if (moving) postInvalidateOnAnimation() else lastFrameAt = 0L
    }

    companion object {
        private const val TOKEN_DP = 52f
        private const val ICON_DP = 22f

        // how far above the finger it rides, so the finger does not cover it
        private const val LIFT_DP = 64f

        // how small it gets as it sinks into the pin
        private const val SINK_SHRINK = 0.6f

        // above the player container (20 dp) and the drag layer of the designs (24 dp)
        private const val ELEVATION_DP = 26f

        /** A token at the finger ([wx], [wy], window px) over [activity]'s content, or null. */
        fun show(activity: Activity, wx: Float, wy: Float): YtShareTokenView? {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
            val view = YtShareTokenView(activity)
            view.elevation = ELEVATION_DP * view.density
            // without an outline it casts no shadow of its own
            view.outlineProvider = null
            content.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            view.start(wx, wy)
            return view
        }
    }
}
