package com.github.libretube.ui.yt.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import com.github.libretube.R
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * YT: the lens of the time magnifier ("Zeit unter der Lupe"), over the player's controls while
 * the time bar is scrubbed. A pill above the finger says the level and the time ("¼× · 12:03.4",
 * "Bild · 12:03.233 · +7") over a small ruler that stays put under the gliding finger, so the
 * finer scale can be seen; faint lines across the picture show where the next level begins.
 * Draws only while a scrub runs and takes no touches. DismissableTimeBar drives it, in this
 * view's coordinates.
 */
class YtScrubLensView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density

    private var style: YtPlayerHudStyle? = null
    private var active = false
    private var fresh = false

    // the bar and its level lines (heights above the bar's centre)
    private var barCenterY = 0f
    private var lines = FloatArray(0)
    private var levels = true

    // the scrub as last reported
    private var fingerX = 0f
    private var fingerY = 0f
    private var level = YtScrubLevel.FULL
    private var positionMs = 0.0
    private var frameMs = YtScrubPrecision.DEFAULT_FRAME_MS
    private var showPill = false
    private var showRuler = false
    private var lead = ""
    private var rest = ""
    private var leadWidth = 0f
    private var textWidth = 0f
    private var pillWidth = 0f
    private var leadColor = 0

    private val appear = YtSpring(0f)
    private val pillIn = YtSpring(0f)
    private val fine = YtSpring(0f)
    private val zoom = YtSpring(0f, stiffness = 900f, dampingRatio = 1f)
    private val offsetX = YtSpring(0f)
    private val offsetY = YtSpring(0f)
    private val springs = arrayOf(appear, pillIn, fine, zoom, offsetX, offsetY)
    private var lastFrameAt = 0L

    private val frameWord = context.getString(R.string.yt_r12_c_scrub_frame)
    private val originWord = context.getString(R.string.yt_r12_c_scrub_origin)
    private val cancelText = context.getString(R.string.yt_r12_c_scrub_cancel)
    // the marks of the levels: on the ladder, and the lens's lead ([YtScrubLevel] order from ½ on)
    private val ladderLabels = arrayOf(
        context.getString(R.string.yt_r12_c_level_half),
        context.getString(R.string.yt_r12_c_level_quarter),
        context.getString(R.string.yt_r12_c_level_eighth),
        frameWord,
    )
    private val ladderLabelWidths = FloatArray(ladderLabels.size)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(13f) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(11f) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = density
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
    }
    private val pill = RectF()

    private val pillHeightText = 30f * density
    private val pillHeightRuler = 46f * density
    private val pillPad = 14f * density
    private val pillMinWidthRuler = 112f * density
    private val fingerGap = 40f * density
    private val edge = 8f * density
    private val tickGap = 7f * density

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    /**
     * The end of the explanation loop being marked ("A", "B"): the pill names it and always shows
     * the time; null for a plain scrub. Set before [begin].
     */
    var markLabel: String? = null

    /** With [markLabel]: the time in whole seconds on every level (a share link). Until [end]. */
    var markWholeSeconds = false

    /**
     * A scrub begins. [lineHeights] are where ½, ¼, ⅛ and the frames begin, above [barCenterY];
     * [levels] false: the magnifier is off for it (a live stream), so only the start and the
     * cancel zone are told.
     */
    fun begin(style: YtPlayerHudStyle, barCenterY: Float, lineHeights: FloatArray, levels: Boolean) {
        this.style = style
        this.barCenterY = barCenterY
        lines = lineHeights.copyOf()
        this.levels = levels
        textPaint.typeface = style.mono
        labelPaint.typeface = style.typeface ?: style.mono
        for (i in ladderLabels.indices) ladderLabelWidths[i] = labelPaint.measureText(ladderLabels[i])
        fillPaint.color = style.fill
        strokePaint.color = style.stroke
        strokePaint.strokeWidth = style.strokeDp * density
        // the accent where it reads on the pill, else the pill's text colour
        val opaqueFill = ColorUtils.setAlphaComponent(style.fill, 255)
        leadColor = if (ColorUtils.calculateContrast(ColorUtils.setAlphaComponent(style.accent, 255), opaqueFill) >= 2.5) {
            style.accent
        } else {
            style.text
        }
        for (spring in springs) {
            if (spring === zoom) continue
            spring.stiffness = style.stiffness
            spring.dampingRatio = style.damping
        }
        if (YtPlayerHudStyle.motion) appear.animateTo(1f) else appear.snapTo(1f)
        pillIn.snapTo(0f)
        fine.snapTo(0f)
        level = YtScrubLevel.FULL
        active = true
        fresh = true
        lastFrameAt = 0L
        invalidate()
    }

    /**
     * The scrub moved: the finger is at ([x], [y]), the position at [positionMs] on [level];
     * [pxPerMs] is the finger travel per ms there (the ruler's scale).
     */
    fun update(
        x: Float,
        y: Float,
        level: YtScrubLevel,
        positionMs: Double,
        pxPerMs: Double,
        frameMs: Double,
        frameOffset: Long,
        atOrigin: Boolean,
        cancelling: Boolean,
        withHours: Boolean,
    ) {
        if (!active) return
        val motion = YtPlayerHudStyle.motion
        fingerX = x
        fingerY = y
        this.positionMs = positionMs
        this.frameMs = frameMs
        this.level = level

        when {
            cancelling -> {
                lead = ""
                rest = cancelText
            }

            // marking an end of the explanation loop (or a share's second): which, always with its time
            markLabel != null -> {
                lead = markLabel.orEmpty()
                val decimals = when {
                    markWholeSeconds -> 0
                    level == YtScrubLevel.FULL -> 0
                    level == YtScrubLevel.FRAME -> 3
                    else -> YtScrubPrecision.decimalsFor(1.0 / pxPerMs)
                }
                val levelPart = if (level == YtScrubLevel.FULL) "" else " · " + ladderLabels[level.ordinal - 1]
                rest = levelPart + " · " + YtScrubPrecision.formatTime(positionMs, decimals, withHours)
            }

            atOrigin -> {
                lead = originWord
                rest = " · " + YtScrubPrecision.formatTime(positionMs, 0, withHours)
            }

            level == YtScrubLevel.FRAME -> {
                lead = frameWord
                rest = " · " + YtScrubPrecision.formatTime(positionMs, 3, withHours) +
                    " · " + YtScrubPrecision.formatFrames(frameOffset)
            }

            level != YtScrubLevel.FULL -> {
                lead = ladderLabels[level.ordinal - 1]
                val decimals = YtScrubPrecision.decimalsFor(1.0 / pxPerMs)
                rest = " · " + YtScrubPrecision.formatTime(positionMs, decimals, withHours)
            }

            else -> {
                lead = ""
                rest = ""
            }
        }
        val pillWasHidden = !showPill || pillIn.value < 0.01f
        showPill = rest.isNotEmpty()
        showRuler = showPill && !cancelling && level != YtScrubLevel.FULL
        leadWidth = if (lead.isEmpty()) 0f else textPaint.measureText(lead)
        textWidth = leadWidth + textPaint.measureText(rest)
        pillWidth = maxOf(textWidth + 2 * pillPad, if (showRuler) pillMinWidthRuler else 0f)

        // the ruler's scale in log space, so a halving looks the same on every level
        val targetZoom = ln(pxPerMs.coerceAtLeast(1e-9)).toFloat()
        val targetPill = if (showPill) 1f else 0f
        val targetFine = if (level != YtScrubLevel.FULL) 1f else 0f
        if (!motion || fresh) zoom.snapTo(targetZoom) else zoom.animateTo(targetZoom)
        if (motion) {
            pillIn.animateTo(targetPill)
            fine.animateTo(targetFine)
        } else {
            pillIn.snapTo(targetPill)
            fine.snapTo(targetFine)
        }

        // above the finger, or beside it where there is no room above (the top of the small player)
        val pillH = if (showRuler) pillHeightRuler else pillHeightText
        val side = when {
            y - fingerGap - pillH >= edge -> 0
            x > width / 2f -> -1
            else -> 1
        }
        val tx = if (side == 0) 0f else side * (fingerGap + pillWidth / 2f)
        val ty = if (side == 0) -(fingerGap + pillH) else -pillH / 2f
        // the pill follows the finger 1:1; only a flip between above and beside is animated
        if (!motion || fresh || pillWasHidden) {
            offsetX.snapTo(tx)
            offsetY.snapTo(ty)
        } else {
            offsetX.animateTo(tx)
            offsetY.animateTo(ty)
        }
        fresh = false
        invalidate()
    }

    /** The scrub ended: the lens fades away. */
    fun end() {
        if (!active) return
        active = false
        markWholeSeconds = false
        if (YtPlayerHudStyle.motion) appear.animateTo(0f) else appear.snapTo(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val style = style ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1000f
        lastFrameAt = now
        var moving = false
        for (spring in springs) moving = spring.step(dt) or moving

        val alpha = appear.value.coerceIn(0f, 1f)
        if (alpha <= 0.001f) {
            lastFrameAt = 0L
            return
        }
        if (levels) drawLadder(canvas, style, alpha)
        val pillAlpha = alpha * pillIn.value.coerceIn(0f, 1f)
        if (pillAlpha > 0.001f) drawPill(canvas, style, pillAlpha)
        if (moving) postInvalidateOnAnimation()
    }

    // the faint level lines across the picture, the current band's mark in the accent
    private fun drawLadder(canvas: Canvas, style: YtPlayerHudStyle, alpha: Float) {
        val strength = alpha * (0.45f + 0.55f * fine.value.coerceIn(0f, 1f))
        val labelRight = width - 16f * density
        for (i in lines.indices) {
            if (i >= ladderLabels.size) break
            val y = barCenterY - lines[i]
            if (y < 12f * density) continue
            val current = level.ordinal == i + 1
            val entered = level.ordinal >= i + 1
            labelPaint.color = if (current) {
                ColorUtils.setAlphaComponent(style.accent, (255 * strength).toInt())
            } else {
                ColorUtils.setAlphaComponent(Color.WHITE, (255 * strength * if (entered) 0.8f else 0.55f).toInt())
            }
            linePaint.color = ColorUtils.setAlphaComponent(
                if (current) style.accent else Color.WHITE,
                (255 * strength * if (current) 0.55f else 0.2f).toInt(),
            )
            val labelWidth = ladderLabelWidths[i]
            canvas.drawLine(16f * density, y, labelRight - labelWidth - 8f * density, y, linePaint)
            canvas.drawText(ladderLabels[i], labelRight - labelWidth, y + labelPaint.textSize * 0.35f, labelPaint)
        }
    }

    private fun drawPill(canvas: Canvas, style: YtPlayerHudStyle, alpha: Float) {
        val pillH = if (showRuler) pillHeightRuler else pillHeightText
        val half = pillWidth / 2f
        val cx = if (width > pillWidth + 2 * edge) {
            (fingerX + offsetX.value).coerceIn(edge + half, width - edge - half)
        } else {
            width / 2f
        }
        val maxTop = (height - edge - pillH).coerceAtLeast(edge)
        val top = (fingerY + offsetY.value).coerceIn(edge, maxTop)
        pill.set(cx - half, top, cx + half, top + pillH)

        val save = canvas.save()
        // it grows out of the finger's side of the pill
        val scale = 0.9f + 0.1f * pillIn.value.coerceIn(0f, 1.2f)
        canvas.scale(scale, scale, pill.centerX(), pill.bottom)
        val radius = style.corner * pillHeightText
        fillPaint.alpha = (Color.alpha(style.fill) * alpha).toInt()
        canvas.drawRoundRect(pill, radius, radius, fillPaint)
        if (style.stroke != 0 && style.strokeDp > 0f) {
            strokePaint.alpha = (Color.alpha(style.stroke) * alpha).toInt()
            canvas.drawRoundRect(pill, radius, radius, strokePaint)
        }

        // the text, centred in its line at the top of the pill
        val baseline = pill.top + pillHeightText / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        var tx = pill.centerX() - textWidth / 2f
        if (lead.isNotEmpty()) {
            textPaint.color = ColorUtils.setAlphaComponent(leadColor, (255 * alpha).toInt())
            canvas.drawText(lead, tx, baseline, textPaint)
            tx += leadWidth
        }
        textPaint.color = ColorUtils.setAlphaComponent(style.text, (255 * alpha).toInt())
        canvas.drawText(rest, tx, baseline, textPaint)

        if (showRuler) drawRuler(canvas, style, alpha)
        canvas.restoreToCount(save)
    }

    // ticks that stay where they are under the gliding finger; the needle is the position
    private fun drawRuler(canvas: Canvas, style: YtPlayerHudStyle, alpha: Float) {
        val pxPerMs = exp(zoom.value.toDouble())
        val left = pill.left + 12f * density
        val right = pill.right - 12f * density
        val bottom = pill.bottom - 7f * density
        val cx = pill.centerX()
        val inFrames = level == YtScrubLevel.FRAME
        val step = YtScrubPrecision.rulerStepMs(pxPerMs, tickGap.toDouble(), frameMs.takeIf { inFrames })
        val first = floor((positionMs - (cx - left) / pxPerMs) / step).toLong()
        val last = floor((positionMs + (right - cx) / pxPerMs) / step).toLong()
        // a longer tick every whole second in the frames, else every fifth step
        val perMajor = if (inFrames && step == frameMs) (1000.0 / frameMs).roundToLong().coerceAtLeast(1L) else 5L
        val fadeWidth = 14f * density
        var k = first
        while (k <= last) {
            val x = (cx + (k * step - positionMs) * pxPerMs).toFloat()
            if (x in left..right) {
                val edgeFade = (minOf(x - left, right - x) / fadeWidth).coerceIn(0f, 1f)
                val major = k.mod(perMajor) == 0L
                tickPaint.color = ColorUtils.setAlphaComponent(
                    style.text,
                    (255 * alpha * edgeFade * if (major) 0.75f else 0.35f).toInt(),
                )
                val h = if (major) 7f * density else 4f * density
                canvas.drawLine(x, bottom - h, x, bottom, tickPaint)
            }
            k++
        }
        needlePaint.color = ColorUtils.setAlphaComponent(leadColor, (255 * alpha).toInt())
        canvas.drawLine(cx, bottom - 10f * density, cx, bottom + 1.5f * density, needlePaint)
    }
}
