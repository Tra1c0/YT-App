package com.github.libretube.ui.yt.ambilight

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Surface
import com.github.libretube.ui.yt.ambilight.YtAmbilightZones as Z

/**
 * YT: copies the video's frame for the ambilight and works out its zone colours, all on a
 * background thread of its own. The video is a SurfaceView, which cannot be drawn or captured
 * otherwise (Pitfall P5); PixelCopy reads its current buffer, scaled down by the GPU to a tiny
 * bitmap (48 x 27 for 16:9). On older Android versions PixelCopy copies synchronously on the
 * calling thread, so it is never called on the main thread here.
 *
 * One copy at a time ([busy]); the bitmap, the pixel array and every scratch array are reused,
 * so a sample allocates nothing. The result waits in [take] for the main thread, which [done]
 * tells (posted to [main]).
 */
internal class YtAmbilightSampler(private val main: Handler, private val done: Runnable) {
    private val thread: HandlerThread by lazy {
        HandlerThread("yt-ambilight", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    }
    private val worker: Handler by lazy { Handler(thread.looper) }

    // the worker thread's own
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private val area = IntArray(4)
    private val sums = FloatArray(YtAmbilightMath.SUMS_SIZE)
    private val raw = FloatArray(Z.COUNT * Z.RAW)
    private val lab = FloatArray(3)
    private val work = FloatArray(Z.COUNT * Z.CHANNELS)
    private var startedNs = 0L

    // handed to the main thread under the lock
    private val lock = Any()
    private val shared = FloatArray(Z.COUNT * Z.CHANNELS)
    private val sharedArea = IntArray(4)
    private var sharedWidth = 0
    private var sharedHeight = 0

    @Volatile private var surface: Surface? = null

    @Volatile private var width = 0

    @Volatile private var height = 0

    /** A copy is under way (requested, not yet [done]). */
    @Volatile var busy = false
        private set

    /** The PixelCopy result of the last copy (PixelCopy.SUCCESS or an error code). */
    @Volatile var lastCode = PixelCopy.SUCCESS
        private set

    /** Mean time of the copy itself (request to result), ms. */
    @Volatile var copyMs = 0f
        private set

    /** Mean CPU time of the colour maths after the copy, ms. */
    @Volatile var cpuMs = 0f
        private set

    @Volatile var samples = 0
        private set

    /**
     * Asks for a copy of [source] at [w] x [h] px; false while one is under way. Main thread
     * (the view's surface is read there).
     */
    fun request(source: Surface, w: Int, h: Int): Boolean {
        if (busy) return false
        busy = true
        surface = source
        width = w
        height = h
        worker.post(copy)
        return true
    }

    /** The zones of the last good copy into [out], the picture's area and the copy's size. */
    fun take(out: FloatArray, outArea: IntArray, size: IntArray) {
        synchronized(lock) {
            shared.copyInto(out, 0, 0, shared.size)
            sharedArea.copyInto(outArea)
            size[0] = sharedWidth
            size[1] = sharedHeight
        }
    }

    private val copy = Runnable {
        val source = surface
        val w = width
        val h = height
        if (source == null || !source.isValid || w <= 0 || h <= 0) {
            finish(PixelCopy.ERROR_SOURCE_INVALID)
            return@Runnable
        }
        val target = bitmap?.takeIf { it.width == w && it.height == h && !it.isRecycled }
            ?: runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()?.also { bitmap = it }
        if (target == null) {
            finish(PixelCopy.ERROR_DESTINATION_INVALID)
            return@Runnable
        }
        startedNs = SystemClock.elapsedRealtimeNanos()
        try {
            PixelCopy.request(source, target, listener, worker)
        } catch (_: IllegalArgumentException) {
            // the surface went away between the check and the request
            finish(PixelCopy.ERROR_SOURCE_INVALID)
        } catch (_: RuntimeException) {
            finish(PixelCopy.ERROR_UNKNOWN)
        }
    }

    private val listener = PixelCopy.OnPixelCopyFinishedListener { code ->
        val copiedNs = SystemClock.elapsedRealtimeNanos()
        copyMs = mean(copyMs, (copiedNs - startedNs) / 1e6f)
        if (code == PixelCopy.SUCCESS) {
            bitmap?.let { process(it) }
            cpuMs = mean(cpuMs, (SystemClock.elapsedRealtimeNanos() - copiedNs) / 1e6f)
            samples++
        }
        finish(code)
    }

    private fun process(source: Bitmap) {
        val w = source.width
        val h = source.height
        if (pixels.size != w * h) pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        YtAmbilightMath.activeArea(pixels, w, h, area)
        YtAmbilightMath.zones(pixels, w, area, raw, sums)
        YtAmbilightMath.targets(raw, work, lab)
        synchronized(lock) {
            work.copyInto(shared)
            area.copyInto(sharedArea)
            sharedWidth = w
            sharedHeight = h
        }
    }

    private fun finish(code: Int) {
        lastCode = code
        surface = null
        busy = false
        main.post(done)
    }

    private fun mean(old: Float, next: Float): Float = if (old == 0f) next else old + (next - old) * 0.1f
}
