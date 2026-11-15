package com.github.libretube.ui.views

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * YT: the scrolling area below the player. A design can draw an edge over the top of it, where
 * the page slides under the video, instead of the content being cut straight off; the edge
 * grows in over the first [edgeLength] px of scrolling, so the unscrolled page stays untouched.
 */
class YtEdgeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    /** Draws the edge into a canvas [width] wide at the top of the visible area. */
    var edge: ((canvas: Canvas, width: Int, strength: Float) -> Unit)? = null
        set(value) {
            field = value
            invalidate()
        }

    var edgeLength = 1

    /**
     * Drawn under the content, into a canvas [width] x [height] at the top of the visible area
     * (the ambilight's glow below the video): over the background, behind every child.
     */
    var under: ((canvas: Canvas, width: Int, height: Int) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) setWillNotDraw(false)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val under = under ?: return
        val save = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        under(canvas, width, height)
        canvas.restoreToCount(save)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val edge = edge ?: return
        val strength = (scrollY / edgeLength.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        if (strength <= 0f) return
        val save = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        edge(canvas, width, strength)
        canvas.restoreToCount(save)
    }
}
