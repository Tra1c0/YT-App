package com.github.libretube.ui.yt

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.Log
import android.view.View
import com.github.libretube.helpers.YtAudioTap
import com.github.libretube.helpers.YtGuard
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * YT: the wave of light at a player's lower edge. It seems to come out from behind the player
 * and hangs below it, pointed at both lower corners; its depth along the edge follows the sound
 * ([YtWaveShape]: bass in the middle, treble towards the corners).
 *
 * It draws once per frame of the screen (60 to 120 times a second, as the display runs) and
 * takes the bands for the exact moment the player plays then, so the same sound always looks
 * the same at any frame rate. Nothing moves by itself.
 *
 * The owner says where the player's lower edge runs in this view ([edge], with corners of
 * [radius]) and how large the wave is ([unit], the height the earlier wave had). Behind a
 * player in the same window the player hides the part above its edge; above a window of the
 * system ([clipPlayer]) the view leaves the player's area out itself.
 */
@SuppressLint("ViewConstructor")
class YtWaveView(
    context: Context,
    private val position: () -> Long?,
    private val speed: () -> Float,
) : View(context) {
    private val density = context.resources.displayMetrics.density

    /** Where the player's lower edge runs, in px from this view's top. */
    var edge = 0f
        set(value) {
            field = value
            shaders()
        }

    /** The corner radius of the player, in px. */
    var radius = 0f

    /** The wave's unit in px: the height the earlier wave had (its depth is measured in it). */
    var unit = 0f
        set(value) {
            field = value
            shaders()
        }

    /** Leave the player's own area out (true when this view lies above the player). */
    var clipPlayer = false

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fadeMask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    /** Composes the drawn wave onto the window: its alpha is the fade. */
    private val layerPaint = Paint()
    private val path = Path()
    private val player = Path()

    private val target = FloatArray(YtAudioTap.BANDS)
    private val shown = FloatArray(YtAudioTap.BANDS)
    private val slopes = FloatArray(YtAudioTap.BANDS)
    private val depths = FloatArray(SEGMENTS + 1)
    private val edges = FloatArray(SEGMENTS + 1)

    private var generation = -1
    private var start: Long? = null
    private var lastFound = 0L
    private var lastFrame = 0L
    private var resting = false

    /** How far the wave shows (0 to 1), where it heads, and a move waiting for it to be gone. */
    private var fade = 0f
    private var fadeTarget = 0f
    private var move: (() -> Unit)? = null

    /** Counts the orders; a move already on its way is dropped when a newer order came. */
    private var order = 0

    // the frame rate, logged now and then (emulator checks)
    private var countFrom = 0L
    private var counted = 0

    /** Fades to [target]; a move still waiting is dropped. */
    fun fadeTo(target: Float) {
        order++
        fadeTarget = target
        move = null
        wake()
    }

    /** Shows at once, without fading in (it appears together with the player). */
    fun showNow() {
        order++
        fade = 1f
        fadeTarget = 1f
        move = null
        wake()
    }

    /** Fades out, runs [action] (the window moves), then fades back in. */
    fun moveThen(action: () -> Unit) {
        order++
        move = action
        fadeTarget = 0f
        wake()
    }

    /** Starts drawing again after a rest (playback resumed, a new place). */
    fun wake() {
        resting = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shaders()
    }

    private fun shaders() {
        val w = width.toFloat()
        if (w <= 0f) return
        // blue at the player's edge, violet, then pink at the ribbon's outer side
        val deepest = edge + max(unit * YtWaveShape.FULL, 1f)
        val colors = intArrayOf(0xFF4AA8FF.toInt(), 0xFFA35CF5.toInt(), 0xFFF060D0.toInt())
        val stops = floatArrayOf(0f, 0.45f, 1f)
        line.shader = LinearGradient(0f, edge, 0f, deepest, colors, stops, Shader.TileMode.CLAMP)
        glow.shader = LinearGradient(0f, edge - 4 * density, 0f, deepest, colors, stops, Shader.TileMode.CLAMP)
        // the pointed ends fade into what lies around
        fadeMask.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.03f, 0.97f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun stepFade(dt: Float) {
        fade = if (fadeTarget > fade) {
            min(fadeTarget, fade + dt / FADE_IN_S)
        } else {
            max(fadeTarget, fade - dt / FADE_OUT_S)
        }
        val pending = move
        if (fade <= 0f && pending != null) {
            move = null
            // after this frame: the window is moved outside of drawing, then the wave returns
            val given = order
            post {
                if (given != order) return@post
                pending()
                fadeTarget = 1f
                wake()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = drawingTime
        val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        stepFade(dt)
        val w = width.toFloat()
        val h = height.toFloat()
        if (fade <= 0f || w <= 1f || h <= 1f || unit <= 0f) {
            // nothing to show: the loop rests until a fade or a move wakes it
            if (fadeTarget > 0f || move != null) postInvalidateOnAnimation() else lastFrame = 0L
            return
        }
        val playing = readBands(dt)
        countFrames(now)

        YtWaveShape.slopes(shown, slopes)
        val r = radius.coerceAtMost(w / 2)
        for (s in 0..SEGMENTS) {
            val u = s / SEGMENTS.toFloat()
            val x = w * u
            depths[s] = YtWaveShape.depth(u, shown, slopes) * unit
            // the player's lower edge, rising along its rounded corners
            val inset = min(x, w - x)
            edges[s] = if (inset >= r || r <= 0f) {
                edge
            } else {
                val d = r - inset
                edge - r + sqrt(max(0f, r * r - d * d))
            }
        }

        layerPaint.alpha = (fade * 255).roundToInt().coerceIn(0, 255)
        val layer = canvas.saveLayer(0f, 0f, w, h, layerPaint)
        if (clipPlayer) {
            player.rewind()
            player.addRoundRect(0f, edge - h - r, w, edge, r, r, Path.Direction.CW)
            canvas.clipOutPath(player)
        }
        // the glow along the edge, where the ribbon comes out from behind the player
        for ((width, alpha) in GLOW) {
            glow.strokeWidth = width * density
            glow.alpha = alpha
            path.rewind()
            for (s in 0..SEGMENTS) {
                val x = w * s / SEGMENTS
                val y = edges[s] + depths[s] * 0.25f
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, glow)
        }
        // the lines, from the edge to the ribbon's outer side, braided a little
        val behind = density
        for (i in 0 until LINES) {
            val c = i / (LINES - 1f)
            line.alpha = (230 - 140 * c).roundToInt()
            path.rewind()
            for (s in 0..SEGMENTS) {
                val u = s / SEGMENTS.toFloat()
                val y = edges[s] - behind + (depths[s] + behind) * YtWaveShape.line(c, u)
                if (s == 0) path.moveTo(w * u, y) else path.lineTo(w * u, y)
            }
            canvas.drawPath(path, line)
        }
        canvas.drawRect(0f, 0f, w, h, fadeMask)
        canvas.restoreToCount(layer)

        // paused and settled: no need to draw the same picture again
        resting = !playing && shown.max() < 0.005f && fade == fadeTarget && move == null
        if (resting) lastFrame = 0L else postInvalidateOnAnimation()
    }

    /** The bands for this frame; true while the player plays. */
    private fun readBands(dt: Float): Boolean {
        if (testSignal) {
            // emulator check without sound: a slow, fixed pattern across the bands
            val t = drawingTime / 1000.0
            for (b in shown.indices) shown[b] = (0.5 + 0.45 * sin(2 * PI * (0.4 * t + b / 6.0))).toFloat()
            return true
        }
        val now = position()
        if (now == null) {
            settle(dt)
            return false
        }
        // the stream restarted (seek, new video, change of speed): its place is found anew,
        // from how far the tap has run ahead of what is heard, once that has settled
        val current = YtAudioTap.generation
        if (current != generation) {
            generation = current
            start = null
        }
        val lead = YtAudioTap.leadMs(speed())
        if (start == null) start = YtAudioTap.startOf(current, now, lead)
        val found = start?.let { YtAudioTap.bandsAt(now, it, target) } == true ||
            YtAudioTap.latest(target, lead)
        val time = System.nanoTime()
        if (found) {
            lastFound = time
            System.arraycopy(target, 0, shown, 0, shown.size)
        } else if (time - lastFound > HOLD_NS) {
            // a short gap (the stream just restarted) keeps the last bands; a long one settles
            settle(dt)
        }
        return true
    }

    /** Towards silence, at the same pace whatever the frame rate. */
    private fun settle(dt: Float) {
        val rate = 1f - exp(-dt / SETTLE_S)
        for (b in shown.indices) shown[b] -= shown[b] * rate
    }

    private fun countFrames(now: Long) {
        if (!YtGuard.isEmulator) return
        if (countFrom == 0L) countFrom = now
        counted++
        if (now - countFrom >= 2000) {
            Log.i(TAG, "fps=${counted * 1000 / (now - countFrom)} size=${width}x$height bands=${shown.joinToString(",") { "%.2f".format(it) }}")
            countFrom = now
            counted = 0
        }
    }

    companion object {
        private const val TAG = "YtWave"
        private const val SEGMENTS = 96
        private const val LINES = 24
        private val GLOW = listOf(14f to 26, 7f to 55)
        private const val FADE_IN_S = 0.32f
        private const val FADE_OUT_S = 0.12f
        private const val SETTLE_S = 0.06f
        private const val HOLD_NS = 500_000_000L

        /** Emulator check: draws a fixed pattern instead of the sound (there is none there). */
        @Volatile
        var testSignal = false

        /** Depth of the strip below the player that the wave needs, for a [unit] in px. */
        fun stripHeight(unit: Float, density: Float): Int =
            kotlin.math.ceil(unit * YtWaveShape.FULL).toInt() + (7 * density).roundToInt()

        /** The wave's unit for a player [height] px high: the earlier wave's height. */
        fun unitFor(height: Int, density: Float): Float =
            (height * 0.24f).coerceIn(30 * density, 64 * density)
    }
}
