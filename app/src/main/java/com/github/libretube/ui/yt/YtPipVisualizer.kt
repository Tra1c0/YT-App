package com.github.libretube.ui.yt

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import com.github.libretube.R
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtAudioTap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YT: the sound wave ([YtWaveView]) at the lower edge of the system's picture-in-picture
 * window. This is the fallback: with the wave switched on YT floats the video in its own window
 * ([YtFloatingPlayer]), where the wave moves with it in every frame. Here it is a separate
 * window just over the PiP's lower edge (Android lets no app draw outside its PiP window); it
 * leaves the PiP itself free and hangs below it, as if from behind. Android reports the PiP's
 * place only after a drag ends (its shell moves only the surface meanwhile), so the wave fades
 * out on a move and back in at the new place.
 *
 * The switch also governs the permission to show over other apps, which both need. The window
 * takes no touches (what lies below stays usable) and exists only while the video floats.
 *
 * Android 15 and newer lock that permission for apps installed from an APK file until the user
 * allows restricted settings in the app's info; without it the wave says so once and keeps
 * looking while the video floats, so a permission given meanwhile brings it at once.
 */
object YtPipVisualizer {
    const val PREF_KEY = "yt_pip_visualizer"
    private const val TAG = "YtPipWave"

    /**
     * Emulator test (crash-check.sh, --ez yt_pip_test true): picture-in-picture starts on Home
     * even without playback, so the wave's window can be checked where no video loads.
     */
    var testPip = false

    private var wave: YtWaveView? = null
    private var host: Activity? = null
    private val handler = Handler(Looper.getMainLooper())
    private val lastBounds = Rect()

    /** The floating window that waits for the permission, with what its wave would read. */
    private var waiting: Waiting? = null
    private class Waiting(val activity: Activity, val position: () -> Long?, val speed: () -> Float)

    /** The missing permission was told once in this run of the app; no toast on every float. */
    private var told = false

    fun enabled(): Boolean = PreferenceHelper.getBoolean(PREF_KEY, false)

