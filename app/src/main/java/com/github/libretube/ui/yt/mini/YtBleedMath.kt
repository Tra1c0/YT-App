package com.github.libretube.ui.yt.mini

import kotlin.math.max
import kotlin.math.roundToInt

/*
 * YT: the colour bleed of the Glas mini player in numbers (pure, no Android). A tiny copy of the
 * video frame is blurred, a little more saturated (as the glass's vibrancy) and eased towards the
 * last one, so a cut in the video does not flash the glass; it is then drawn stretched over the
 * picture's edges and the glass around it.
 */
object YtBleedMath {
    /** The long side of the copied frame, in px: enough for colour, too few for detail. */
    const val LONG_SIDE = 48

    /** The size ([width], [height]) of the copy of a frame with this [aspect] (width / height). */
    fun copySize(aspect: Float): Pair<Int, Int> {
        val a = if (aspect.isFinite() && aspect > 0f) aspect.coerceIn(0.4f, 4f) else 16f / 9f
        return if (a >= 1f) {
            LONG_SIDE to (LONG_SIDE / a).roundToInt().coerceAtLeast(4)
        } else {
            (LONG_SIDE * a).roundToInt().coerceAtLeast(4) to LONG_SIDE
        }
    }

    /**
     * Where a video of [aspect] lies when zoomed to cover a view [width] x [height] (centred, as
     * the player crops the docked strip): left, top, width, height in the view's px.
     */
    fun zoomFrame(width: Float, height: Float, aspect: Float): FloatArray {
        if (width <= 0f || height <= 0f || !(aspect > 0f)) return floatArrayOf(0f, 0f, max(width, 0f), max(height, 0f))
        val frameWidth: Float
        val frameHeight: Float
        if (width / height > aspect) {
            frameWidth = width
            frameHeight = width / aspect
        } else {
            frameHeight = height
            frameWidth = height * aspect
        }
        return floatArrayOf((width - frameWidth) / 2f, (height - frameHeight) / 2f, frameWidth, frameHeight)
    }

    /**
     * Blurs the opaque ARGB [pixels] ([width] x [height]) in place: [passes] box blurs of
     * [radius], each first along the rows, then the columns (edges clamped).
     */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 2) {
        if (radius <= 0 || width <= 0 || height <= 0) return
        val line = IntArray(max(width, height))
        repeat(passes) {
            for (y in 0 until height) blurLine(pixels, y * width, 1, width, radius, line)
            for (x in 0 until width) blurLine(pixels, x, width, height, radius, line)
        }
    }

    private fun blurLine(pixels: IntArray, start: Int, step: Int, count: Int, radius: Int, line: IntArray) {
        for (i in 0 until count) line[i] = pixels[start + i * step]
        val span = 2 * radius + 1
        var r = 0
        var g = 0
        var b = 0
        for (k in -radius..radius) {
            val c = line[k.coerceIn(0, count - 1)]
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
        for (i in 0 until count) {
            pixels[start + i * step] = (0xFF shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
            val out = line[(i - radius).coerceIn(0, count - 1)]
            val inc = line[(i + radius + 1).coerceIn(0, count - 1)]
            r += ((inc shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((inc shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (inc and 0xFF) - (out and 0xFF)
        }
    }

    /** Saturates the colours of [pixels] by [amount] (1 = as they are), as the glass's vibrancy. */
    fun saturate(pixels: IntArray, amount: Float) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val grey = 0.2126f * r + 0.7152f * g + 0.0722f * b
            pixels[i] = (0xFF shl 24) or
                (channel(grey + (r - grey) * amount) shl 16) or
                (channel(grey + (g - grey) * amount) shl 8) or
                channel(grey + (b - grey) * amount)
        }
    }

    /** Eases [next] towards [previous] (same size): [keep] of the old colour stays (0 to 1). */
    fun ease(previous: IntArray, next: IntArray, keep: Float) {
        if (previous.size != next.size) return
        val k = keep.coerceIn(0f, 1f)
        for (i in next.indices) {
            val a = previous[i]
            val b = next[i]
            next[i] = (0xFF shl 24) or
                (channel(((a shr 16) and 0xFF) * k + ((b shr 16) and 0xFF) * (1f - k)) shl 16) or
                (channel(((a shr 8) and 0xFF) * k + ((b shr 8) and 0xFF) * (1f - k)) shl 8) or
                channel((a and 0xFF) * k + (b and 0xFF) * (1f - k))
        }
    }

    private fun channel(value: Float): Int = value.roundToInt().coerceIn(0, 255)
}
