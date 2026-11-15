package com.github.libretube.ui.yt.builder

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * YT: theme values as Compose drawing values: paints as brushes, text specs as text styles, the
 * per-corner radii with corner smoothing as a shape. Brushes depend on the size, so the renderer
 * builds them in drawWithCache (once per size and style, not per frame).
 */
object YtBuilderPaint {
    /** [p] (already resolved: no token references) as a brush over [size]. */
    fun brush(p: YtPaint, size: Size): Brush = when (p.type) {
        YtPaintType.SOLID -> androidx.compose.ui.graphics.SolidColor(Color(p.color))
        YtPaintType.LINEAR -> {
            val stops = p.stops.map { it.at to Color(it.color) }.toTypedArray()
            // the line through the centre at the angle, long enough to span the box
            val rad = p.angle * PI.toFloat() / 180f
            val dx = cos(rad)
            val dy = sin(rad)
            val half = (kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            Brush.linearGradient(*stops, start = c - Offset(dx * half, dy * half), end = c + Offset(dx * half, dy * half))
        }
        YtPaintType.RADIAL -> {
            val stops = p.stops.map { it.at to Color(it.color) }.toTypedArray()
            val r = max(1f, p.radius * max(size.width, size.height))
            Brush.radialGradient(*stops, center = Offset(size.width * p.centerX, size.height * p.centerY), radius = r)
        }
    }

    fun color(p: YtPaint): Color = Color(if (p.type == YtPaintType.SOLID) p.color else p.stops.firstOrNull()?.color ?: p.color)

    fun weight(w: Int): FontWeight = FontWeight(w.coerceIn(1, 1000))

    /** The text style of [spec] with its type token, in the theme's [assets] fonts. */
    fun textStyle(spec: YtTextSpec, t: YtTokensResolver, assets: File?, colorOverride: Color? = null): TextStyle {
        val token = t.type(spec.styleRef)
        val size = if (spec.size > 0f) spec.size else token.size
        return TextStyle(
            color = colorOverride ?: color(t.resolve(spec.color)),
            fontFamily = YtBuilderFonts.family(token.font, assets),
            fontWeight = weight(if (spec.weight > 0) spec.weight else token.weight),
            fontStyle = if (token.italic) FontStyle.Italic else FontStyle.Normal,
            fontSize = size.sp,
            lineHeight = if (token.lineHeight > 0f) token.lineHeight.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            letterSpacing = token.letterSpacing.sp,
            textAlign = when (spec.align) {
                YtTextAlign.START -> TextAlign.Start
                YtTextAlign.CENTER -> TextAlign.Center
                YtTextAlign.END -> TextAlign.End
            },
        )
    }
}

/**
 * Per-corner radii (dp, start/end as the layout direction has them) with corner smoothing: 0 is a
 * circular arc, towards 1 the curve starts earlier along the side and runs into the corner more
 * gently (a squircle-like continuous corner). Radii are scaled down together where they would
 * overlap, so very large values become a pill rather than a broken path.
 */
class YtCornerShape(private val corners: YtCorners) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = density.density
        val rtl = layoutDirection == LayoutDirection.Rtl
        var tl = (if (rtl) corners.topEnd else corners.topStart) * d
        var tr = (if (rtl) corners.topStart else corners.topEnd) * d
        var br = (if (rtl) corners.bottomStart else corners.bottomEnd) * d
        var bl = (if (rtl) corners.bottomEnd else corners.bottomStart) * d
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, max(w, 0f), max(h, 0f)))
        // the scale CSS uses for overlapping radii
        val f = min(1f, min(min(w / max(tl + tr, 1e-3f), w / max(bl + br, 1e-3f)), min(h / max(tl + bl, 1e-3f), h / max(tr + br, 1e-3f))))
        tl *= f; tr *= f; br *= f; bl *= f
        val s = corners.smoothing.coerceIn(0f, 1f)
        if (s <= 0.001f) {
            return Outline.Rounded(
                androidx.compose.ui.geometry.RoundRect(
                    0f, 0f, w, h,
                    androidx.compose.ui.geometry.CornerRadius(tl), androidx.compose.ui.geometry.CornerRadius(tr),
                    androidx.compose.ui.geometry.CornerRadius(br), androidx.compose.ui.geometry.CornerRadius(bl),
                ),
            )
        }
        return Outline.Generic(smoothPath(w, h, tl, tr, br, bl, s))
    }

    companion object {
        /**
         * A rounded rectangle whose corners are cubic curves that start (1 + s) times the radius
         * along each side (clamped to half the side), with control points pulled towards the
         * corner: a smooth, continuous corner that keeps the look of the radius.
         */
        fun smoothPath(w: Float, h: Float, tl: Float, tr: Float, br: Float, bl: Float, s: Float): Path {
            fun reach(r: Float, side: Float) = min(r * (1f + s), side / 2f)
            // how far along the reach the control points sit: 0.55 is a circle, more is squarer
            val k = 0.55f + 0.3f * s
            val p = Path()
            val a = reach(tl, min(w, h))
            val b = reach(tr, min(w, h))
            val c = reach(br, min(w, h))
            val e = reach(bl, min(w, h))
            p.moveTo(a, 0f)
            p.lineTo(w - b, 0f)
            p.cubicTo(w - b + b * k, 0f, w, b - b * k, w, b)
            p.lineTo(w, h - c)
            p.cubicTo(w, h - c + c * k, w - c + c * k, h, w - c, h)
            p.lineTo(e, h)
            p.cubicTo(e - e * k, h, 0f, h - e + e * k, 0f, h - e)
            p.lineTo(0f, a)
            p.cubicTo(0f, a - a * k, a - a * k, 0f, a, 0f)
            p.close()
            return p
        }
    }

    override fun equals(other: Any?): Boolean = other is YtCornerShape && other.corners == corners
    override fun hashCode(): Int = corners.hashCode()
}
