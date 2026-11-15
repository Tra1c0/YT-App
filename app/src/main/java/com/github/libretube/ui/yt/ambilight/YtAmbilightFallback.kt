package com.github.libretube.ui.yt.ambilight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.github.libretube.api.obj.PreviewFrames
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.util.OnlineTimeFrameReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.github.libretube.ui.yt.ambilight.YtAmbilightZones as Z

/**
 * YT: the ambilight's colours when the video's frame cannot be copied (a protected surface, an
 * emulator, a GPU that refuses): the storyboard still of the current position (the same stills
 * as the time bar's preview), or else the thumbnail. Seldom (every few seconds, and only when
 * the still changed), so a new bitmap per still is fine here; the maths are the same as for a
 * copied frame.
 */
internal class YtAmbilightFallback {
    enum class Kind { STORYBOARD, THUMBNAIL }

    private var frames: List<PreviewFrames> = emptyList()
    private var thumbnail: String? = null
    private var lastKey: Long = Long.MIN_VALUE
    private var lastKind: Kind? = null

    private val small: Bitmap by lazy { Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888) }
    private val canvas: Canvas by lazy { Canvas(small) }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect(0, 0, WIDTH, HEIGHT)
    private val pixels = IntArray(WIDTH * HEIGHT)
    private val area = IntArray(4)
    private val sums = FloatArray(YtAmbilightMath.SUMS_SIZE)
    private val raw = FloatArray(Z.COUNT * Z.RAW)
    private val lab = FloatArray(3)

    /** The video changed: its stills and thumbnail. */
    fun video(previews: List<PreviewFrames>, thumbnailUrl: String?) {
        frames = previews
        thumbnail = thumbnailUrl
        lastKey = Long.MIN_VALUE
        lastKind = null
    }

    /** Whether the same still as last time would come, so nothing needs loading. */
    fun same(positionMs: Long): Boolean = lastKind != null && key(positionMs) == lastKey

    /**
     * The zone targets of the still at [positionMs] into [out]; the kind of still, or null if
     * there is none (no storyboard, no thumbnail, loading failed). One call at a time.
     */
    suspend fun sample(context: Context, positionMs: Long, out: FloatArray): Kind? {
        val key = key(positionMs)
        val image: Bitmap?
        val kind: Kind
        if (key >= 0L) {
            kind = Kind.STORYBOARD
            image = runCatching { OnlineTimeFrameReceiver(context, frames).getFrameAtTime(positionMs) }.getOrNull()
        } else {
            kind = Kind.THUMBNAIL
            image = runCatching { ImageHelper.getImage(context, thumbnail) }.getOrNull()
        }
        if (image == null) return null
        val ok = withContext(Dispatchers.Default) { zonesOf(image, out) }
        if (!ok) return null
        lastKey = key
        lastKind = kind
        return kind
    }

    private fun zonesOf(image: Bitmap, out: FloatArray): Boolean = runCatching {
        // hardware bitmaps cannot be drawn on a software canvas
        val readable = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
        readable ?: return false
        canvas.drawBitmap(readable, null, dst, paint)
        small.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        YtAmbilightMath.activeArea(pixels, WIDTH, HEIGHT, area)
        YtAmbilightMath.zones(pixels, WIDTH, area, raw, sums)
        YtAmbilightMath.targets(raw, out, lab)
        true
    }.getOrDefault(false)

    /** The still's number for [positionMs] (the storyboard's frame), or the thumbnail's key. */
    private fun key(positionMs: Long): Long {
        val best = frames.maxByOrNull { it.frameHeight } ?: return THUMBNAIL_KEY
        if (best.durationPerFrame <= 0L) return THUMBNAIL_KEY
        return positionMs.coerceAtLeast(0L) / best.durationPerFrame
    }

    companion object {
        const val WIDTH = 48
        const val HEIGHT = 27
        private const val THUMBNAIL_KEY = -1L
    }
}
