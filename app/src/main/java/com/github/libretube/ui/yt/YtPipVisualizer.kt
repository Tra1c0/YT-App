package com.github.libretube.ui.yt

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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
    private const val TAG = "YtPipWave"

    /**
     * Emulator test (crash-check.sh, --ez yt_pip_test true): picture-in-picture starts on Home
     * even without playback, so the wave's window can be checked where no video loads.
     */
    var testPip = false

    private var wave: WaveView? = null
    private var host: Activity? = null
    private val handler = Handler(Looper.getMainLooper())
    private val lastBounds = Rect()

    fun enabled(): Boolean = PreferenceHelper.getBoolean(PREF_KEY, false)

    fun allowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Shows the wave below the picture-in-picture window of [activity]; [position] is the
     * player's current position in ms (null while paused or unknown), [speed] its playback speed.
     */
    fun show(activity: Activity, position: () -> Long?, speed: () -> Float) {
        val on = enabled()
        val allowed = allowed(activity)
        Log.i(TAG, "show: switch=$on permission=$allowed shown=${wave != null} api=${Build.VERSION.SDK_INT}")
        if (!on || !allowed || wave != null) return
        val app = activity.applicationContext
        val view = WaveView(app, position, speed)
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
            // placed in display coordinates, not inside the system bars
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        val added = runCatching {
            app.getSystemService(WindowManager::class.java).addView(view, params)
        }.onFailure { Log.w(TAG, "show: window not added", it) }.isSuccess
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
        val bounds = pipBounds(activity) ?: return
        if (bounds == lastBounds) return
        lastBounds.set(bounds)

        val screen = screenBounds(activity)
        val density = activity.resources.displayMetrics.density
        // the wave hangs right at the window's lower edge, a little under it, thin as a glow
        val overlap = (3 * density).roundToInt()
        val top = bounds.bottom - overlap
        val wanted = (bounds.height() * 0.24f).roundToInt().coerceIn((30 * density).roundToInt(), (64 * density).roundToInt())
        val height = min(wanted, screen.bottom - top)
        // stashed at the edge, or no room below: no wave
        val visible = bounds.width() > 0 && screen.contains(bounds) && height >= (18 * density)
        Log.i(TAG, "place: pip=$bounds screen=$screen top=$top height=$height visible=$visible")
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
            params.y = top
            params.width = bounds.width()
            params.height = height
            runCatching { manager.updateViewLayout(view, params) }
            view.animate().alpha(1f).setDuration(320).start()
        }.start()
    }

    /**
     * Where the floating window is on the screen: the bounds of the activity's window as the
     * system reports them (Android 11+), else where its views are drawn.
     */
    private fun pipBounds(activity: Activity): Rect? {
        val decor = activity.window?.decorView ?: return null
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        val drawn = Rect(location[0], location[1], location[0] + decor.width, location[1] + decor.height)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return drawn
        val reported = Rect(activity.windowManager.currentWindowMetrics.bounds)
        if (reported != drawn) Log.i(TAG, "bounds: window=$reported views=$drawn")
        return if (reported.isEmpty) drawn else reported
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
     * ribbon that glows; blue on the crests, violet and pink below, as in the design. Its ends
     * taper and fade into what lies below.
     */
    @SuppressLint("ViewConstructor")
    private class WaveView(
        context: Context,
        private val position: () -> Long?,
        private val speed: () -> Float,
    ) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val fadeMask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        private val path = Path()
        private val bands = FloatArray(YtAudioTap.BANDS)
        private val smooth = FloatArray(YtAudioTap.BANDS)
        private var generation = -1
        private var start: Long? = null
        private var lastFound = 0L
        private var phase = 0f
        private var lastFrame = 0L

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val colors = intArrayOf(0xFF4AA8FF.toInt(), 0xFFA35CF5.toInt(), 0xFFF060D0.toInt())
            val stops = floatArrayOf(0.15f, 0.5f, 0.85f)
            paint.shader = LinearGradient(0f, 0f, w * 0.15f, h.toFloat(), colors, stops, Shader.TileMode.CLAMP)
            glow.shader = LinearGradient(0f, 0f, w * 0.15f, h.toFloat(), colors, stops, Shader.TileMode.CLAMP)
            fadeMask.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(0x00000000, 0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
                floatArrayOf(0f, 0.2f, 0.8f, 1f),
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
            val mid = h * 0.5f
            // a quiet swing even in silence, so the ribbon never lies flat; never beyond the view
            val a1 = h * (0.08f + 0.22f * bass)
            val a2 = h * (0.04f + 0.12f * middle)
            val a3 = h * (0.01f + 0.05f * treble)
            val lines = 34
            val segments = 80
            val layer = canvas.saveLayer(0f, 0f, w, h, null)
            // the glow: the middle of the ribbon, wide and faint
            for ((width, alpha) in listOf(7f to 26, 3.5f to 55)) {
                glow.strokeWidth = width * density
                glow.alpha = alpha
                ribbon(0.5f, w, mid, a1, a2, a3, segments)
                canvas.drawPath(path, glow)
            }
            for (i in 0 until lines) {
                val t = i / (lines - 1f)
                paint.alpha = (90 + 140 * (1f - abs(t - 0.5f) * 1.6f)).roundToInt().coerceIn(40, 230)
                ribbon(t, w, mid, a1, a2, a3, segments)
                canvas.drawPath(path, paint)
            }
            // the ends fade into what lies below
            canvas.drawRect(0f, 0f, w, h, fadeMask)
            canvas.restoreToCount(layer)
            postInvalidateOnAnimation()
        }

        /** One line [t] (0 to 1) of the ribbon into [path]. */
        private fun ribbon(t: Float, w: Float, mid: Float, a1: Float, a2: Float, a3: Float, segments: Int) {
            val spread = 1f - abs(t - 0.5f) * 1.2f
            val shift = (t - 0.5f) * 1.6f
            path.rewind()
            for (s in 0..segments) {
                val u = s / segments.toFloat()
                val x = w * u
                // the lines meet at the ends, where the ribbon tapers to a point
                val taper = sin(PI.toFloat() * u)
                val y = mid + taper * (
                    a1 * sin(2f * PI.toFloat() * (1.1f * u) + phase + shift) * spread +
                        a2 * sin(2f * PI.toFloat() * (2.3f * u) - 1.3f * phase + 1.7f * shift) +
                        a3 * sin(2f * PI.toFloat() * (5.5f * u) + 2.9f * phase + 3f * shift)
                    )
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }

        private fun readBands() {
            val now = position()
            if (now == null) {
                // paused: the ribbon settles into its quiet swing
                bands.fill(0f)
                return
            }
            // the stream restarted (seek, new video, change of speed): its place is found anew,
            // from how far the tap has run ahead of what is heard, once that has settled
            val current = YtAudioTap.generation
            if (current != generation) {
                generation = current
                start = null
            }
            if (start == null) {
                val lead = (LEAD_MS * speed().coerceIn(0.25f, 4f)).toLong()
                start = YtAudioTap.startOf(current, now, lead)
            }
            val found = start?.let { YtAudioTap.bandsAt(now, it, bands) } == true || YtAudioTap.latest(bands)
            val time = System.nanoTime()
            if (found) {
                lastFound = time
            } else if (time - lastFound > HOLD_NS) {
                // a short gap (the stream just restarted) keeps the last bands; a long one is quiet
                bands.fill(0f)
            }
            for (b in bands.indices) bands[b] = max(0f, bands[b])
        }

        private companion object {
            /** What the player's speaker buffer holds ahead of what is heard, in ms of playing. */
            const val LEAD_MS = 250f
            const val HOLD_NS = 500_000_000L
        }
    }
}
