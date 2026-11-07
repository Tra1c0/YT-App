package com.github.libretube.ui.yt.search

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.isGone
import com.github.libretube.R
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.extensions.setWatchProgressLength
import com.github.libretube.ui.yt.SeenBadge
import com.github.libretube.ui.yt.YtSeenPrefs
import com.github.libretube.ui.yt.feed.YtSeenMark
import com.github.libretube.ui.yt.palette
import com.github.libretube.ui.yt.seenBadge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT: the watched mark of the home cards (YtSeen) on the rows of the search results, which are
 * classic View rows: a started video's bar in the design's accent, a finished one's thumbnail
 * dimmed with the design's small "Gesehen" badge. The classic look and the setting off keep
 * LibreTube's own bar.
 */
object YtSeenRow {
    /** In place of `progress.setWatchProgressLength(videoId, duration)` on a search row. */
    fun bind(thumbnail: ImageView, progress: View, videoId: String, durationSeconds: Long?) {
        val palette = YtDesign.current().palette
        if (palette == null || !YtSeenPrefs.enabled) {
            thumbnail.foreground = null
            progress.setWatchProgressLength(videoId, durationSeconds ?: 0L)
            return
        }
        // the row is recycled: forget the last video's mark until this one's is read
        thumbnail.foreground = null
        progress.isGone = true
        thumbnail.setTag(R.id.yt_seen_row, videoId)
        CoroutineScope(Dispatchers.IO).launch {
            val position = DatabaseHelper.getWatchPosition(videoId)
            withContext(Dispatchers.Main) {
                if (thumbnail.getTag(R.id.yt_seen_row) != videoId) return@withContext
                val mark = YtSeenMark.of(position, durationSeconds)
                when {
                    mark == null -> Unit
                    mark.watched -> {
                        val word = thumbnail.context.getString(R.string.yt_seen_badge)
                        thumbnail.foreground = SeenDrawable(seenBadge(palette), word, thumbnail.resources.displayMetrics.density)
                    }
                    else -> {
                        progress.setWatchProgressLength(position, durationSeconds ?: 0L)
                        progress.setBackgroundColor(palette.accent.toArgb())
                    }
                }
            }
        }
    }
}

/** The dimmed picture and the badge, drawn over the thumbnail. */
private class SeenDrawable(private val badge: SeenBadge, word: String, private val density: Float) : Drawable() {
    private val word = if (badge.uppercase) word.uppercase() else word
    private val dim = Paint().apply { color = android.graphics.Color.BLACK; alpha = ((1f - YtSeenMark.WATCHED_BRIGHTNESS) * 255).toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badge.fill.toArgb() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = badge.ink.toArgb()
        textSize = 10f * density
        isFakeBoldText = true
        letterSpacing = if (badge.uppercase) 0.08f else 0f
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = badge.ink.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, dim)
        val d = density
        val inset = 6 * d
        val icon = 9 * d
        val pad = 5 * d
        val metrics = ink.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val height = maxOf(icon, if (badge.iconOnly) 0f else textHeight) + 6 * d
        val textWidth = if (badge.iconOnly) 0f else ink.measureText(word)
        val width = if (badge.iconOnly) height else pad + icon + 3 * d + textWidth + pad
        val box = RectF(b.left + inset, b.top + inset, b.left + inset + width, b.top + inset + height)
        val radius = if (badge.cornerDp < 0) height / 2f else badge.cornerDp * d
        canvas.drawRoundRect(box, radius, radius, fill)
        val iconX = box.left + if (badge.iconOnly) (width - icon) / 2f else pad
        val iconY = box.top + (height - icon) / 2f
        val check = Path().apply {
            moveTo(iconX, iconY + icon * 0.55f)
            lineTo(iconX + icon * 0.38f, iconY + icon * 0.9f)
            lineTo(iconX + icon, iconY + icon * 0.15f)
        }
        canvas.drawPath(check, tick)
        if (!badge.iconOnly) {
            val baseline = box.top + (height - textHeight) / 2f - metrics.ascent
            canvas.drawText(word, box.left + pad + icon + 3 * d, baseline, ink)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
