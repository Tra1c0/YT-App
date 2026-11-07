package com.github.libretube.ui.yt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * YT: how the shared screens (video page, personal area, Shorts) draw pills and cards, so they
 * match the home screen of each design.
 */
enum class YtSurfaceStyle {
    /** Plain surface color (YouTube neu, Movo, After, Side). */
    FLAT,

    /** Clay: puffy, lit from above, warm shadow. */
    CLAY,

    /** Glas: frosted white with a bright edge. */
    GLASS,

    /** Editorial: no fill, a thin black outline, square corners. */
    EDITORIAL,

    /** Pop: lavender blobs. */
    POP,

    /** Formen: bright widgets on the night background. */
    SHAPES,

    /** Frost: frosted glass on sage. */
    FROST,
}

/** The shape of a pill in this design (square in Editorial, a blob in Pop). */
fun ytPillShape(palette: YtPalette): Shape = when (palette.surfaceStyle) {
    YtSurfaceStyle.EDITORIAL -> RectangleShape
    YtSurfaceStyle.POP -> BlobShape(0.6f, 0.5f, 0.56f, 0.64f)
    else -> RoundedCornerShape(50)
}

/** The shape of a card in this design. */
fun ytCardShape(palette: YtPalette, radius: Int = 16): Shape = when (palette.surfaceStyle) {
    YtSurfaceStyle.EDITORIAL -> RectangleShape
    YtSurfaceStyle.CLAY -> RoundedCornerShape((radius * 1.4f).dp)
    YtSurfaceStyle.POP -> RoundedCornerShape((radius * 1.3f).dp)
    else -> RoundedCornerShape(radius.dp)
}

/** Fills a pill or card the way the design does. */
fun Modifier.ytSurface(palette: YtPalette, shape: Shape): Modifier = when (palette.surfaceStyle) {
    YtSurfaceStyle.CLAY -> this
        .shadow(6.dp, shape, clip = false, ambientColor = ytDayNight(Color(0x66B07A66), Color(0x66000000)), spotColor = ytDayNight(Color(0x80B07A66), Color(0x80000000)))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(ytDayNight(Color(0xFFFBEDE2), Color(0xFF46352E)), palette.surface)))
        .border(1.dp, ytDayNight(Color(0x80FFFFFF), Color(0x1FFFF0E4)), shape)
    YtSurfaceStyle.GLASS -> this.ytGlass(shape)
    YtSurfaceStyle.EDITORIAL -> this
        .clip(shape)
        .background(palette.background)
        .border(1.dp, palette.text, shape)
    YtSurfaceStyle.POP -> this
        .clip(shape)
        .background(palette.raised)
    YtSurfaceStyle.SHAPES -> this
        .clip(shape)
        .background(palette.raised)
    YtSurfaceStyle.FROST -> this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x1FFFFFFF))))
        .border(1.dp, Color(0x59FFFFFF), shape)
    YtSurfaceStyle.FLAT -> this
        .clip(shape)
        .background(palette.surface)
}

/**
 * The opaque colours a [ytSurface] pill or card shows when drawn over [under] (ARGB): the two ends
 * of its gradient, or its one fill twice. Keep in step with [ytSurface]; words on such a surface
 * are checked against both ends (YtInk).
 */
fun ytSurfaceTones(palette: YtPalette, under: Int): IntArray {
    fun on(top: Color, bottom: Color = top) = intArrayOf(YtContrast.over(top.toArgb(), under), YtContrast.over(bottom.toArgb(), under))
    return when (palette.surfaceStyle) {
        YtSurfaceStyle.CLAY -> on(ytDayNight(Color(0xFFFBEDE2), Color(0xFF46352E)), palette.surface)
        YtSurfaceStyle.GLASS -> on(ytDayNight(Color(0x9EFFFFFF), Color(0x4D6FB5AA)), ytDayNight(Color(0x5CFFFFFF), Color(0x296FB5AA)))
        YtSurfaceStyle.EDITORIAL -> on(palette.background)
        YtSurfaceStyle.POP, YtSurfaceStyle.SHAPES -> on(palette.raised)
        YtSurfaceStyle.FROST -> on(Color(0x40FFFFFF), Color(0x1FFFFFFF))
        YtSurfaceStyle.FLAT -> on(palette.surface)
    }
}
