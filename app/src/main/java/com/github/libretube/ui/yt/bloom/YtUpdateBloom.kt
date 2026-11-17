package com.github.libretube.ui.yt.bloom

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.ui.graphics.toArgb
import androidx.fragment.app.FragmentActivity
import androidx.media3.session.MediaController
import com.github.libretube.ui.fragments.AudioPlayerFragment
import com.github.libretube.ui.fragments.PlayerFragment
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtMotionPrefs
import com.github.libretube.ui.yt.palette

/**
 * YT: while the update dialog is up, playback is paused (and resumed on a plain dismiss, only if
 * this dialog paused it) and the app behind becomes a blurred, glowing still picture: a PixelCopy
 * of the window at 1/8 scale plus a separate copy of the video's SurfaceView (Pitfall P5), run
 * through [YtBloomMath] and shown as a full-screen ImageView on the decor view, below the dialog
 * window. Any failure leaves the plain dim.
 */
object YtUpdateBloom {
    private const val SCALE = 8
    private const val FADE_MS = 260L
    private const val DIALOG_DIM = 0.05f

    private val main = Handler(Looper.getMainLooper())

    private var overlay: ImageView? = null
    private var generation = 0

    // process-wide so that a dialog created again after a rotation keeps the ownership
    private var pausedId: String? = null
    private var owns = false

    /** The dialog is on screen (onStart): pause and capture. */
    fun onShow(activity: Activity?, dialog: Dialog?) {
        activity ?: return
        runCatching { pause(activity) }
        val token = ++generation
        removeNow()
        val decor = activity.window?.decorView ?: return
        // after the dialog's first layout, so the window behind is final
        decor.postDelayed({
            if (token == generation && !activity.isFinishing && !activity.isDestroyed) {
                runCatching { capture(activity, dialog, token) }
            }
        }, 60)
    }

    /** The dialog stops (activity stop, rotation, destroy): no overlay stays. */
    fun onHide() {
        generation++
        removeNow()
    }

    /** The dialog is gone. [changingConfig]: only a rotation, keep the pause ownership. */
    fun onDismiss(activity: Activity?, changingConfig: Boolean, startedUpdate: Boolean) {
        generation++
        if (changingConfig) {
            removeNow()
            return
        }
        fadeOut()
        if (!startedUpdate && activity != null) runCatching { resume(activity) }
        owns = false
        pausedId = null
    }

    /** The activity goes for good with the dialog: forget, never resume. */
    fun onDestroy() {
        generation++
        removeNow()
    }

    fun forget() {
        owns = false
        pausedId = null
    }

    // --- pause / resume ---

    private fun controller(activity: Activity): MediaController? {
        val fragments = (activity as? FragmentActivity)?.supportFragmentManager?.fragments.orEmpty()
        fragments.filterIsInstance<PlayerFragment>().firstOrNull()?.controller()?.let { return it }
        return fragments.filterIsInstance<AudioPlayerFragment>().firstOrNull()?.ytController
    }

    private fun pause(activity: Activity) {
        if (owns) return
        val c = controller(activity) ?: return
        if (c.isPlaying || c.playWhenReady) {
            pausedId = c.currentMediaItem?.mediaId
            c.pause()
            owns = true
        }
    }

    private fun resume(activity: Activity) {
        if (!owns) return
        val c = controller(activity) ?: return
        // something else was started meanwhile: leave it alone
        if (c.playWhenReady || c.isPlaying) return
        if (c.currentMediaItem?.mediaId != pausedId) return
        c.play()
    }

    // --- bloom ---

    private fun capture(activity: Activity, dialog: Dialog?, token: Int) {
        val window = activity.window ?: return
        val decor = window.decorView
        val w = decor.width / SCALE
        val h = decor.height / SCALE
        if (w < 2 || h < 2) return
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bitmap, { code ->
            if (token != generation) return@request
            if (code != PixelCopy.SUCCESS) return@request
            copyVideo(activity, bitmap, token) { finish(activity, dialog, bitmap, token) }
        }, main)
    }

    private fun copyVideo(activity: Activity, into: Bitmap, token: Int, done: () -> Unit) {
        val surface = videoSurface(activity)
        if (surface == null || !surface.isShown || surface.width <= 0 || surface.height <= 0 ||
            surface.holder?.surface?.isValid != true
        ) {
            done()
            return
        }
        val at = IntArray(2)
        surface.getLocationInWindow(at)
        val rect = RectF(
            at[0].toFloat() / SCALE, at[1].toFloat() / SCALE,
            (at[0] + surface.width).toFloat() / SCALE, (at[1] + surface.height).toFloat() / SCALE,
        )
        val vw = (surface.width / SCALE).coerceAtLeast(1)
        val vh = (surface.height / SCALE).coerceAtLeast(1)
        val video = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, video, { code ->
                if (token != generation) return@request
                if (code == PixelCopy.SUCCESS) Canvas(into).drawBitmap(video, null, rect, null)
                done()
            }, main)
        } catch (_: IllegalArgumentException) {
            done()
        }
    }

    private fun videoSurface(activity: Activity): SurfaceView? {
        val fragment = (activity as? FragmentActivity)?.supportFragmentManager?.fragments.orEmpty()
            .filterIsInstance<PlayerFragment>().firstOrNull() ?: return null
        return runCatching { fragment.binding.player.videoSurfaceView as? SurfaceView }.getOrNull()
    }

    private fun finish(activity: Activity, dialog: Dialog?, frame: Bitmap, token: Int) {
        if (token != generation || activity.isFinishing || activity.isDestroyed) return
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        val accent = runCatching { YtDesign.current().palette?.accent?.toArgb() }.getOrNull()
        val out = YtBloomMath.bloom(pixels, w, h, YtBloomMath.Params(accent = accent))
        val result = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val decor = activity.window?.decorView as? ViewGroup ?: return
        removeNow()
        val view = ImageView(activity).apply {
            setImageBitmap(result)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = 10_000f
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        decor.addView(view)
        overlay = view
        dialog?.window?.setDimAmount(DIALOG_DIM)
        if (YtMotionPrefs.enabled) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    private fun fadeOut() {
        val view = overlay ?: return
        overlay = null
        if (!YtMotionPrefs.enabled) {
            detach(view)
            return
        }
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction { detach(view) }.start()
    }

    private fun removeNow() {
        val view = overlay ?: return
        overlay = null
        view.animate().cancel()
        detach(view)
    }

    private fun detach(view: ImageView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.setImageDrawable(null)
    }
}
