package com.github.libretube.ui.yt.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * YT: the compass of hold and drag on the playing video. Around where the finger holds, the four
 * targets with the icon and name of their actions; a knob follows the finger (1:1, then a rubber
 * band at the ring), the target it points at lights up past 40 dp and grows towards it, past
 * 88 dp it takes the accent: letting go then runs it, and it pops. A short pill can say what
 * happened (1,25×, Untertitel aus). A View in the player's template, so it is there in fullscreen
 * too; styled by the design (YtPlayerHudStyle). YtHoldGesture decides what it shows.
 * Not for screen readers: every action it offers has its own button elsewhere.
 */
class YtHoldCompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    /** One target: the icon and the name of its action. */
    class Target(val icon: Drawable?, val label: String)

    private val density = resources.displayMetrics.density

    private var style: YtPlayerHudStyle? = null
    private var targets: Map<YtHoldDir, Target> = emptyMap()
    private var centreText: String? = null
    private var state = YtHoldState()
    private var holding = false
    private var committed: YtHoldDir? = null
    private var resultText: String? = null
    private var resultUntil = 0L
    private var lastFrameAt = 0L

    // the finger's speed when it lets go, carried into the knob's spring
    private var lastMoveAt = 0L
    private var lastDx = 0f
    private var lastDy = 0f
    private var velocityX = 0f
    private var velocityY = 0f

    private val presence = YtSpring(0f)
    private val focus = YtSpring(0f)
    private val centreShown = YtSpring(0f)
    private val knobX = YtSpring(0f)
    private val knobY = YtSpring(0f)
    private val result = YtSpring(0f)
    private val emphasis = YtHoldDir.entries.associateWith { YtSpring(0f) }

    // the layout of one show, relative to the centre
    private var cx = 0f
    private var cy = 0f
    private var radius = MAX_RADIUS_DP * density
    private val targetRadius = TARGET_RADIUS_DP * density
    private val iconSize = ICON_DP * density
    private val labelHeight = LABEL_HEIGHT_DP * density
    private val labelPad = LABEL_PAD_DP * density
    private val gap = GAP_DP * density
    private val labelWidths = HashMap<YtHoldDir, Float>()

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val resultPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val rect = RectF()

    private val hideResult = Runnable { invalidate() }

    init {
        // invisible, not gone, at rest: it is laid out, so it knows its size when a hold begins
        visibility = INVISIBLE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    val isShowing: Boolean get() = holding

    /**
     * Shows the compass around [x], [y] (view px, where the finger holds) with [targets] (a
     * direction without one stays empty) and [centre] in the middle (2× while it plays faster).
     */
    fun show(x: Float, y: Float, targets: Map<YtHoldDir, Target>, centre: String?, style: YtPlayerHudStyle) {
        this.style = style
        this.targets = targets
        centreText = centre
        state = YtHoldState()
        holding = true
        committed = null
        resultText = null
        removeCallbacks(hideResult)
        lastMoveAt = 0L
        lastDx = 0f
        lastDy = 0f
        velocityX = 0f
        velocityY = 0f

        labelPaint.typeface = style.typeface
        centrePaint.typeface = style.mono ?: style.typeface
        resultPaint.typeface = style.typeface
        labelWidths.clear()
        for ((dir, target) in targets) labelWidths[dir] = labelPaint.measureText(target.label) + 2 * labelPad
        place(x, y)
        scrimPaint.shader = RadialGradient(
            cx, cy, radius + targetRadius + SCRIM_EXTRA_DP * density,
            intArrayOf(SCRIM_CENTRE, SCRIM_MID, Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )

        for (spring in listOf(presence, focus, centreShown, knobX, knobY, result) + emphasis.values) {
            spring.stiffness = style.stiffness
            spring.dampingRatio = style.damping
        }
        // the knob and the targets answer faster than the whole compass breathes
        knobX.stiffness = KNOB_STIFFNESS
        knobY.stiffness = KNOB_STIFFNESS
        knobX.dampingRatio = KNOB_DAMPING
        knobY.dampingRatio = KNOB_DAMPING
        knobX.snapTo(0f)
        knobY.snapTo(0f)
        emphasis.values.forEach { it.snapTo(0f) }
        focus.snapTo(0f)
        result.snapTo(0f)
        if (visibility != VISIBLE) presence.snapTo(0f)
        to(presence, 1f)
        centreShown.snapTo(if (centre != null) 1f else 0f)
        visibility = VISIBLE
        lastFrameAt = 0L
        invalidate()
    }

    /** The finger is at [dx], [dy] (px) from where it held, and the rules say [state]. */
    fun move(dx: Float, dy: Float, state: YtHoldState) {
        if (!holding) return
        val now = SystemClock.uptimeMillis()
        if (lastMoveAt != 0L && now > lastMoveAt) {
            val dt = (now - lastMoveAt) / 1000f
            velocityX = velocityX * VELOCITY_KEEP + (dx - lastDx) / dt * (1f - VELOCITY_KEEP)
            velocityY = velocityY * VELOCITY_KEEP + (dy - lastDy) / dt * (1f - VELOCITY_KEEP)
        }
        lastMoveAt = now
        lastDx = dx
        lastDy = dy

        // 1:1 near the centre, then it gives towards the ring
        val distance = hypot(dx, dy)
        val k = if (distance > 0f) YtHoldDirection.knob(distance, radius) / distance else 0f
        knobX.snapTo(dx * k)
        knobY.snapTo(dy * k)

        this.state = state
        for ((dir, spring) in emphasis) {
            val target = when {
                state.dir != dir -> 0f
                state.stage == YtHoldStage.ARMED -> 2f
                else -> 1f
            }
            to(spring, target)
        }
        to(focus, if (state.dir != null) 1f else 0f)
        invalidate()
    }

    /** The 2× in the middle goes (the finger left the centre) or comes. */
    fun centre(text: String?) {
        if (text != null) centreText = text
        to(centreShown, if (text != null) 1f else 0f)
        invalidate()
    }

    /** Let go on [dir]'s target: it pops and the compass goes. */
    fun commit(dir: YtHoldDir) {
        if (!holding) return
        holding = false
        committed = dir
        to(emphasis.getValue(dir), 3f)
        // the knob lands on the target
        val (tx, ty) = offset(dir)
        to(knobX, tx)
        to(knobY, ty)
        to(presence, 0f)
        invalidate()
    }

    /** Let go anywhere else, or the hold was taken away: everything springs back and goes. */
    fun dismiss() {
        if (!holding) return
        holding = false
        committed = null
        knobX.kick(velocityX)
        knobY.kick(velocityY)
        to(knobX, 0f)
        to(knobY, 0f)
        emphasis.values.forEach { to(it, 0f) }
        to(focus, 0f)
        to(presence, 0f)
        invalidate()
    }

    /** A short word on what the action did, in the middle, for a moment. */
    fun result(text: String) {
        val style = style ?: return
        resultText = text
        resultPaint.typeface = style.typeface
        visibility = VISIBLE
        to(result, 1f)
        resultUntil = SystemClock.uptimeMillis() + RESULT_MS
        removeCallbacks(hideResult)
        postDelayed(hideResult, RESULT_MS)
        lastFrameAt = 0L
        invalidate()
    }

    /** Gone at once (the player went away, or is locked). */
    fun clear() {
        holding = false
        committed = null
        resultText = null
        removeCallbacks(hideResult)
        presence.snapTo(0f)
        result.snapTo(0f)
        visibility = INVISIBLE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideResult)
    }

    // where the centre goes so the whole compass fits; smaller rings in the small page player
    private fun place(x: Float, y: Float) {
        val pad = EDGE_PAD_DP * density
        var r = MAX_RADIUS_DP * density
        val minR = MIN_RADIUS_DP * density
        while (true) {
            radius = r
            val (left, top, right, bottom) = extents()
            val fitsX = left + right + 2 * pad <= width
            val fitsY = top + bottom + 2 * pad <= height
            if ((fitsX && fitsY) || r <= minR) {
                cx = if (fitsX) x.coerceIn(pad + left, width - pad - right) else width / 2f
                cy = if (fitsY) y.coerceIn(pad + top, height - pad - bottom) else height / 2f
                return
            }
            r = max(minR, r - RADIUS_STEP_DP * density)
        }
    }

    // how far the compass reaches from its centre: left, top, right, bottom
    private fun extents(): FloatArray {
        var left = targetRadius
        var top = targetRadius
        var right = targetRadius
        var bottom = targetRadius
        for (dir in targets.keys) {
            val (tx, ty) = offset(dir)
            labelRect(dir, tx, ty)
            left = max(left, max(-(tx - targetRadius), -rect.left))
            right = max(right, max(tx + targetRadius, rect.right))
            top = max(top, max(-(ty - targetRadius), -rect.top))
            bottom = max(bottom, max(ty + targetRadius, rect.bottom))
        }
        return floatArrayOf(left, top, right, bottom)
    }

    private fun offset(dir: YtHoldDir): Pair<Float, Float> = when (dir) {
        YtHoldDir.UP -> 0f to -radius
        YtHoldDir.DOWN -> 0f to radius
        YtHoldDir.LEFT -> -radius to 0f
        YtHoldDir.RIGHT -> radius to 0f
    }

    // up and down have their name beside the icon, left and right below it (it fits a small player)
    private fun labelRect(dir: YtHoldDir, tx: Float, ty: Float) {
        val w = labelWidths[dir] ?: 0f
        when (dir) {
            YtHoldDir.UP, YtHoldDir.DOWN -> {
                val l = tx + targetRadius + gap
                rect.set(l, ty - labelHeight / 2f, l + w, ty + labelHeight / 2f)
            }

            YtHoldDir.LEFT, YtHoldDir.RIGHT -> {
                val t = ty + targetRadius + gap
                rect.set(tx - w / 2f, t, tx + w / 2f, t + labelHeight)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val style = style ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1000f
        lastFrameAt = now
        if (resultText != null && now >= resultUntil) to(result, 0f)

        var moving = false
        for (spring in listOf(presence, focus, centreShown, knobX, knobY, result) + emphasis.values) {
            moving = spring.step(dt) or moving
        }
        val shown = presence.value.coerceIn(0f, 1f)
        val resultShown = result.value.coerceIn(0f, 1f)
        if (!holding && shown <= 0.001f && presence.atRest && resultShown <= 0.001f && result.atRest) {
            visibility = INVISIBLE
            resultText = null
            lastFrameAt = 0L
            return
        }

        if (shown > 0.001f) drawCompass(canvas, style, shown)
        if (resultShown > 0.001f) drawResult(canvas, style, resultShown)

        if (moving || holding || resultText != null) postInvalidateOnAnimation()
    }

    private fun drawCompass(canvas: Canvas, style: YtPlayerHudStyle, shown: Float) {
        scrimPaint.alpha = (255 * shown).toInt()
        canvas.drawPaint(scrimPaint)

        val save = canvas.save()
        val scale = APPEAR_SCALE + (1f - APPEAR_SCALE) * shown
        canvas.scale(scale, scale, cx, cy)

        // the centre: a ring where the finger held, with 2× in it while it plays faster
        val centreAlpha = shown * (1f - 0.5f * focus.value.coerceIn(0f, 1f))
        ringPaint.color = ColorUtils.setAlphaComponent(style.text, (0x99 * centreAlpha).toInt())
        canvas.drawCircle(cx, cy, CENTRE_RADIUS_DP * density, ringPaint)
        val centre = centreText
        val centreShownNow = centreShown.value.coerceIn(0f, 1f)
        if (centre != null && centreShownNow > 0.001f) {
            val w = centrePaint.measureText(centre) + 2 * labelPad
            val h = labelHeight + 4f * density
            rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            fillPaint.color = style.accent
            fillPaint.alpha = (255 * shown * centreShownNow).toInt()
            canvas.drawRoundRect(rect, h / 2f, h / 2f, fillPaint)
            centrePaint.color = onColour(style.accent, (255 * shown * centreShownNow).toInt())
            canvas.drawText(centre, cx, cy - (centrePaint.descent() + centrePaint.ascent()) / 2f, centrePaint)
        }

        for ((dir, target) in targets) drawTarget(canvas, style, dir, target, shown)

        // the knob under the finger
        knobPaint.color = ColorUtils.setAlphaComponent(style.accent, (255 * shown).toInt())
        canvas.drawCircle(cx + knobX.value, cy + knobY.value, KNOB_RADIUS_DP * density, knobPaint)
        canvas.restoreToCount(save)
    }

    private fun drawTarget(canvas: Canvas, style: YtPlayerHudStyle, dir: YtHoldDir, target: Target, shown: Float) {
        val e = emphasis.getValue(dir).value
        val lit = e.coerceIn(0f, 1f)
        val armed = (e - 1f).coerceIn(0f, 1f)
        val popped = (e - 2f).coerceIn(0f, 1f)
        val others = if (state.dir != null && state.dir != dir) focus.value.coerceIn(0f, 1f) else 0f
        val alpha = shown * (1f - DIM * others)

        // a lit target leans in towards the finger
        val (ox, oy) = offset(dir)
        val lean = LEAN_DP * density * lit
        val ux = if (ox == 0f) 0f else if (ox > 0f) 1f else -1f
        val uy = if (oy == 0f) 0f else if (oy > 0f) 1f else -1f
        val tx = cx + ox - ux * lean
        val ty = cy + oy - uy * lean
        val r = targetRadius * (1f + LIT_GROW * lit + ARMED_GROW * armed + POP_GROW * popped)

        val opaque = ColorUtils.setAlphaComponent(style.fill, 255)
        val fill = ColorUtils.blendARGB(opaque, ColorUtils.setAlphaComponent(style.accent, 255), armed)
        val fillAlpha = Color.alpha(style.fill) + (255 - Color.alpha(style.fill)) * armed
        fillPaint.color = fill
        fillPaint.alpha = (fillAlpha * alpha).toInt()
        canvas.drawCircle(tx, ty, r, fillPaint)
        if (style.stroke != 0 && style.strokeDp > 0f) {
            strokePaint.color = style.stroke
            strokePaint.strokeWidth = style.strokeDp * density
            strokePaint.alpha = (Color.alpha(style.stroke) * alpha * (1f - armed)).toInt()
            canvas.drawCircle(tx, ty, r, strokePaint)
        }
        // the accent's ring grows in as it lights
        if (lit > 0.001f && armed < 0.999f) {
            ringPaint.color = ColorUtils.setAlphaComponent(style.accent, (255 * alpha * lit * (1f - armed)).toInt())
            canvas.drawCircle(tx, ty, r, ringPaint)
        }

        val iconColour = if (armed > 0.5f) onColour(style.accent, 255) else style.text
        target.icon?.let { icon ->
            val half = iconSize / 2f * (r / targetRadius)
            icon.setBounds((tx - half).toInt(), (ty - half).toInt(), (tx + half).toInt(), (ty + half).toInt())
            icon.setTint(ColorUtils.setAlphaComponent(iconColour, 255))
            icon.alpha = (255 * alpha).toInt()
            icon.draw(canvas)
        }

        // the name, on a little pill of its own so it reads on any picture
        labelRect(dir, ox, oy)
        rect.offset(cx, cy)
        val pillAlpha = alpha * (1f - popped)
        fillPaint.color = ColorUtils.setAlphaComponent(style.fill, 255)
        fillPaint.alpha = (Color.alpha(style.fill) * pillAlpha).toInt()
        val corner = style.corner * rect.height()
        canvas.drawRoundRect(rect, corner, corner, fillPaint)
        labelPaint.color = ColorUtils.setAlphaComponent(if (lit > 0.5f) style.text else style.textSecondary, (255 * pillAlpha).toInt())
        val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(target.label, rect.left + labelPad, baseline, labelPaint)
    }

    private fun drawResult(canvas: Canvas, style: YtPlayerHudStyle, shown: Float) {
        val text = resultText ?: return
        val w = resultPaint.measureText(text) + 2 * RESULT_PAD_DP * density
        val h = RESULT_HEIGHT_DP * density
        val scale = APPEAR_SCALE + (1f - APPEAR_SCALE) * result.value
        val save = canvas.save()
        canvas.scale(scale, scale, cx, cy)
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val corner = style.corner * h
        fillPaint.color = style.fill
        fillPaint.alpha = (Color.alpha(style.fill) * shown).toInt()
        canvas.drawRoundRect(rect, corner, corner, fillPaint)
        if (style.stroke != 0 && style.strokeDp > 0f) {
            strokePaint.color = style.stroke
            strokePaint.strokeWidth = style.strokeDp * density
            strokePaint.alpha = (Color.alpha(style.stroke) * shown).toInt()
            canvas.drawRoundRect(rect, corner, corner, strokePaint)
        }
        resultPaint.color = ColorUtils.setAlphaComponent(style.text, (255 * shown).toInt())
        canvas.drawText(text, cx, cy - (resultPaint.descent() + resultPaint.ascent()) / 2f, resultPaint)
        canvas.restoreToCount(save)
    }

    // black or white, whichever reads on [colour]
    private fun onColour(colour: Int, alpha: Int): Int {
        val base = if (ColorUtils.calculateLuminance(ColorUtils.setAlphaComponent(colour, 255)) > 0.5) Color.BLACK else Color.WHITE
        return ColorUtils.setAlphaComponent(base, alpha.coerceIn(0, 255))
    }

    // springs with the motion switch on, jumps without it
    private fun to(spring: YtSpring, target: Float) {
        if (YtPlayerHudStyle.motion) spring.animateTo(target) else spring.snapTo(target)
    }

    private companion object {
        const val MAX_RADIUS_DP = 76f
        const val MIN_RADIUS_DP = 48f
        const val RADIUS_STEP_DP = 4f
        const val TARGET_RADIUS_DP = 22f
        const val ICON_DP = 22f
        const val KNOB_RADIUS_DP = 7f
        const val CENTRE_RADIUS_DP = 16f
        const val LABEL_HEIGHT_DP = 20f
        const val LABEL_PAD_DP = 8f
        const val GAP_DP = 6f
        const val EDGE_PAD_DP = 8f
        const val SCRIM_EXTRA_DP = 72f
        const val RESULT_HEIGHT_DP = 34f
        const val RESULT_PAD_DP = 14f
        const val RESULT_MS = 1_100L
        const val LEAN_DP = 6f
        const val LIT_GROW = 0.08f
        const val ARMED_GROW = 0.1f
        const val POP_GROW = 0.3f
        const val DIM = 0.45f
        const val APPEAR_SCALE = 0.86f
        const val KNOB_STIFFNESS = 900f
        const val KNOB_DAMPING = 0.72f
        const val VELOCITY_KEEP = 0.4f
        const val SCRIM_CENTRE = 0x73000000
        const val SCRIM_MID = 0x40000000
    }
}