    fun allowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Whether Android may lock the permission as a restricted setting: from Android 15 on, for
     * apps from an APK file or from an installer the system does not trust. An app cannot ask
     * whether the lock is set, so this only decides whether the way around it is explained.
     */
    fun mayBeRestricted(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    /**
     * Just before the video starts to float, while YT is still in front: the hint that the
     * permission is missing. Android drops toasts of an app that is not in front when its
     * notifications are off, so a hint only from the floating window may never be seen.
     */
    fun beforePip(context: Context) {
        if (told || !enabled() || allowed(context)) return
        told = true
        Log.i(TAG, "before pip: permission missing, told")
        tell(context)
    }

    /** The hint in the language of YT's screens (the activity's), short enough for two lines. */
    private fun tell(context: Context) {
        Toast.makeText(context.applicationContext, context.getString(R.string.yt_pip_visualizer_missing), Toast.LENGTH_LONG).show()
    }

    /**
     * Shows the wave below the picture-in-picture window of [activity]; [position] is the
     * player's current position in ms (null while paused or unknown), [speed] its playback speed.
     */
    fun show(activity: Activity, position: () -> Long?, speed: () -> Float) {
        val on = enabled()
        val allowed = allowed(activity)
        Log.i(TAG, "show: switch=$on permission=$allowed shown=${wave != null} api=${Build.VERSION.SDK_INT}")
        if (!on || wave != null) return
        val app = activity.applicationContext
        if (!allowed) {
            // the switch is on, the permission is not (taken back, restored from a backup,
            // or locked by Android): say so once, then look again every second while it floats
            if (!told) {
                told = true
                tell(activity)
            }
            waiting = Waiting(activity, position, speed)
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, RETRY_MS)
            return
        }
        handler.removeCallbacks(retry)
        waiting = null
        val view = YtWaveView(app, position, speed).apply { clipPlayer = true }
        // right where it belongs from the first frame, when the floating window is known already
        val spot = spot(activity)
        spot?.let { view.fit(it) }
        val shown = spot?.visible == true
        val params = WindowManager.LayoutParams(
            if (shown) spot!!.width else 1,
            if (shown) spot!!.height else 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (shown) {
                x = spot!!.x
                y = spot.y
            }
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
        Log.i(TAG, "show: added=$added at=${spot?.bounds} visible=$shown")
        if (!added) return
        wave = view
        host = activity
        YtAudioTap.listening = true
        if (spot != null) lastBounds.set(spot.bounds) else lastBounds.setEmpty()
        // the wave fades in by itself as it draws; no animator that could be cut off
        if (shown) view.fadeTo(1f)
        Log.i(TAG, "show: window=${spot?.x},${spot?.y},${spot?.width},${spot?.height}")
        handler.postDelayed(follow, FOLLOW_MS)
    }

    fun hide() {
        handler.removeCallbacks(retry)
        waiting = null
        handler.removeCallbacks(follow)
        YtAudioTap.listening = false
        val view = wave ?: return
        wave = null
        host = null
        runCatching { view.context.getSystemService(WindowManager::class.java).removeViewImmediate(view) }
    }

    /** Looks again for the permission while the window floats; the wave comes once it is given. */
    private val retry = object : Runnable {
        override fun run() {
            val wait = waiting ?: return
            val activity = wait.activity
            if (activity.isFinishing || activity.isDestroyed || !activity.isInPictureInPictureMode) {
                waiting = null
                return
            }
            if (allowed(activity)) {
                waiting = null
                show(activity, wait.position, wait.speed)
                return
            }
            handler.postDelayed(this, RETRY_MS)
        }
    }

    /** Keeps the wave below the window: checks where it is, fades on a move. */
    private val follow = object : Runnable {
        override fun run() {
            val view = wave ?: return
            val activity = host ?: return hide()
            if (activity.isFinishing || activity.isDestroyed) return hide()
            place(activity, view)
            // playback may have resumed while the wave rested
            view.wake()
            handler.postDelayed(this, FOLLOW_MS)
        }
    }

    /**
     * Where the wave's window goes for the floating window at [bounds]: from the PiP's lower
     * corners' tops ([radius] above its edge) down to the wave's outer side, as wide as the PiP.
     */
    private class Spot(
        val bounds: Rect,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val radius: Float,
        val unit: Float,
        val visible: Boolean,
    )

    private fun YtWaveView.fit(spot: Spot) {
        radius = spot.radius
        edge = spot.radius
        unit = spot.unit
    }

    private fun spot(activity: Activity, bounds: Rect? = pipBounds(activity)): Spot? {
        bounds ?: return null
        val screen = screenBounds(activity)
        val density = activity.resources.displayMetrics.density
        val radius = (PIP_CORNER_DP * density).roundToInt()
        val room = screen.bottom - bounds.bottom
        var unit = YtWaveView.unitFor(bounds.height(), density)
        // little room below: a smaller wave; none at all: no wave
        val spare = room - (7 * density).roundToInt()
        if (YtWaveView.stripHeight(unit, density) > room) unit = min(unit, spare / YtWaveShape.FULL)
        val strip = YtWaveView.stripHeight(unit, density).coerceAtMost(room)
        val visible = bounds.width() > 0 && screen.contains(bounds) && room >= 18 * density && unit > 0f
        val y = bounds.bottom - radius
        Log.i(TAG, "spot: pip=$bounds screen=$screen y=$y strip=$strip unit=$unit visible=$visible")
        return Spot(bounds, bounds.left, y, bounds.width(), radius + strip.coerceAtLeast(1), radius.toFloat(), unit, visible)
    }

    private fun place(activity: Activity, view: YtWaveView) {
        val bounds = pipBounds(activity) ?: return
        if (bounds == lastBounds) return
        lastBounds.set(bounds)
        val spot = spot(activity, bounds) ?: return
        if (!spot.visible) {
            view.fadeTo(0f)
            return
        }
        val manager = activity.applicationContext.getSystemService(WindowManager::class.java)
        // fade out, move, fade in
        view.moveThen {
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@moveThen
            params.x = spot.x
            params.y = spot.y
            params.width = spot.width
            params.height = spot.height
            view.fit(spot)
            runCatching { manager.updateViewLayout(view, params) }
                .onFailure { Log.w(TAG, "place: window not moved", it) }
        }
    }

    /** The corner radius of the system's PiP window (WM Shell, pip_corner_radius). */
    private const val PIP_CORNER_DP = 16

    private const val FOLLOW_MS = 200L
    private const val RETRY_MS = 1000L

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
}
