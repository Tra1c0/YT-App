package com.github.libretube.ui.yt

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.YtAudioTap
import com.github.libretube.helpers.YtGuard
import com.github.libretube.services.AbstractPlayerService
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YT: YT's own floating player, used instead of Android's picture-in-picture while the sound
 * wave is switched on.
 *
 * Android tells an app where its picture-in-picture window is only after a drag ends (its
 * shell moves just the window's surface meanwhile), so a wave in a window of its own can only
 * jump after it. Here the video and the wave are one window: they move together in every frame
 * of the screen, at 60 to 120 frames a second as the display runs. The wave hangs at the
 * video's lower edge, as if from behind it ([YtWaveView]).
 *
 * The player itself stays in YT's media service; this window only borrows the player page's
 * media controller and takes the video over with [PlayerView.switchTargetView] (the new surface
 * first, then the old one let go, so the picture does not stop). Back in the app it returns the
 * same way. The window needs the permission to show over other apps, like the wave did.
 *
 * Gestures: drag to move (on release it settles at the nearer side), two fingers to resize,
 * a tap for the controls, a double tap to open YT, and drag onto the ✕ at the bottom to close.
 */
@SuppressLint("StaticFieldLeak")
object YtFloatingPlayer {
    private const val TAG = "YtFloat"
    private const val CORNER_DP = 16f
    private const val MARGIN_DP = 16f
    private const val MIN_WIDTH_DP = 150f
    private const val CONTROLS_MS = 3000L
    private const val SNAP_MS = 240L
    private const val FLING_DP_S = 1000f
    private const val SURFACE_WAIT_FRAMES = 90
    private const val CHECK_MS = 500L

    private val handler = Handler(Looper.getMainLooper())
    private var shown: Floating? = null

    // where the window was last, for the next time in this run of the app
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var lastWidth = 0

    val isShowing: Boolean get() = shown != null

    /** Floating, and not on its way back into the app. */
    val isFloating: Boolean get() = shown?.closing == false

    /** The wave is switched on and YT may show over other apps: float in YT's own window. */
    fun wanted(context: Context): Boolean =
        YtPipVisualizer.enabled() && Settings.canDrawOverlays(context)

    /**
     * Floats [controller]'s video, taking it over from [from] (the player page's view).
     * False when the window could not be added; then the caller uses picture-in-picture.
     */
    fun show(activity: Activity, controller: MediaController, from: PlayerView, offline: Boolean): Boolean {
        // a window still handing the video back (the app was left again at once): finish that first
        shown?.let { if (it.closing) it.close(pause = false) else return true }
        if (!wanted(activity)) return false
        val floating = runCatching { Floating(activity, controller, from, offline) }
            .onFailure { Log.w(TAG, "show: not built", it) }
            .getOrNull() ?: return false
        if (!floating.add()) return false
        shown = floating
        return true
    }

    /**
     * Back in the app: the video returns to [to] as soon as its surface is ready, then the window
     * goes and [done] runs. Nothing happens when nothing floats.
     */
    fun returnTo(to: PlayerView, done: () -> Unit = {}) {
        shown?.returnTo(to, done)
    }

    /** Closes the window; [pause] stops the video too (the ✕), else it plays on in the app. */
    fun close(pause: Boolean) {
        shown?.close(pause)
    }

    private class Floating(
        activity: Activity,
        val controller: MediaController,
        from: PlayerView,
        val offline: Boolean,
    ) {
        val app: Context = activity.applicationContext
        val host: Class<out Activity> = activity.javaClass
        val manager: WindowManager = app.getSystemService(WindowManager::class.java)
        val density = app.resources.displayMetrics.density
        val corner = CORNER_DP * density
        val margin = (MARGIN_DP * density).roundToInt()
        var page = WeakReference(from)

        val themed = ContextThemeWrapper(app, R.style.BaseTheme)
        val root = Root(themed)
        val videoBox = FrameLayout(themed)
        val video: PlayerView = LayoutInflater.from(themed)
            .inflate(R.layout.yt_floating_player, videoBox, false) as PlayerView
        val wave = YtWaveView(
            app,
            position = { if (controller.isConnected && controller.isPlaying) controller.currentPosition else null },
            speed = { if (controller.isConnected) controller.playbackParameters.speed else 1f },
        )
        val controls = FrameLayout(themed)
        val play = button(R.drawable.ic_pause) { if (controller.isPlaying) controller.pause() else controller.play() }
        val opener = button(R.drawable.ic_fullscreen) { expand() }
        val closer = button(R.drawable.ic_close) { close(pause = true) }
        val target = CloseTarget(app, manager, density)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "YT player"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // placed in display coordinates, not inside the system bars
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }

        // the video's size in the window, and the wave's strip below it
        var videoWidth = 0
        var videoHeight = 0
        var strip = 0
        var added = false
        var closing = false
        var moved = false
        var aspect = 16f / 9f
        var pendingAspect: Float? = null
        val area = Rect()

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing(isPlaying)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val ratio = ratioOf(videoSize) ?: return
                if (abs(ratio - aspect) < 0.01f) return
                // not in the middle of a drag, a pinch or a snap: then, once it ends
                if (touch.busy) {
                    pendingAspect = ratio
                    return
                }
                aspect = ratio
                resize(videoWidth, keepCentre = true)
            }
        }

        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val off = intent.action == Intent.ACTION_SCREEN_OFF
                // no picture while the screen is off: the video track rests, as on the player page
                runCatching {
                    controller.sendCustomCommand(
                        AbstractPlayerService.runPlayerActionCommand,
                        bundleOf(PlayerCommand.SET_VIDEO_TRACK_TYPE_DISABLED.name to off),
                    )
                }
                if (off && PlayerHelper.pausePlayerOnScreenOffEnabled) controller.pause()
                Log.i(TAG, "screen ${if (off) "off" else "on"}: video track ${if (off) "off" else "on"}")
            }
        }

        val check = object : Runnable {
            override fun run() {
                // the media service stopped (e.g. from its notification): nothing left to show
                if (!controller.isConnected) return close(pause = false)
                // the permission was taken back: Android hides the window, so the video stops too
                if (!Settings.canDrawOverlays(app)) return close(pause = true)
                handler.postDelayed(this, CHECK_MS)
            }
        }

        val hideControls = Runnable { showControls(false) }
        val touch = Touch()

        init {
            ratioOf(controller.videoSize)?.let { aspect = it }
            videoBox.background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = corner
            }
            videoBox.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, corner)
                }
            }
            videoBox.clipToOutline = true
            video.subtitleView?.isVisible = false
            videoBox.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            controls.setBackgroundColor(0x66000000)
            controls.isVisible = false
            val size = (40 * density).roundToInt()
            val gap = (4 * density).roundToInt()
            controls.addView(play, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            controls.addView(opener, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply { setMargins(gap, gap, gap, gap) })
            controls.addView(closer, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply { setMargins(gap, gap, gap, gap) })
            videoBox.addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            // the wave first, so the video lies on top of it and the wave comes out from behind
            wave.radius = corner
            wave.edge = corner
            root.addView(wave)
            root.addView(videoBox)
        }

        fun button(icon: Int, action: () -> Unit) = ImageButton(themed).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = null
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding((8 * density).roundToInt(), (8 * density).roundToInt(), (8 * density).roundToInt(), (8 * density).roundToInt())
            setOnClickListener {
                action()
                if (!closing) restartControlsTimer()
            }
        }

        fun ratioOf(size: VideoSize): Float? {
            if (size.width <= 0 || size.height <= 0) return null
            return size.width * size.pixelWidthHeightRatio / size.height
        }

        fun add(): Boolean {
            usableArea(area)
            // as large as Android's own picture-in-picture: 60% of the shorter side, less margins
            val shorter = min(area.width(), area.height())
            val width = if (lastWidth > 0) lastWidth else (0.6f * (shorter - 2 * margin)).roundToInt()
            layoutFor(width)
            val total = videoHeight + strip
            params.x = if (lastX != Int.MIN_VALUE) lastX else area.right - videoWidth - margin
            params.y = if (lastY != Int.MIN_VALUE) lastY else area.bottom - total - margin
            clampInto(params)
            added = runCatching { manager.addView(root, params) }
                .onFailure { Log.w(TAG, "show: window not added", it) }
                .isSuccess
            if (!added) return false
            log("added")
            controller.addListener(listener)
            ContextCompat.registerReceiver(
                app,
                screen,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            YtAudioTap.listening = true
            wave.showNow()
            playing(controller.isPlaying)
            handler.postDelayed(check, CHECK_MS)
            // the video moves in once the window's own surface is ready (no gap in the picture)
            whenReady({ (video.videoSurfaceView as? TextureView)?.isAvailable == true }) {
                if (closing) return@whenReady
                val old = page.get()
                PlayerView.switchTargetView(controller, old, video)
                moved = true
                Log.i(TAG, "surface→floating")
            }
            return true
        }

        /** Runs [action] once [ready] holds, checking each frame of the screen (at most 1.5 s). */
        fun whenReady(ready: () -> Boolean, action: () -> Unit) {
            var frames = 0
            val step = object : Runnable {
                override fun run() {
                    if (ready() || ++frames >= SURFACE_WAIT_FRAMES) action() else root.postOnAnimation(this)
                }
            }
            root.postOnAnimation(step)
        }

        /** The video's and the wave's sizes for a window [width] px wide. */
        fun layoutFor(width: Int) {
            usableArea(area)
            val maxWidth = (area.width() - 2 * margin).coerceAtLeast(1)
            val minWidth = min((MIN_WIDTH_DP * density).roundToInt(), maxWidth)
            var w = width.coerceIn(minWidth, maxWidth)
            var h = (w / aspect).roundToInt()
            // tall videos: at most 45% of the screen's height
            val maxHeight = (area.height() * 0.45f).roundToInt()
            if (h > maxHeight) {
                h = maxHeight
                w = (h * aspect).roundToInt()
            }
            videoWidth = w
            videoHeight = h
            val unit = YtWaveView.unitFor(h, density)
            strip = YtWaveView.stripHeight(unit, density)
            wave.unit = unit
            params.width = w
            params.height = h + strip
            videoBox.layoutParams = FrameLayout.LayoutParams(w, h)
            val cornerPx = corner.roundToInt()
            wave.layoutParams = FrameLayout.LayoutParams(w, cornerPx + strip).apply { topMargin = h - cornerPx }
            videoBox.invalidateOutline()
        }

        /** A new video's shape that came during a gesture, once the gesture is over. */
        fun applyPendingAspect() {
            val ratio = pendingAspect ?: return
            if (touch.busy) return
            pendingAspect = null
            aspect = ratio
            resize(videoWidth, keepCentre = true)
        }

        /** A new width (two fingers, or a new video's shape), keeping the window's centre. */
        fun resize(width: Int, keepCentre: Boolean) {
            if (!added) return
            val centreX = params.x + videoWidth / 2
            val centreY = params.y + videoHeight / 2
            layoutFor(width)
            if (keepCentre) {
                params.x = centreX - videoWidth / 2
                params.y = centreY - videoHeight / 2
            }
            clampInto(params)
            update()
        }

        fun update() {
            if (!added || closing) return
            runCatching { manager.updateViewLayout(root, params) }
                .onFailure { Log.w(TAG, "window not moved", it) }
        }

        /** Keeps the window inside the usable area: the screen without system bars and cut-outs. */
        fun clampInto(p: WindowManager.LayoutParams) {
            usableArea(area)
            val total = videoHeight + strip
            p.x = p.x.coerceIn(area.left, max(area.left, area.right - videoWidth))
            p.y = p.y.coerceIn(area.top, max(area.top, area.bottom - total))
        }

        fun usableArea(out: Rect) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = manager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                out.set(metrics.bounds)
                out.left += insets.left
                out.top += insets.top
                out.right -= insets.right
                out.bottom -= insets.bottom
            } else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                manager.defaultDisplay.getRealMetrics(metrics)
                // a phone turned sideways has its navigation bar at the side
                val sideways = metrics.widthPixels > metrics.heightPixels
                val bar = if (sideways) systemDimension("navigation_bar_width") else systemDimension("navigation_bar_height")
                out.set(
                    0,
                    systemDimension("status_bar_height"),
                    metrics.widthPixels - if (sideways) bar else 0,
                    metrics.heightPixels - if (sideways) 0 else bar,
                )
            }
        }

        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        fun systemDimension(name: String): Int {
            val id = app.resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) app.resources.getDimensionPixelSize(id) else 0
        }

        /** Play state: the button, the screen kept on, the wave's pace and the refresh rate. */
        fun playing(isPlaying: Boolean) {
            if (!added || closing) return
            play.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            params.flags = if (isPlaying) {
                params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            }
            // ask for the display's highest rate (up to 120 Hz) while the wave moves
            params.preferredRefreshRate = if (isPlaying) highestRate() else 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                runCatching {
                    View::class.java.getMethod("setRequestedFrameRate", Float::class.javaPrimitiveType)
                        .invoke(wave, if (isPlaying) FRAME_RATE_HIGH else FRAME_RATE_DEFAULT)
                }
            }
            update()
            wave.wake()
        }

        fun highestRate(): Float {
            val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0f
            val current = display.mode
            return display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .map { it.refreshRate }
                .filter { it <= 120.5f }
                .maxOrNull() ?: 0f
        }

        fun showControls(show: Boolean) {
            handler.removeCallbacks(hideControls)
            controls.isVisible = show
            if (show) handler.postDelayed(hideControls, CONTROLS_MS)
        }

        fun restartControlsTimer() {
            handler.removeCallbacks(hideControls)
            if (controls.isVisible) handler.postDelayed(hideControls, CONTROLS_MS)
        }

        /** Opens YT's player page again; the video returns there in [returnTo]. */
        fun expand() {
            val intent = Intent(app, host).apply {
                putExtra(IntentData.maximizePlayer, true)
                putExtra(IntentData.audioOnly, false)
                putExtra(IntentData.offlinePlayer, offline)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            runCatching { app.startActivity(intent) }.onFailure { Log.w(TAG, "expand: not opened", it) }
        }

        fun returnTo(to: PlayerView, done: () -> Unit) {
            if (closing) return
            page = WeakReference(to)
            if (!moved) {
                // the video never left the page: just go
                close(pause = false)
                done()
                return
            }
            closing = true
            whenReady({ (to.videoSurfaceView as? SurfaceView)?.holder?.surface?.isValid == true }) {
                // closed meanwhile (the page went away): close() has handed the video back
                if (!added) return@whenReady
                PlayerView.switchTargetView(controller, video, to)
                moved = false
                Log.i(TAG, "surface→page")
                // a frame or two for the page's picture, then the window goes
                root.postOnAnimation {
                    root.postOnAnimation {
                        remove()
                        done()
                    }
                }
            }
        }

        fun close(pause: Boolean) {
            if (closing && !added) return
            closing = true
            if (pause && controller.isConnected) controller.pause()
            if (moved) {
                // back to the page, which shows it once the app is open again
                PlayerView.switchTargetView(controller, video, page.get())
                moved = false
                Log.i(TAG, "surface→page (closed)")
            }
            remove()
        }

        fun remove() {
            if (!added) return
            added = false
            handler.removeCallbacks(check)
            handler.removeCallbacks(hideControls)
            touch.stop()
            target.hide()
            runCatching { controller.removeListener(listener) }
            runCatching { app.unregisterReceiver(screen) }
            YtAudioTap.listening = false
            lastX = params.x
            lastY = params.y
            lastWidth = videoWidth
            runCatching { manager.removeViewImmediate(root) }
            if (shown === this) shown = null
            Log.i(TAG, "removed")
        }

        fun log(what: String) {
            Log.i(
                TAG,
                "float $what: window=${params.x},${params.y},${params.width},${params.height} " +
                    "video=$videoWidth x $videoHeight strip=$strip unit=${wave.unit} corner=$corner",
            )
        }

        /** The window's root: takes over the touches once a drag or a pinch begins. */
        inner class Root(context: Context) : FrameLayout(context) {
            private var cancelled = false

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelled = false
                if (touch.handle(event)) {
                    if (!cancelled) {
                        // a button pressed at the start of the drag does not fire
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        cancelled = true
                    }
                    return true
                }
                if (!cancelled) super.dispatchTouchEvent(event)
                return true
            }

            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                // turned or folded: back inside the screen
                post {
                    layoutFor(videoWidth)
                    clampInto(params)
                    update()
                }
            }
        }

        /** Drag, fling to a side, two-finger resize, taps. */
        inner class Touch {
            private val slop = ViewConfiguration.get(app).scaledTouchSlop
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false
            private var scaling = false
            private var ignoreRest = false
            private var startDistance = 0f
            private var startWidth = 0
            private var velocity: VelocityTracker? = null
            private var snap: ValueAnimator? = null
            private var interrupted = false
            private var moves = 0

            val busy: Boolean get() = dragging || scaling || snap?.isRunning == true

            private val taps = GestureDetector(
                app,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (controls.isVisible && onButton(e)) return true
                        showControls(!controls.isVisible)
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        // two quick taps on a button are two presses of it, not "open YT"
                        if (controls.isVisible && onButton(e)) return true
                        expand()
                        return true
                    }
                },
            )

            fun onButton(e: MotionEvent): Boolean = listOf(play, opener, closer).any { b ->
                val r = Rect()
                b.getHitRect(r)
                r.offset(controls.left + videoBox.left, controls.top + videoBox.top)
                r.contains(e.x.toInt(), e.y.toInt())
            }

            /** True while a drag or pinch has the touches. */
            fun handle(e: MotionEvent): Boolean {
                if (!dragging && !scaling) taps.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // a snap cut short is finished on release, unless a drag takes over
                        interrupted = snap?.isRunning == true
                        snap?.cancel()
                        usableArea(area)
                        downX = e.rawX
                        downY = e.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                        scaling = false
                        ignoreRest = false
                        moves = 0
                        velocity?.recycle()
                        velocity = VelocityTracker.obtain()
                        track(e)
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (!dragging && e.pointerCount == 2) {
                            scaling = true
                            startDistance = spread(e)
                            startWidth = videoWidth
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        track(e)
                        if (scaling && e.pointerCount >= 2) {
                            val distance = spread(e)
                            if (startDistance > 0f && abs(distance - startDistance) > slop) {
                                resize((startWidth * distance / startDistance).roundToInt(), keepCentre = true)
                            }
                        } else if (!scaling && !ignoreRest) {
                            val dx = e.rawX - downX
                            val dy = e.rawY - downY
                            if (!dragging && hypot(dx, dy) > slop) {
                                dragging = true
                                showControls(false)
                                target.show()
                            }
                            if (dragging) {
                                params.x = startX + dx.roundToInt()
                                params.y = startY + dy.roundToInt()
                                clampInto(params)
                                update()
                                val near = target.near(e.rawX, e.rawY)
                                videoBox.foreground = if (near) RED else null
                                if (++moves % 10 == 0 && YtGuard.isEmulator) log("drag")
                            }
                        }
                    }

                    MotionEvent.ACTION_POINTER_UP -> {
                        if (scaling && e.pointerCount <= 2) {
                            scaling = false
                            // the finger left on the window does not start a drag
                            ignoreRest = true
                        } else if (dragging) {
                            // the finger that led the drag went up: the remaining one leads on
                            // from where it is, without a jump
                            val stays = if (e.actionIndex == 0) 1 else 0
                            val offsetX = e.rawX - e.getX(0)
                            val offsetY = e.rawY - e.getY(0)
                            downX = e.getX(stays) + offsetX
                            downY = e.getY(stays) + offsetY
                            startX = params.x
                            startY = params.y
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = dragging
                        val wasScaling = scaling
                        track(e)
                        velocity?.computeCurrentVelocity(1000)
                        val vx = velocity?.xVelocity ?: 0f
                        velocity?.recycle()
                        velocity = null
                        dragging = false
                        scaling = false
                        if (wasDragging) {
                            val drop = e.actionMasked == MotionEvent.ACTION_UP && target.near(e.rawX, e.rawY)
                            target.hide()
                            videoBox.foreground = null
                            if (drop) {
                                close(pause = true)
                                return true
                            }
                            settle(vx)
                            log("released")
                            return true
                        }
                        if (wasScaling || ignoreRest) {
                            settle(0f)
                            return true
                        }
                        if (interrupted) settle(0f)
                    }
                }
                return dragging || scaling
            }

            /** Feeds the speed tracker in screen coordinates (the window itself moves). */
            private fun track(e: MotionEvent) {
                val copy = MotionEvent.obtain(e)
                copy.setLocation(e.rawX, e.rawY)
                velocity?.addMovement(copy)
                copy.recycle()
            }

            private fun spread(e: MotionEvent): Float =
                hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

            /** To the nearer side, or the side it was flung to, like Android's picture-in-picture. */
            fun settle(vx: Float) {
                usableArea(area)
                val total = videoHeight + strip
                val left = area.left + margin
                val right = area.right - videoWidth - margin
                val fling = FLING_DP_S * density
                val toX = when {
                    vx > fling -> right
                    vx < -fling -> left
                    params.x + videoWidth / 2 > area.centerX() -> right
                    else -> left
                }
                val toY = params.y.coerceIn(area.top + margin, max(area.top + margin, area.bottom - total - margin))
                val fromX = params.x
                val fromY = params.y
                snap?.cancel()
                snap = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = SNAP_MS
                    interpolator = DecelerateInterpolator(1.6f)
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        params.x = (fromX + (toX - fromX) * f).roundToInt()
                        params.y = (fromY + (toY - fromY) * f).roundToInt()
                        update()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            applyPendingAspect()
                        }
                    })
                    start()
                }
            }

            fun stop() {
                snap?.cancel()
                snap = null
                velocity?.recycle()
                velocity = null
            }
        }
    }

    /**
     * The ✕ shown at the bottom while the player is dragged; dropping the player on it closes it.
     * A window that takes no touches, at most 80% opaque, so touches can pass it (Android 12+).
     */
    private class CloseTarget(val app: Context, val manager: WindowManager, val density: Float) {
        private val size = (64 * density).roundToInt()
        private val view = object : View(app) {
            val circle = Paint(Paint.ANTI_ALIAS_FLAG)
            val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = 2.5f * density
                strokeCap = Paint.Cap.ROUND
            }
            var hot = false

            override fun onDraw(canvas: Canvas) {
                val c = width / 2f
                val r = c * if (hot) 1f else 0.8f
                circle.color = if (hot) 0xE6E53935.toInt() else 0x99000000.toInt()
                canvas.drawCircle(c, c, r, circle)
                val a = r * 0.35f
                canvas.drawLine(c - a, c - a, c + a, c + a, cross)
                canvas.drawLine(c - a, c + a, c + a, c - a, cross)
            }
        }
        private var added = false
        private val centre = FloatArray(2)

        fun show() {
            if (added) return
            val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.currentWindowMetrics.bounds
            } else {
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                manager.defaultDisplay.getRealMetrics(m)
                Rect(0, 0, m.widthPixels, m.heightPixels)
            }
            val bottom = (96 * density).roundToInt()
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bounds.centerX() - size / 2
                y = bounds.bottom - bottom - size
                alpha = 0.8f
                title = "YT close"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            }
            centre[0] = params.x + size / 2f
            centre[1] = params.y + size / 2f
            view.hot = false
            added = runCatching { manager.addView(view, params) }.isSuccess
        }

        /** Whether a finger at ([x], [y]) on the screen is over the ✕. */
        fun near(x: Float, y: Float): Boolean {
            if (!added) return false
            val hot = hypot(x - centre[0], y - centre[1]) <= size * 0.8f
            if (hot != view.hot) {
                view.hot = hot
                view.invalidate()
            }
            return hot
        }

        fun hide() {
            if (!added) return
            added = false
            runCatching { manager.removeViewImmediate(view) }
        }
    }

    private val RED = android.graphics.drawable.ColorDrawable(0x66E53935)

    /** View.REQUESTED_FRAME_RATE_CATEGORY_HIGH / _DEFAULT (Android 15), called by name. */
    private const val FRAME_RATE_HIGH = -4f
    private const val FRAME_RATE_DEFAULT = 0f
}
