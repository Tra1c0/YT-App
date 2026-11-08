package com.github.libretube.ui.yt

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * YT: the background of the activity's player container. Turned, the container keeps out of the
 * system's side bar and the camera cutout with a side padding (YtSideInsets), and the player page
 * inside it is narrower by that much; the strips it leaves were transparent, so the screen below
 * (home) showed beside the open page. This paints just those strips in the page's colour while a
 * page is open ([open] = 1) and fades them as it docks (0: nothing, as when no page is there).
 */
class YtSideFill(color: Int) : Drawable() {
    private val paint = Paint().apply { this.color = color }
    private val baseAlpha = paint.alpha
    private var left = 0
    private var right = 0

    /** 1 while a page is open, 0 docked or without a page. */
    var open = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v == field) return
            field = v
            invalidateSelf()
        }

    fun sides(left: Int, right: Int) {
        if (left == this.left && right == this.right) return
        this.left = left.coerceAtLeast(0)
        this.right = right.coerceAtLeast(0)
        invalidateSelf()
    }

    fun color(color: Int) {
        paint.color = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (open <= 0f || (left == 0 && right == 0)) return
        paint.alpha = (baseAlpha * open).roundToInt()
        val b = bounds
        if (left > 0) canvas.drawRect(b.left.toFloat(), b.top.toFloat(), (b.left + left).toFloat(), b.bottom.toFloat(), paint)
        if (right > 0) canvas.drawRect((b.right - right).toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
