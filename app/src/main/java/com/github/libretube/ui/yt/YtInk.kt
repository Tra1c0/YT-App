package com.github.libretube.ui.yt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb

/**
 * YT: the type colours for one surface. A palette's text roles are made for its background, but
 * the sheets and overlays stand on other surfaces: Formen draws a light lavender sheet in a dark
 * design, Frost a pale frosted one. Every word on such a surface takes its colour from here: the
 * design's own role where it already reads (4.5:1), else pushed towards black or white only as far
 * as needed (YtContrast.readable). [back] is the opaque colour the surface shows, as ARGB.
 */
@Immutable
data class YtInk(
    val back: Int,
    val text: Color,
    val textSecondary: Color,
) {
    /** [color] made readable on this surface. */
    fun read(color: Color): Color = Color(YtContrast.readable(color.toArgb(), back))

    companion object {
        /** The text roles of [palette] on the opaque [back]. */
        fun on(palette: YtPalette, back: Int): YtInk {
            val opaque = back or (0xFF shl 24)
            return YtInk(
                back = opaque,
                text = Color(YtContrast.readable(palette.text.toArgb(), opaque)),
                textSecondary = Color(YtContrast.readable(palette.textSecondary.toArgb(), opaque)),
            )
        }

        /**
         * The text roles of [palette] on a surface that may change between [backs] (the ends of a
         * gradient): readable on all of them.
         */
        fun onAll(palette: YtPalette, backs: IntArray): YtInk = YtInk(
            back = backs.first() or (0xFF shl 24),
            text = Color(YtContrast.readableOnAll(palette.text.toArgb(), backs)),
            textSecondary = Color(YtContrast.readableOnAll(palette.textSecondary.toArgb(), backs)),
        )
    }
}

/**
 * The ink of the sheet or overlay the composable sits on, set with [ProvideYtInk]. Null outside
 * a sheet: the shared pieces (also used by the sign-in window) keep the design's roles there.
 */
val LocalYtInk = compositionLocalOf<YtInk?> { null }

/** Gives the content of a surface of colour [back] (opaque ARGB) its ink. */
@Composable
fun ProvideYtInk(palette: YtPalette, back: Int, content: @Composable () -> Unit) {
    val ink = remember(palette, back) { YtInk.on(palette, back) }
    androidx.compose.runtime.CompositionLocalProvider(LocalYtInk provides ink, content = content)
}

/** The ink for words directly on the current sheet, or the design's own roles outside one. */
@Composable
fun ytInk(palette: YtPalette): YtInk =
    LocalYtInk.current ?: remember(palette) { YtInk(palette.background.toArgb() or (0xFF shl 24), palette.text, palette.textSecondary) }

/**
 * The ink for words on a pill or card drawn with [ytSurface] on the current sheet: checked
 * against the surface as it looks over the sheet. Outside a sheet the design's own roles.
 */
@Composable
fun ytSurfaceInk(palette: YtPalette): YtInk {
    val sheet = LocalYtInk.current ?: return ytInk(palette)
    return remember(palette, sheet.back) { YtInk.onAll(palette, ytSurfaceTones(palette, sheet.back)) }
}

/**
 * The ink for words on a fill of [fill] (possibly see-through) on the current sheet. Outside a
 * sheet null, so the caller keeps the colour it had.
 */
@Composable
fun ytInkOnFill(palette: YtPalette, fill: Color): YtInk? {
    val sheet = LocalYtInk.current ?: return null
    return remember(palette, sheet.back, fill) { YtInk.on(palette, YtContrast.over(fill.toArgb(), sheet.back)) }
}

/**
 * The opaque colour of a sheet that is drawn in the design's surface over a half black scrim on
 * the screen (quick sheet, account sheet). Editorial's square sheet is drawn in the background.
 */
fun ytScrimSheetBack(palette: YtPalette, square: Boolean, scrim: Int = 0x80000000.toInt()): Int {
    val dimmed = YtContrast.over(scrim, palette.background.toArgb())
    val sheet = YtContrast.over(palette.surface.toArgb(), dimmed)
    return if (square) YtContrast.over(palette.background.toArgb(), sheet) else sheet
}

/** The opaque colour of a sheet filled with [ytSheetFill] (Feed-Regler, "Warum sehe ich das?"). */
fun ytFillSheetBack(palette: YtPalette): Int = YtContrast.over(palette.surface.toArgb(), palette.background.toArgb())

/** [this] over [bg], pushed towards black or white as far as needed for [min]:1 (YtContrast). */
internal fun Color.readableOn(bg: Color, min: Double = YtContrast.BODY): Color =
    Color(YtContrast.readable(toArgb(), bg.compositeOver(Color.Black).toArgb(), min))
