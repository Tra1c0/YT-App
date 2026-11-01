package com.github.libretube.ui.views

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.marginLeft
import androidx.media3.common.util.UnstableApi
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.extensions.dpToPx
import com.github.libretube.helpers.ThemeHelper

/**
 * TimeBar that displays chapters as breaks in the progress bar.
 */
@UnstableApi
class ChapterTimeBar(
    context: Context,
    attributeSet: AttributeSet? = null,
) : MarkableTimeBar(context, attributeSet) {
    private var chapters = listOf<ChapterSegment>()
    private var length: Int = 0

    private val progressBarHeight = 2f.dpToPx()
    private val chapterBreakWidth = 4f.dpToPx()
    private val eraserPaint = Paint().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blendMode = BlendMode.CLEAR
        } else {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
    }

    // YT: the markers on top of the bar: the rewind memory's return point (a ghost of the
    // thumb) and, while scrubbing, the notch where the video was (letting go there changes nothing)
    private val markerDensity = resources.displayMetrics.density
    private var markerColor = ThemeHelper.getThemeColor(context, com.google.android.material.R.attr.colorSecondary)
    private var returnMarkerMs = -1L
    private var returnMarkerAlpha = 0f
    private val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * markerDensity
    }
    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // YT: the loop's handles come above the chapter breaks (drawMarkers)
        drawsLoopHandlesItself = false
    }

    override fun onDraw(canvas: Canvas) {
        val saveCount = canvas.saveLayer(null, null)
        super.onDraw(canvas)
        drawChapters(canvas)

        canvas.restoreToCount(saveCount)
        // outside the layer, so the chapter breaks do not erase them
        drawMarkers(canvas)
    }


    private fun drawChapters(canvas: Canvas) {
        if (exoPlayer == null) return

        val horizontalOffset = (parent as View).marginLeft
        length = width - horizontalOffset * 2
        val marginY = (height - progressBarHeight) / 2

        // skip the first chapter as it always starts at 0:00
        chapters.drop(1).forEach {
            val center = it.start.toLength() + horizontalOffset

            canvas.drawRect(
                Rect(
                    center - (chapterBreakWidth / 2),
                    marginY,
                    center + (chapterBreakWidth / 2),
                    marginY + progressBarHeight
                ),
                eraserPaint,
            )
        }
    }

    private fun drawMarkers(canvas: Canvas) {
        drawLoopHandles(canvas)
        val cy = height / 2f
        val origin = scrubOriginMs
        if (origin >= 0L) {
            xOf(origin)?.let { x ->
                notchPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, 0xCC)
                val w = markerDensity
                val h = 6f * markerDensity
                canvas.drawRoundRect(x - w, cy - h, x + w, cy + h, w, w, notchPaint)
            }
        }
        if (returnMarkerMs >= 0L && returnMarkerAlpha > 0.01f) {
            xOf(returnMarkerMs)?.let { x ->
                val a = returnMarkerAlpha.coerceIn(0f, 1f)
                val r = 5f * markerDensity * (0.7f + 0.3f * a)
                ghostFill.color = ColorUtils.setAlphaComponent(markerColor, (0x66 * a).toInt())
                ghostRing.color = ColorUtils.setAlphaComponent(Color.WHITE, (0xE6 * a).toInt())
                canvas.drawCircle(x, cy, r, ghostFill)
                canvas.drawCircle(x, cy, r, ghostRing)
            }
        }
    }

    private fun Long.toLength(): Int {
        return (this * 1000f / exoPlayer!!.duration * length).toInt()
    }

    fun setChapters(chapters: List<ChapterSegment>) {
        this.chapters = chapters
    }

    /** YT: the chapters' starts in ms as the bar shows them (for the loop's "this chapter"). */
    val loopChapterStartsMs: LongArray get() = chapterStartsMs()

    override fun chapterStartsMs(): LongArray =
        chapters.filter { it.highlightDrawable == null }.map { it.start * 1000 }.toLongArray()

    override fun setPlayedColor(playedColor: Int) {
        super.setPlayedColor(playedColor)
        markerColor = playedColor
    }

    /**
     * YT: the rewind memory's return point, drawn as a ghost of the thumb at [positionMs] with
     * [alpha] (it fades with the chip); a negative position removes it.
     */
    fun setReturnMarker(positionMs: Long, alpha: Float) {
        if (positionMs == returnMarkerMs && alpha == returnMarkerAlpha) return
        returnMarkerMs = positionMs
        returnMarkerAlpha = alpha
        invalidate()
    }
}
