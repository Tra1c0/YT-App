package com.github.libretube.ui.yt.mini

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * YT: the colour of the playing video for the glass around it (Glas: the picture's edges dissolve
 * into the slab, its colour bleeds into the glass). The video is a SurfaceView, which cannot be
 * blurred or masked, so a tiny copy of its frame (PixelCopy, a few times a second) is blurred and
 * drawn over the picture's edges and on the glass beside it instead.
 *
 * Copies only while [run]'s condition holds (docked, playing, on screen); paused, the last colour
 * stays. A surface that cannot be copied (protected video) leaves [frame] null: the designs then
 * draw a plain glass tint.
 */
@Stable
class YtMiniBleed {
    /** The last blurred frame, or null (nothing copied yet, or the video cannot be copied). */
    var frame by mutableStateOf<Bitmap?>(null)
        private set

    /** Counts the frames, so drawing that reads it draws again for each new one. */
    var generation by mutableIntStateOf(0)
        private set

    /** The video's width / height as last copied. */
    var aspect by mutableFloatStateOf(16f / 9f)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var copy: Bitmap? = null
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var front = 0
    private var pixels = IntArray(0)
    private var previous = IntArray(0)
    private var pending = false
    private var failures = 0

    /**
     * Copies the frame of [surface] every [PERIOD_MS] ms while [active] is true (read as
     * snapshot state: it restarts and stops with it). Runs until its coroutine is cancelled.
     */
    suspend fun run(surface: () -> View?, active: () -> Boolean) {
        snapshotFlow { active() }.collectLatest { on ->
            if (!on) return@collectLatest
            while (true) {
                val view = surface() as? SurfaceView
                // on screen only: not while the page or the app is hidden
                if (view != null && view.isShown && view.windowVisibility == View.VISIBLE) {
                    copyOnce(view)
                }
                // a video that cannot be copied is asked again now and then, not all the time
                delay(if (failures >= MAX_FAILURES) RETRY_MS else PERIOD_MS)
            }
        }
    }

    private suspend fun copyOnce(view: SurfaceView) {
        if (pending || view.width <= 0 || view.height <= 0) return
        val surface = view.holder?.surface ?: return
        if (!surface.isValid) return
        val (width, height) = YtBleedMath.copySize(view.width.toFloat() / view.height)
        val target = copy?.takeIf { it.width == width && it.height == height && !it.isRecycled }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { copy = it }

        pending = true
        val result = try {
            suspendCancellableCoroutine { continuation ->
                PixelCopy.request(view, target, { code -> if (continuation.isActive) continuation.resume(code) }, handler)
            }
        } catch (_: IllegalArgumentException) {
            // the surface went away between the check and the request
            PixelCopy.ERROR_SOURCE_INVALID
        } finally {
            pending = false
        }
        if (result != PixelCopy.SUCCESS) {
            failures++
            if (failures >= MAX_FAILURES && frame != null) {
                frame = null
                generation++
            }
            return
        }
        failures = 0
        publish(target, view.width.toFloat() / view.height)
    }

    /** Blurs the copy, eases it towards the last frame and shows it. */
    private fun publish(source: Bitmap, sourceAspect: Float) {
        val width = source.width
        val height = source.height
        val size = width * height
        if (pixels.size != size) pixels = IntArray(size)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        YtBleedMath.saturate(pixels, SATURATION)
        YtBleedMath.blur(pixels, width, height, BLUR_RADIUS)
        // a cut in the video glides over a few frames instead of flashing the glass
        if (previous.size == size && frame != null) YtBleedMath.ease(previous, pixels, KEEP)
        if (previous.size != size) previous = IntArray(size)
        pixels.copyInto(previous)

        // two buffers: the one drawn now is not written while it may still be on screen
        val next = 1 - front
        val out = buffers[next]?.takeIf { it.width == width && it.height == height && !it.isRecycled }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { buffers[next] = it }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        front = next
        aspect = sourceAspect
        frame = out
        generation++
    }

    companion object {
        /** About 8 copies a second: the colour follows the picture, the cost stays small. */
        const val PERIOD_MS = 125L
        const val RETRY_MS = 2000L
        const val MAX_FAILURES = 3
        const val BLUR_RADIUS = 2
        const val SATURATION = 1.25f
        const val KEEP = 0.45f
    }
}
