package com.github.libretube.ui.yt.ambilight

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtDrag
import com.github.libretube.ui.yt.palette

/**
 * YT: the player's background with the ambilight in its letterbox or pillarbox bars (in the page
 * and in fullscreen, where the player view moves into a dialog and takes this with it). Black
 * as before where nothing glows. [frame]: the content frame the video surface fills, so its
 * place inside the player is known.
 */
internal class YtAmbilightBarsDrawable(private val player: View, private val frame: View?) : Drawable() {
    private val video = RectF()
    private val minBar = 6f * player.resources.displayMetrics.density

    /** True if the last draw drew a glowing bar. */
    var drewBars = false
        private set

    /** Whether the player shows bars beside or above the picture now. */
    fun barsShown(): Boolean = locate() && (
        video.left >= minBar || player.width - video.right >= minBar ||
            video.top >= minBar || player.height - video.bottom >= minBar
        )

    /** The picture's rect in the player's coordinates; false if it is not known. */
    private fun locate(): Boolean {
        val f = frame ?: return false
        if (f.width <= 0 || f.height <= 0 || !f.isShown) return false
        var x = 0f
        var y = 0f
        var v: View = f
        while (v !== player) {
            x += v.left + v.translationX
            y += v.top + v.translationY
            v = v.parent as? View ?: return false
        }
        video.set(x, y, x + f.width, y + f.height)
        return true
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(BLACK)
        drewBars = locate() && YtAmbilightPaint.bars(canvas, player.width, player.height, video, minBar)
    }

    /** As the black colour drawable it replaces: an opaque rect (shadows, outlines). */
    override fun getOutline(outline: android.graphics.Outline) {
        outline.setRect(bounds)
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        private const val BLACK = -0x1000000
    }
}

/**
 * YT: the ambilight above the screens while the video is docked as the mini player: a halo
 * around the mini player and, with "Leiste & Hintergrund", the screen edges, the header and the
 * nav bar's edge. A plain view over the activity's content that never takes a touch; the mini
 * player's card is clipped out, so nothing is drawn over the picture. It draws nothing while no
 * video is docked or the ambilight is off.
 */
internal class YtAmbilightLayer(private val activity: Activity) : View(activity), YtAmbilight.Listener {
    private val at = IntArray(2)
    private val navAt = IntArray(2)
    private val card = RectF()
    private val bar = RectF()
    private var nav: View? = null
    private var drew = false
    private var lastBounds: Rect? = null
    private var lastDocked = false
    private val density = resources.displayMetrics.density

    init {
        isClickable = false
        isLongClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    /** The mini player moved, docked or left: draw again if it matters. */
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val bounds = YtDrag.miniBounds
        val docked = YtDock.docked
        if (bounds !== lastBounds || docked != lastDocked) {
            lastBounds = bounds
            lastDocked = docked
            if (wants() || drew) invalidate()
        }
        true
    }

    private fun wants(): Boolean =
        YtAmbilight.showing && YtDock.docked && YtDrag.miniBounds != null &&
            (YtAmbilight.place == YtAmbilightPlace.DOCKED || YtAmbilight.place == YtAmbilightPlace.MOVING)

    override fun onAmbilight() {
        if (wants() || drew) invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        nav = activity.findViewById(R.id.bottomNav)
        YtAmbilight.addListener(this)
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        YtAmbilight.removeListener(this)
        nav = null
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        drew = false
        if (!wants()) return
        val bounds = YtDrag.miniBounds ?: return
        getLocationInWindow(at)
        card.set(
            (bounds.left - at[0]).toFloat(),
            (bounds.top - at[1]).toFloat(),
            (bounds.right - at[0]).toFloat(),
            (bounds.bottom - at[1]).toFloat(),
        )
        val fade = YtAmbilightMath.smoothstep(0.85f, 1f, YtDock.progress)
        if (fade <= 0f) return
        val save = canvas.save()
        // never over the mini player (its picture is a surface)
        canvas.clipOutRect(card)
        if (YtAmbilight.settings.chrome) {
            YtAmbilightPaint.edges(canvas, width, height, density, fade)
            val n = nav
            if (n != null && n.isShown && n.width > 0) {
                n.getLocationInWindow(navAt)
                bar.set(
                    (navAt[0] - at[0]).toFloat(),
                    (navAt[1] - at[1]).toFloat(),
                    (navAt[0] - at[0] + n.width).toFloat(),
                    (navAt[1] - at[1] + n.height).toFloat(),
                )
                YtAmbilightPaint.nav(canvas, bar, density, fade)
            }
        }
        YtAmbilightPaint.halo(canvas, card, density, fade)
        canvas.restoreToCount(save)
        drew = true
    }

    companion object {
        private const val TAG = "yt_ambilight_layer"

        /**
         * Keeps the layer in step with the setting: only the designs get one, only while
         * "yt_ambilight" is on (nothing is built while it is off), and a switch at runtime adds or
         * removes it.
         */
        fun attach(activity: Activity) {
            sync(activity)
            val owner = activity as? androidx.lifecycle.LifecycleOwner ?: return
            val prefs = runCatching { com.github.libretube.helpers.PreferenceHelper.settings }.getOrNull() ?: return
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == YtAmbilightPrefs.ENABLED) sync(activity)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            // the preferences hold a listener weakly: the observer below keeps it alive
            owner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                @Suppress("unused") private val keep = listener
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    prefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            })
        }

        private fun sync(activity: Activity) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val existing = content.findViewWithTag<View>(TAG)
            // the classic look glows behind the player only (the page draws that)
            val wanted = YtAmbilightPrefs.read().enabled && YtDesign.current().palette != null
            if (!wanted) {
                if (existing != null) content.removeView(existing)
                return
            }
            if (existing != null) return
            val layer = YtAmbilightLayer(activity).apply { tag = TAG }
            content.addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
}

/** The context's theme colour of [attr], or [fallback]. */
internal fun ytThemeColor(context: Context, attr: Int, fallback: Int): Int =
    runCatching { com.google.android.material.color.MaterialColors.getColor(context, attr, fallback) }.getOrDefault(fallback)
