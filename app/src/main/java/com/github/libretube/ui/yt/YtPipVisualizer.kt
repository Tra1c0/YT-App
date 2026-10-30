package com.github.libretube.ui.yt

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtAudioTap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * YT: a wave of light lines below the floating picture-in-picture window, moving with the
 * sound of the video: the bass swings the long waves, the middle the shorter ones, the treble
 * makes the lines shiver.
 *
 * Android lets no app draw outside its picture-in-picture window, so the wave is a separate
 * window of its own beneath it. That needs the permission to show over other apps; the switch
 * in the player settings asks for it. The window takes no touches (what lies below stays
 * usable) and exists only while the video floats. Android reports only where the window came
 * to rest after it was dragged, so the wave fades out while it moves and back in below it.
 */
object YtPipVisualizer {
    const val PREF_KEY = "yt_pip_visualizer"

    private var wave: WaveView? = null
    private var host: Activity? = null
    private val handler = Handler(Looper.getMainLooper())
    private val lastBounds = Rect()

    fun enabled(): Boolean = PreferenceHelper.getBoolean(PREF_KEY, false)

    fun allowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Shows the wave below the picture-in-picture window of [activity]; [position] is the
     * player's current position in ms (null while unknown).
     */
    fun show(activity: Activity, position: () -> Long?) {
        if (!enabled() || !allowed(activity) || wave != null) return
        val app = activity.applicationContext
        val view = WaveView(app, position)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // touches go through to what lies below only up to this opacity (Android 12+)
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "YT wave"
        }
        val added = runCatching {
            app.getSystemService(WindowManager::class.java).addView(view, params)
        }.isSuccess
        if (!added) return
        view.alpha = 0f
        wave = view
        host = activity
        YtAudioTap.listening = true
        lastBounds.setEmpty()
        handler.post(follow)
    }

    fun hide() {
        handler.removeCallbacks(follow)
        YtAudioTap.listening = false
        val view = wave ?: return
        wave = null
        host = null
        runCatching { view.context.getSystemService(WindowManager::class.java).removeViewImmediate(view) }
    }

    /** Keeps the wave below the window: checks where it is, fades on a move. */
    private val follow = object : Runnable {
        override fun run() {
            val view = wave ?: return
            val activity = host ?: return hide()
            if (activity.isFinishing || activity.isDestroyed) return hide()
            place(activity, view)
            handler.postDelayed(this, 200)
        }
    }

    private fun place(activity: Activity, view: WaveView) {
        val decor = activity.window?.decorView ?: return
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        val bounds = Rect(location[0], location[1], location[0] + decor.width, location[1] + decor.height)
        if (bounds == lastBounds) return
        lastBounds.set(bounds)

        val screen = screenBounds(activity)
        val density = activity.resources.displayMetrics.density
        val gap = (6 * density).roundToInt()
        val wanted = (bounds.height() * 0.5f).roundToInt()
        val room = screen.bottom - bounds.bottom - gap
        val height = min(wanted, room)
        // stashed at the edge, or no room below: no wave
        val visible = bounds.width() > 0 && screen.contains(bounds) && height >= (22 * density)
        val manager = activity.applicationContext.getSystemService(WindowManager::class.java)
        view.animate().cancel()
        if (!visible) {
            view.animate().alpha(0f).setDuration(150).start()
            return
        }
        // fade out, move, fade in
        view.animate().alpha(0f).setDuration(if (view.alpha > 0f) 120L else 0L).withEndAction {
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@withEndAction
            params.x = bounds.left
            params.y = bounds.bottom + gap
            params.width = bounds.width()
            params.height = height
            runCatching { manager.updateViewLayout(view, params) }
            view.animate().alpha(1f).setDuration(320).start()
        }.start()
    }

    private fun screenBounds(activity: Activity): Rect {
        val manager = activity.applicationContext.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(manager.maximumWindowMetrics.bounds)
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    /**
     * The wave: many thin lines over one curve, each a little shifted, so they braid into a
     * ribbon; blue above, magenta below, as in the design.
     */
    @SuppressLint("ViewConstructor")
    private class WaveView(context: Context, private val position: () -> Long?) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * density
        }
        private val path = Path()
        private val bands = FloatArray(YtAudioTap.BANDS)
        private val smooth = FloatArray(YtAudioTap.BANDS)
        private var generation = -1
        private var start = 0L
        private var phase = 0f
        private var lastFrame = 0L

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0xFF3FA2F6.toInt(), 0xFF8E5CF7.toInt(), 0xFFE03BD6.toInt(), 0xFFF06AD8.toInt()),
                floatArrayOf(0f, 0.35f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
            lastFrame = now
            readBands()
            // rise fast, fall slowly, as the eye expects of sound
            for (b in bands.indices) {
                val target = bands[b]
                val rate = if (target > smooth[b]) 18f else 4f
                smooth[b] += (target - smooth[b]) * min(1f, rate * dt)
            }
            val bass = (smooth[0] + smooth[1]) / 2f
            val middle = (smooth[2] + smooth[3] + smooth[4]) / 3f
            val treble = (smooth[5] + smooth[6] + smooth[7]) / 3f
            phase += dt * (0.6f + 1.6f * middle)

            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val mid = h / 2f
            // a quiet wave even in silence, so the ribbon never lies flat
            val a1 = h * (0.12f + 0.3f * bass)
            val a2 = h * (0.05f + 0.18f * middle)
            val a3 = h * (0.01f + 0.07f * treble)
            val lines = 26
            val segments = 72
            for (i in 0 until lines) {
                val t = i / (lines - 1f)
                val spread = 1f - abs(t - 0.5f) * 1.2f
                val shift = (t - 0.5f) * 1.6f
                paint.alpha = (70 + 120 * spread).roundToInt().coerceIn(0, 255)
                path.rewind()
                for (s in 0..segments) {
                    val x = w * s / segments
                    val u = s / segments.toFloat()
                    // the ends taper so the ribbon fades into the background
                    val taper = sin(PI.toFloat() * u).let { 0.35f + 0.65f * it }
                    val y = mid + taper * (
                        a1 * sin(2f * PI.toFloat() * (1.1f * u) + phase + shift) * spread +
                            a2 * sin(2f * PI.toFloat() * (2.3f * u) - 1.3f * phase + 1.7f * shift) +
                            a3 * sin(2f * PI.toFloat() * (5.5f * u) + 2.9f * phase + 3f * shift)
                        )
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }
            postInvalidateOnAnimation()
        }

        private fun readBands() {
            val now = position()
            if (now == null) {
                // paused: the ribbon settles into its quiet swing
                bands.fill(0f)
                return
            }
            if (YtAudioTap.generation != generation) {
                generation = YtAudioTap.generation
                start = now
            }
            if (!YtAudioTap.bandsAt(now, start, bands) && !YtAudioTap.latest(bands)) bands.fill(0f)
            for (b in bands.indices) bands[b] = max(0f, bands[b])
        }
    }
}
