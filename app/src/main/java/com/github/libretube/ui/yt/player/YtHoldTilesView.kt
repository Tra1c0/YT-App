package com.github.libretube.ui.yt.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * YT: the look of hold and drag on the playing video (package C, (d)). While a finger holds, the
 * video itself (the player's content frame, moved and scaled as a plain View, which a SurfaceView
 * follows since Android 7) springs towards the finger and shrinks to about 45 %, and keeps
 * playing; around it four tiles show the icon and name of the action bound to each direction,
 * drawn in the design's own way (YtHoldTileStyle). The tile the finger goes for grows, armed it
 * takes the design's armed colour; letting go runs it, it pops, and the video springs back to full
 * size. Everything else around the video gets the design's backdrop, which also rounds the video's
 * corners (it is drawn over them; a SurfaceView cannot be clipped). A View in the player's
 * template, so it works in fullscreen too (a Dialog without Compose owners). YtHoldGesture
 * decides what it shows. Not for screen readers: every action it offers has a button elsewhere.
 */
class YtHoldTilesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    /** One tile: the icon and the name of its action. */
    class Target(val icon: Drawable?, val label: String)

    private val density = resources.displayMetrics.density

    private var style: YtHoldTileStyle? = null
    private var frame: View? = null
    private var transformed = false
    private var targets: Map<YtHoldDir, Target> = emptyMap()
    private val lines = HashMap<YtHoldDir, List<String>>()
    private var centreText: String? = null
    private var state = YtHoldState()
    private var holding = false
    private var resultText: String? = null
    private var resultUntil = 0L
    private var lastFrameAt = 0L
    private var shownAt = 0L

    // the finger's speed when it lets go, carried into the video's spring back
    private var lastMoveAt = 0L
    private var lastDx = 0f
    private var lastDy = 0f
    private var velocityX = 0f
    private var velocityY = 0f

    /** The backdrop and the tiles. */
    private val presence = YtSpring(0f)

    /** The video: 0 full size, 1 shrunk under the finger. */
    private val shrink = YtSpring(0f)
    private val leanX = YtSpring(0f)
    private val leanY = YtSpring(0f)
    private val focus = YtSpring(0f)
    private val centreShown = YtSpring(0f)
    private val result = YtSpring(0f)
    private val emphasis = YtHoldDir.entries.associateWith { YtSpring(0f) }
    private val springs = listOf(presence, shrink, leanX, leanY, focus, centreShown, result) + emphasis.values

    // the layout of one hold, in this view's px
    private var small = YtHoldBox(0f, 0f, 0f, 0f)
    private var tiles: Map<YtHoldDir, YtHoldBox> = emptyMap()

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12.5f, resources.displayMetrics)
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val resultPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val path = Path()
    private val extra = Path()
    private val measure = PathMeasure()
    private val point = FloatArray(2)
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

    /** Where the shrunk video and the tiles go (px, for the test log): "video=l t r b tiles=UP,DOWN". */
    val layoutSummary: String
        get() = "video=${small.left.toInt()} ${small.top.toInt()} ${small.right.toInt()} ${small.bottom.toInt()} " +
            "tiles=${tiles.keys.joinToString(",")} shape=${style?.shape}"

    /**
     * A hold began at [x], [y] (this view's px): [frame] (the video's content frame, a sibling of
     * this view) shrinks towards it, with a tile for each of [targets] around it, and [centre] on
     * the video (2× while it plays faster).
     */
    fun show(x: Float, y: Float, frame: View, targets: Map<YtHoldDir, Target>, centre: String?, style: YtHoldTileStyle) {
        this.style = style
        this.frame = frame
        this.targets = targets
        centreText = centre
        state = YtHoldState()
        holding = true
        resultText = null
        removeCallbacks(hideResult)
        lastMoveAt = 0L
        lastDx = 0f
        lastDy = 0f
        velocityX = 0f
        velocityY = 0f
        labelPaint.typeface = style.typeface
        resultPaint.typeface = style.typeface
        badgePaint.typeface = style.typeface

        val w = width.toFloat()
        val h = height.toFloat()
        val sizes = measureTiles(w)
        val gap = GAP_DP * density
        val pad = EDGE_PAD_DP * density
        fun room(dir: YtHoldDir, horizontal: Boolean) =
            sizes[dir]?.let { (if (horizontal) it.first else it.second) + gap } ?: 0f
        val room = YtHoldBox(
            room(YtHoldDir.LEFT, true), room(YtHoldDir.UP, false),
            room(YtHoldDir.RIGHT, true), room(YtHoldDir.DOWN, false),
        )
        small = YtHoldLayout.video(w, h, full(frame), x, y, room, pad)
        tiles = YtHoldLayout.tiles(w, h, small, sizes, gap, pad)

        for (spring in springs) {
            spring.stiffness = style.stiffness
            spring.dampingRatio = style.damping
        }
        // the video answers the finger faster than the room around it changes
        for (spring in listOf(leanX, leanY)) {
            spring.stiffness = LEAN_STIFFNESS
            spring.dampingRatio = LEAN_DAMPING
        }
        leanX.snapTo(0f)
        leanY.snapTo(0f)
        emphasis.values.forEach { it.snapTo(0f) }
        focus.snapTo(0f)
        result.snapTo(0f)
        if (visibility != VISIBLE) {
            presence.snapTo(0f)
            shrink.snapTo(0f)
        }
        to(presence, 1f)
        to(shrink, 1f)
        centreShown.snapTo(if (centre != null) 1f else 0f)
        // moved and scaled from its top left corner; the box says where that goes
        frame.pivotX = 0f
        frame.pivotY = 0f
        transformed = true
        visibility = VISIBLE
        shownAt = SystemClock.uptimeMillis()
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

        // the video leans after the finger, a rubber band that never goes far
        val distance = hypot(dx, dy)
        val k = if (distance > 0f) YtHoldDirection.knob(distance, LEAN_DP * density) / distance else 0f
        to(leanX, dx * k)
        to(leanY, dy * k)

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

    /** The 2× on the video goes (the finger left the centre) or comes. */
    fun centre(text: String?) {
        if (text != null) centreText = text
        to(centreShown, if (text != null) 1f else 0f)
        invalidate()
    }

    /** Let go on [dir]'s tile: it pops, the room goes and the video springs back. */
    fun commit(dir: YtHoldDir) {
        if (!holding) return
        holding = false
        to(emphasis.getValue(dir), 3f)
        back()
    }

    /** Let go anywhere else, or the hold was taken away: everything springs back. */
    fun dismiss() {
        if (!holding) return
        holding = false
        emphasis.values.forEach { to(it, 0f) }
        // the release's speed carries into the video's way back
        if (YtPlayerHudStyle.motion) {
            leanX.kick(velocityX * KICK)
            leanY.kick(velocityY * KICK)
        }
        back()
    }

    private fun back() {
        to(leanX, 0f)
        to(leanY, 0f)
        to(focus, 0f)
        to(presence, 0f)
        to(shrink, 0f)
        lastFrameAt = 0L
        invalidate()
    }

    /** A short word on what the action did, in the middle, for a moment. */
    fun result(text: String) {
        if (style == null) return
        resultText = text
        visibility = VISIBLE
        to(result, 1f)
        resultUntil = SystemClock.uptimeMillis() + RESULT_MS
        removeCallbacks(hideResult)
        postDelayed(hideResult, RESULT_MS)
        lastFrameAt = 0L
        invalidate()
    }

    /** Gone at once, the video back at full size (the player went away, or is locked). */
    fun clear() {
        holding = false
        resultText = null
        removeCallbacks(hideResult)
        for (spring in springs) spring.snapTo(0f)
        resetFrame()
        visibility = INVISIBLE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideResult)
        // a window change (fullscreen, a sheet) must never leave the video small
        if (!holding) {
            for (spring in springs) spring.snapTo(0f)
            resultText = null
            visibility = INVISIBLE
        }
        resetFrame()
    }

    // the frame as laid out (its transform aside), in this view's px
    private fun full(frame: View): YtHoldBox {
        val dx = -left.toFloat()
        val dy = -top.toFloat()
        return YtHoldBox(frame.left + dx, frame.top + dy, frame.right + dx, frame.bottom + dy)
    }

    private fun resetFrame() {
        val frame = frame ?: return
        if (!transformed) return
        transformed = false
        frame.scaleX = 1f
        frame.scaleY = 1f
        frame.translationX = 0f
        frame.translationY = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            frame.resetPivot()
        } else {
            frame.pivotX = frame.width / 2f
            frame.pivotY = frame.height / 2f
        }
    }

    // the size of each tile (width to height): up and down a row of icon and name, left and right
    // the icon above a name of up to two lines, as narrow as it can be
    private fun measureTiles(viewW: Float): Map<YtHoldDir, Pair<Float, Float>> {
        lines.clear()
        val icon = ICON_DP * density
        val padH = PAD_H_DP * density
        val sidePad = SIDE_PAD_DP * density
        val lineH = labelPaint.fontSpacing
        val rowMax = min(ROW_MAX_DP * density, viewW - 2 * EDGE_PAD_DP * density)
        val sideMax = SIDE_MAX_DP * density
        val sizes = HashMap<YtHoldDir, Pair<Float, Float>>()
        for ((dir, target) in targets) {
            when (dir) {
                YtHoldDir.UP, YtHoldDir.DOWN -> {
                    val textMax = rowMax - 2 * padH - icon - ICON_GAP_DP * density
                    val text = YtHoldLayout.wrap(target.label, textMax, 1) { labelPaint.measureText(it) }
                    lines[dir] = text
                    val textW = text.maxOfOrNull { labelPaint.measureText(it) } ?: 0f
                    val gapW = if (text.isEmpty()) 0f else ICON_GAP_DP * density
                    sizes[dir] = (2 * padH + icon + gapW + textW) to ROW_HEIGHT_DP * density
                }

                YtHoldDir.LEFT, YtHoldDir.RIGHT -> {
                    val text = YtHoldLayout.wrap(target.label, sideMax - 2 * sidePad, 2) { labelPaint.measureText(it) }
                    lines[dir] = text
                    val textW = text.maxOfOrNull { labelPaint.measureText(it) } ?: 0f
                    val w = (max(icon, textW) + 2 * sidePad).coerceIn(SIDE_MIN_DP * density, sideMax)
                    val h = 2 * sidePad + icon + (if (text.isEmpty()) 0f else ICON_GAP_DP * density / 2f + lineH * text.size)
                    sizes[dir] = w to h
                }
            }
        }
        return sizes
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val style = style ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1000f
        lastFrameAt = now
        if (resultText != null && now >= resultUntil) to(result, 0f)

        var moving = false
        for (spring in springs) {
            moving = spring.step(dt) or moving
        }
        val frame = frame
        val p = shrink.value
        val full = frame?.let { full(it) }
        val video = if (full != null) {
            YtHoldBox.lerp(full, small.offset(leanX.value, leanY.value), p)
        } else {
            small
        }
        if (frame != null && full != null && transformed && full.width > 0f && full.height > 0f) {
            frame.scaleX = video.width / full.width
            frame.scaleY = video.height / full.height
            frame.translationX = video.left - full.left
            frame.translationY = video.top - full.top
        }

        val shown = presence.value.coerceIn(0f, 1f)
        val resultShown = result.value.coerceIn(0f, 1f)
        val settled = !holding && shown <= 0.001f && presence.atRest && shrink.atRest && abs(p) <= 0.001f
        if (settled && resultShown <= 0.001f && result.atRest) {
            resetFrame()
            visibility = INVISIBLE
            resultText = null
            lastFrameAt = 0L
            return
        }
        if (settled) resetFrame()

        if (shown > 0.001f) {
            drawBackdrop(canvas, style, video, shown, p)
            for ((dir, target) in targets) {
                val box = tiles[dir] ?: continue
                drawTile(canvas, style, dir, target, box, video, shown, now)
            }
            drawBadge(canvas, style, video, shown)
        }
        if (resultShown > 0.001f) drawResult(canvas, style, resultShown)

        // Pop's blobs wobble on while they show
        val wobbling = style.shape == YtHoldTileShape.BLOB && shown > 0.001f
        if (moving || holding || resultText != null || wobbling) postInvalidateOnAnimation()
    }

    // the design's room around the video; it rounds the video's corners as it draws over them
    private fun drawBackdrop(canvas: Canvas, style: YtHoldTileStyle, video: YtHoldBox, shown: Float, p: Float) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        val corner = style.videoCornerDp * density * p.coerceIn(0f, 1f)
        rect.set(video.left, video.top, video.right, video.bottom)
        path.addRoundRect(rect, corner, corner, Path.Direction.CW)
        backdropPaint.color = ColorUtils.setAlphaComponent(style.backdrop, (BACKDROP_ALPHA * shown).toInt())
        canvas.drawPath(path, backdropPaint)
    }

    private fun drawTile(
        canvas: Canvas,
        style: YtHoldTileStyle,
        dir: YtHoldDir,
        target: Target,
        box: YtHoldBox,
        video: YtHoldBox,
        shown: Float,
        now: Long,
    ) {
        val e = emphasis.getValue(dir).value
        val lit = e.coerceIn(0f, 1f)
        val armed = (e - 1f).coerceIn(0f, 1f)
        val popped = (e - 2f).coerceIn(0f, 1f)
        val others = if (state.dir != null && state.dir != dir) focus.value.coerceIn(0f, 1f) else 0f
        val alpha = (shown * (1f - DIM * others)).coerceIn(0f, 1f)
        if (alpha <= 0.001f) return

        // it comes out of the video's edge, and a lit tile leans towards the video
        val (ux, uy) = when (dir) {
            YtHoldDir.UP -> 0f to -1f
            YtHoldDir.DOWN -> 0f to 1f
            YtHoldDir.LEFT -> -1f to 0f
            YtHoldDir.RIGHT -> 1f to 0f
        }
        val out = -(1f - shown) * APPEAR_DP * density - lit * LEAN_TILE_DP * density
        val scale = (APPEAR_SCALE + (1f - APPEAR_SCALE) * shown) *
            (1f + LIT_GROW * lit + ARMED_GROW * armed + POP_GROW * popped)

        val save = canvas.save()
        canvas.translate(ux * out, uy * out)
        canvas.scale(scale, scale, box.centerX, box.centerY)
        rect.set(box.left, box.top, box.right, box.bottom)
        val a = (255 * alpha).toInt()
        val fill = ColorUtils.blendARGB(style.fill(dir), style.armedFill, armed)
        val ink = style.on(fill)
        val corner = if (style.cornerDp < 0f) min(rect.width(), rect.height()) / 2f else style.cornerDp * density

        outline(style, dir, corner, now)

        // shadow first, under the tile
        if (style.shadow != 0) {
            val lift = if (style.shape == YtHoldTileShape.PRINT) 1f + lit else 1f
            shadowPaint.color = style.shadow
            shadowPaint.alpha = (Color.alpha(style.shadow) * alpha).toInt()
            val save2 = canvas.save()
            canvas.translate(style.shadowDxDp * density * lift, style.shadowDyDp * density * lift)
            canvas.drawPath(path, shadowPaint)
            if (style.shape == YtHoldTileShape.COOKIE) canvas.drawPath(extra, shadowPaint)
            canvas.restoreToCount(save2)
        }

        fillPaint.color = fill
        fillPaint.alpha = a
        canvas.drawPath(path, fillPaint)
        if (style.shape == YtHoldTileShape.COOKIE) canvas.drawPath(extra, fillPaint)

        when (style.shape) {
            YtHoldTileShape.FILM -> sprockets(canvas, style, a)
            YtHoldTileShape.GLASS -> {
                glossPaint.shader = LinearGradient(
                    0f, rect.top, 0f, rect.centerY(),
                    ColorUtils.setAlphaComponent(Color.WHITE, (0x8C * alpha * (1f - armed)).toInt()), Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawPath(path, glossPaint)
            }
            else -> Unit
        }

        // the design's edge (Movo's white frame only once armed)
        if (style.stroke != 0 && style.strokeDp > 0f) {
            val strokeShown = if (style.shape == YtHoldTileShape.TILE) armed else 1f
            if (strokeShown > 0.001f) {
                strokePaint.color = style.stroke
                strokePaint.strokeWidth = style.strokeDp * density
                strokePaint.alpha = (Color.alpha(style.stroke) * alpha * strokeShown).toInt()
                canvas.drawPath(path, strokePaint)
            }
        }
        // lit: the accent's ring grows in, until the armed colour takes over
        val ringShown = lit * (1f - armed)
        if (ringShown > 0.001f && style.shape != YtHoldTileShape.PRINT) {
            ringPaint.color = if (style.shape == YtHoldTileShape.TILE) style.on(style.fill(dir)) else style.accent
            ringPaint.alpha = (255 * alpha * ringShown).toInt()
            canvas.drawPath(path, ringPaint)
        }

        drawContent(canvas, dir, target, ink, a)
        canvas.restoreToCount(save)
    }

    // the outline of a tile in [rect] into [path] (a cookie's bumps into [extra])
    private fun outline(style: YtHoldTileStyle, dir: YtHoldDir, corner: Float, now: Long) {
        path.reset()
        path.fillType = Path.FillType.WINDING
        extra.reset()
        when (style.shape) {
            YtHoldTileShape.BLOB -> blob(now)
            YtHoldTileShape.COOKIE -> {
                val bump = BUMP_DP * density
                rect.inset(bump * 0.6f, bump * 0.6f)
                val c = min(rect.width(), rect.height()) / 2f
                path.addRoundRect(rect, c, c, Path.Direction.CW)
                measure.setPath(path, true)
                val length = measure.length
                val count = max(8, (length / (bump * 1.7f)).toInt())
                for (i in 0 until count) {
                    if (measure.getPosTan(length * i / count, point, null)) {
                        extra.addCircle(point[0], point[1], bump, Path.Direction.CW)
                    }
                }
                rect.inset(-bump * 0.6f, -bump * 0.6f)
            }
            YtHoldTileShape.BUBBLE -> {
                path.addRoundRect(rect, corner, corner, Path.Direction.CW)
                val t = TAIL_DP * density
                extra.reset()
                when (dir) {
                    YtHoldDir.UP -> tail(rect.centerX() - t, rect.bottom - 1f, rect.centerX() + t, rect.bottom - 1f, rect.centerX(), rect.bottom + t)
                    YtHoldDir.DOWN -> tail(rect.centerX() - t, rect.top + 1f, rect.centerX() + t, rect.top + 1f, rect.centerX(), rect.top - t)
                    YtHoldDir.LEFT -> tail(rect.right - 1f, rect.centerY() - t, rect.right - 1f, rect.centerY() + t, rect.right + t, rect.centerY())
                    YtHoldDir.RIGHT -> tail(rect.left + 1f, rect.centerY() - t, rect.left + 1f, rect.centerY() + t, rect.left - t, rect.centerY())
                }
                path.op(extra, Path.Op.UNION)
                extra.reset()
            }
            else -> path.addRoundRect(rect, corner, corner, Path.Direction.CW)
        }
    }

    private fun tail(x1: Float, y1: Float, x2: Float, y2: Float, tipX: Float, tipY: Float) {
        extra.moveTo(x1, y1)
        extra.lineTo(tipX, tipY)
        extra.lineTo(x2, y2)
        extra.close()
    }

    // Pop's blob: a squircle whose edge wobbles slowly (still with the motion switch off)
    private fun blob(now: Long) {
        val phase = if (YtPlayerHudStyle.motion) ((now - shownAt) % BLOB_PERIOD_MS) / BLOB_PERIOD_MS.toFloat() * TWO_PI else 0f
        val cx = rect.centerX()
        val cy = rect.centerY()
        val hw = rect.width() / 2f
        val hh = rect.height() / 2f
        for (i in 0 until BLOB_POINTS) {
            val theta = TWO_PI * i / BLOB_POINTS
            val c = cos(theta)
            val s = sin(theta)
            val ex = sign(c) * abs(c).pow(SQUIRCLE)
            val ey = sign(s) * abs(s).pow(SQUIRCLE)
            val r = 1f - BLOB_INSET + BLOB_A * sin(3f * theta + phase) + BLOB_B * sin(5f * theta - 1.3f * phase)
            val x = cx + hw * ex * r
            val y = cy + hh * ey * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    // After's film: holes along the long edges, in the room's colour
    private fun sprockets(canvas: Canvas, style: YtHoldTileStyle, alpha: Int) {
        holePaint.color = style.backdrop
        holePaint.alpha = alpha
        val hw = HOLE_W_DP * density
        val hh = HOLE_H_DP * density
        val inset = HOLE_INSET_DP * density
        val step = HOLE_STEP_DP * density
        val r = hh / 3f
        if (rect.width() >= rect.height()) {
            var x = rect.left + inset * 2
            while (x + hw <= rect.right - inset * 2) {
                canvas.drawRoundRect(x, rect.top + inset, x + hw, rect.top + inset + hh, r, r, holePaint)
                canvas.drawRoundRect(x, rect.bottom - inset - hh, x + hw, rect.bottom - inset, r, r, holePaint)
                x += step
            }
        } else {
            var y = rect.top + inset * 2
            while (y + hw <= rect.bottom - inset * 2) {
                canvas.drawRoundRect(rect.left + inset, y, rect.left + inset + hh, y + hw, r, r, holePaint)
                canvas.drawRoundRect(rect.right - inset - hh, y, rect.right - inset, y + hw, r, r, holePaint)
                y += step
            }
        }
    }

    // the icon and the name: a row for up and down, stacked for left and right
    private fun drawContent(canvas: Canvas, dir: YtHoldDir, target: Target, ink: Int, alpha: Int) {
        val icon = ICON_DP * density
        val text = lines[dir].orEmpty()
        labelPaint.color = ColorUtils.setAlphaComponent(ink, alpha)
        when (dir) {
            YtHoldDir.UP, YtHoldDir.DOWN -> {
                val textW = text.firstOrNull()?.let { labelPaint.measureText(it) } ?: 0f
                val gap = if (text.isEmpty()) 0f else ICON_GAP_DP * density
                val left = rect.centerX() - (icon + gap + textW) / 2f
                drawIcon(canvas, target.icon, left, rect.centerY() - icon / 2f, icon, ink, alpha)
                text.firstOrNull()?.let {
                    labelPaint.textAlign = Paint.Align.LEFT
                    val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
                    canvas.drawText(it, left + icon + gap, baseline, labelPaint)
                }
            }

            YtHoldDir.LEFT, YtHoldDir.RIGHT -> {
                val lineH = labelPaint.fontSpacing
                val textH = if (text.isEmpty()) 0f else ICON_GAP_DP * density / 2f + lineH * text.size
                val top = rect.centerY() - (icon + textH) / 2f
                drawIcon(canvas, target.icon, rect.centerX() - icon / 2f, top, icon, ink, alpha)
                labelPaint.textAlign = Paint.Align.CENTER
                var baseline = top + icon + ICON_GAP_DP * density / 2f - labelPaint.ascent()
                for (line in text) {
                    canvas.drawText(line, rect.centerX(), baseline, labelPaint)
                    baseline += lineH
                }
            }
        }
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable?, left: Float, top: Float, size: Float, ink: Int, alpha: Int) {
        icon ?: return
        icon.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
        icon.setTint(ColorUtils.setAlphaComponent(ink, 255))
        icon.alpha = alpha
        icon.draw(canvas)
    }

    // 2× on the video's top corner while it plays faster
    private fun drawBadge(canvas: Canvas, style: YtHoldTileStyle, video: YtHoldBox, shown: Float) {
        val text = centreText ?: return
        val visible = centreShown.value.coerceIn(0f, 1f) * shown
        if (visible <= 0.001f) return
        val pad = BADGE_PAD_DP * density
        val h = BADGE_HEIGHT_DP * density
        val w = badgePaint.measureText(text) + 2 * pad
        val inset = BADGE_INSET_DP * density
        rect.set(video.left + inset, video.top + inset, video.left + inset + w, video.top + inset + h)
        fillPaint.color = style.accent
        fillPaint.alpha = (255 * visible).toInt()
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fillPaint)
        badgePaint.color = ColorUtils.setAlphaComponent(style.on(style.accent), (255 * visible).toInt())
        canvas.drawText(text, rect.centerX(), rect.centerY() - (badgePaint.descent() + badgePaint.ascent()) / 2f, badgePaint)
    }

    // what the action did, on an opaque pill of the design in the middle
    private fun drawResult(canvas: Canvas, style: YtHoldTileStyle, shown: Float) {
        val text = resultText ?: return
        val w = resultPaint.measureText(text) + 2 * RESULT_PAD_DP * density
        val h = RESULT_HEIGHT_DP * density
        val scale = APPEAR_SCALE + (1f - APPEAR_SCALE) * result.value
        val cx = width / 2f
        val cy = height / 2f
        val save = canvas.save()
        canvas.scale(scale, scale, cx, cy)
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val fill = style.fill(YtHoldDir.UP)
        val corner = if (style.cornerDp < 0f) h / 2f else min(style.cornerDp * density, h / 2f)
        fillPaint.color = fill
        fillPaint.alpha = (255 * shown).toInt()
        canvas.drawRoundRect(rect, corner, corner, fillPaint)
        if (style.stroke != 0 && style.strokeDp > 0f && style.shape != YtHoldTileShape.TILE) {
            strokePaint.color = style.stroke
            strokePaint.strokeWidth = style.strokeDp * density
            strokePaint.alpha = (Color.alpha(style.stroke) * shown).toInt()
            canvas.drawRoundRect(rect, corner, corner, strokePaint)
        }
        resultPaint.color = ColorUtils.setAlphaComponent(style.on(fill), (255 * shown).toInt())
        canvas.drawText(text, cx, cy - (resultPaint.descent() + resultPaint.ascent()) / 2f, resultPaint)
        canvas.restoreToCount(save)
    }

    // springs with the motion switch on, jumps without it
    private fun to(spring: YtSpring, target: Float) {
        if (YtPlayerHudStyle.motion) spring.animateTo(target) else spring.snapTo(target)
    }

    private companion object {
        const val GAP_DP = 10f
        const val EDGE_PAD_DP = 8f
        const val ICON_DP = 20f
        const val ICON_GAP_DP = 8f
        const val PAD_H_DP = 14f
        const val ROW_HEIGHT_DP = 42f
        const val ROW_MAX_DP = 260f
        const val SIDE_PAD_DP = 10f
        const val SIDE_MIN_DP = 60f
        const val SIDE_MAX_DP = 100f
        const val LEAN_DP = 18f
        const val LEAN_TILE_DP = 4f
        const val LEAN_STIFFNESS = 900f
        const val LEAN_DAMPING = 0.72f
        const val KICK = 0.25f
        const val VELOCITY_KEEP = 0.4f
        const val APPEAR_DP = 14f
        const val APPEAR_SCALE = 0.7f
        const val LIT_GROW = 0.08f
        const val ARMED_GROW = 0.08f
        const val POP_GROW = 0.25f
        const val DIM = 0.4f
        const val BACKDROP_ALPHA = 245f
        const val BADGE_PAD_DP = 9f
        const val BADGE_HEIGHT_DP = 24f
        const val BADGE_INSET_DP = 8f
        const val RESULT_HEIGHT_DP = 36f
        const val RESULT_PAD_DP = 16f
        const val RESULT_MS = 1_100L
        const val BUMP_DP = 4f
        const val TAIL_DP = 7f
        const val HOLE_W_DP = 4f
        const val HOLE_H_DP = 3f
        const val HOLE_INSET_DP = 3f
        const val HOLE_STEP_DP = 8f
        const val BLOB_POINTS = 56
        const val BLOB_PERIOD_MS = 2_400L
        const val BLOB_INSET = 0.05f
        const val BLOB_A = 0.03f
        const val BLOB_B = 0.02f
        const val SQUIRCLE = 0.5f
        const val TWO_PI = (2.0 * Math.PI).toFloat()
    }
}
