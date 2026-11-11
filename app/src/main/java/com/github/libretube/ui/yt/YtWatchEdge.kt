package com.github.libretube.ui.yt

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.widget.ScrollView
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.views.YtEdgeScrollView
import kotlin.math.PI
import kotlin.math.sin

/**
 * YT: where the video page slides under the video, each design draws its own edge instead of a
 * straight cut: Kiesel a rounded lip, Pop a wave, Formen scallops, Clay a puffy shelf with a
 * shadow, Glas and Frost a frosted strip with a light edge, After a long cinematic fade,
 * Editorial a printed rule (a deliberate line, as on a folded page), YouTube, Movo and Side a
 * soft fade with a hairline, Frequency small scallops with a coral hairline, Synthwave a neon
 * horizon (a line from magenta to cyan over a faint magenta glow). The scroll bar is hidden; the page has no use for it.
 */
object YtWatchEdge {
    fun install(scroll: ScrollView) {
        val design = YtDesign.current()
        val palette = design.palette ?: return
        scroll.isVerticalScrollBarEnabled = false
        val view = scroll as? YtEdgeScrollView ?: return
        val density = scroll.resources.displayMetrics.density
        // the color the page lies on at its top (it takes on the thumbnail's color), read as it
        // is drawn, so the edge blends into it
        val fallback = palette.background.toArgb()
        fun bg(): Int = when (val drawable = scroll.background) {
            is ColorDrawable -> drawable.color
            is GradientDrawable -> drawable.colors?.firstOrNull() ?: fallback
            else -> fallback
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        fun dp(v: Float) = v * density
        fun alpha(color: Int, strength: Float) = ColorUtils.setAlphaComponent(color, (android.graphics.Color.alpha(color) * strength).toInt())

        fun fade(canvas: Canvas, width: Int, strength: Float, top: Float, length: Float, color: Int = bg()) {
            paint.shader = LinearGradient(0f, top, 0f, top + length, alpha(color, strength), alpha(color, 0f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, top, width.toFloat(), top + length, paint)
            paint.shader = null
        }

        fun line(canvas: Canvas, width: Int, y: Float, height: Float, color: Int) {
            paint.color = color
            canvas.drawRect(0f, y, width.toFloat(), y + height, paint)
        }

        view.edgeLength = dp(48f).toInt()
        view.edge = when (design) {
            YtDesign.KIESEL -> { canvas, width, s ->
                // a rounded lip: the page shows through a window with round top corners
                val r = dp(26f)
                paint.color = alpha(bg(), s)
                path.reset()
                path.moveTo(0f, 0f)
                path.lineTo(width.toFloat(), 0f)
                path.lineTo(width.toFloat(), r)
                path.arcTo(RectF(width - 2 * r, 0f, width.toFloat(), 2 * r), 0f, -90f)
                path.lineTo(r, 0f)
                path.arcTo(RectF(0f, 0f, 2 * r, 2 * r), 270f, -90f)
                path.close()
                canvas.drawPath(path, paint)
                fade(canvas, width, s * 0.7f, 0f, dp(18f))
            }
            YtDesign.POP -> { canvas, width, s ->
                // a wavy lavender lip
                paint.color = alpha(bg(), s)
                path.reset()
                path.moveTo(0f, 0f)
                path.lineTo(width.toFloat(), 0f)
                val base = dp(7f)
                val amp = dp(5f)
                val wave = dp(52f)
                var x = width.toFloat()
                while (x >= 0f) {
                    path.lineTo(x, base + amp * sin(2 * PI * x / wave).toFloat())
                    x -= dp(2f)
                }
                path.lineTo(0f, base)
                path.close()
                canvas.drawPath(path, paint)
                fade(canvas, width, s * 0.6f, base + amp, dp(12f))
            }
            YtDesign.SHAPES -> { canvas, width, s ->
                // scallops, like the cookie shapes of the design
                paint.color = alpha(bg(), s)
                val r = dp(9f)
                canvas.drawRect(0f, 0f, width.toFloat(), dp(4f), paint)
                var x = r
                while (x - r < width) {
                    canvas.drawCircle(x, dp(4f), r, paint)
                    x += 2 * r
                }
                fade(canvas, width, s * 0.6f, dp(4f) + r, dp(12f))
            }
            YtDesign.FREQUENCY -> { canvas, width, s ->
                // Formen's scallops, smaller, over YouTube neu's soft fade, a coral hairline on top
                paint.color = alpha(bg(), s)
                val r = dp(6f)
                canvas.drawRect(0f, 0f, width.toFloat(), dp(3f), paint)
                var x = r
                while (x - r < width) {
                    canvas.drawCircle(x, dp(3f), r, paint)
                    x += 2 * r
                }
                fade(canvas, width, s * 0.6f, dp(3f) + r, dp(14f))
                line(canvas, width, 0f, dp(1f), alpha(ColorUtils.setAlphaComponent(palette.accent.toArgb(), 140), s))
            }
            YtDesign.SYNTHWAVE -> { canvas, width, s ->
                // a neon horizon: the soft fade, a magenta glow under a line from magenta to cyan
                fade(canvas, width, s * 0.7f, 0f, dp(22f))
                fade(canvas, width, s * 0.22f, dp(1.5f), dp(10f), palette.accent.toArgb())
                paint.shader = LinearGradient(
                    0f, 0f, width.toFloat(), 0f,
                    alpha(palette.accent.toArgb(), s), alpha(palette.lime.toArgb(), s), Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, width.toFloat(), dp(1.5f), paint)
                paint.shader = null
            }
            YtDesign.CLAY -> { canvas, width, s ->
                // a puffy shelf with a soft shadow below it
                paint.color = alpha(bg(), s)
                canvas.drawRoundRect(RectF(-dp(20f), -dp(20f), width + dp(20f), dp(12f)), dp(18f), dp(18f), paint)
                fade(canvas, width, s * 0.55f, dp(10f), dp(16f), 0x80B07A66.toInt())
            }
            YtDesign.GLASS, YtDesign.FROST -> { canvas, width, s ->
                // frosted strip with a light edge
                val frost = if (design == YtDesign.FROST) 0xE61F2A26.toInt() else 0xD9FFFFFF.toInt()
                fade(canvas, width, s, 0f, dp(30f), frost)
                line(canvas, width, 0f, dp(1f), alpha(0xCCFFFFFF.toInt(), s))
            }
            YtDesign.AFTER -> { canvas, width, s -> fade(canvas, width, s, 0f, dp(56f)) }
            YtDesign.EDITORIAL -> { canvas, width, s ->
                // the fold of a page: white margin and a printed rule
                line(canvas, width, 0f, dp(4f), alpha(bg(), s))
                line(canvas, width, dp(4f), dp(1.5f), alpha(palette.text.toArgb(), s))
            }
            YtDesign.MOVO -> { canvas, width, s ->
                fade(canvas, width, s, 0f, dp(26f))
                line(canvas, width, 0f, dp(2f), alpha(palette.accent.toArgb(), s))
            }
            else -> { canvas, width, s ->
                fade(canvas, width, s, 0f, dp(22f))
                line(canvas, width, 0f, dp(1f), alpha(ColorUtils.setAlphaComponent(palette.text.toArgb(), 30), s))
            }
        }
    }
}
