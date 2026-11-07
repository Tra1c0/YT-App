package com.github.libretube.ui.yt.player

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.KieselPanel
import com.github.libretube.ui.yt.YtSignature
import com.github.libretube.ui.yt.palette
import com.github.libretube.ui.yt.ytNight

/** YT: the outline of a hold-and-drag tile, one per design. */
enum class YtHoldTileShape {
    /** Classic and YouTube: a dark rounded tile. */
    ROUNDED,

    /** Movo: a flat tile in its own colour; armed it gets a white frame. */
    TILE,

    /** After: a frame of film with sprocket holes along its edges. */
    FILM,

    /** Side: a speech bubble whose tail points at the video. */
    BUBBLE,

    /** Clay: soft dough, a shadow below and a light rim on top. */
    DOUGH,

    /** Editorial: a printed box, a black rule and a hard offset shadow, square corners. */
    PRINT,

    /** Glas: a glass pill with a bright edge and a gloss on its upper half. */
    GLASS,

    /** Pop: a wobbling blob. */
    BLOB,

    /** Formen: a cookie with a scalloped edge. */
    COOKIE,

    /** Frost: a dark frosted tile with a faint edge. */
    FROST,

    /** Kiesel: a black pebble capsule. */
    CAPSULE,
}

/**
 * YT: how hold and drag on the video looks in the current design: the backdrop around the shrunk
 * video, the tiles (shape, a fill per direction, edge, shadow), what an armed tile turns into and
 * how round the video's corners get. Every fill is opaque, and the text on a tile is picked from
 * its fill, so a label reads in every design (a light design no longer meets white text).
 */
class YtHoldTileStyle private constructor(
    val shape: YtHoldTileShape,
    @ColorInt val backdrop: Int,
    /** Up, down, left, right (the order of [YtHoldDir]). */
    private val fills: IntArray,
    /** 0: no edge. */
    @ColorInt val stroke: Int,
    val strokeDp: Float,
    /** Corner radius in dp; negative: half the shorter side (a pill). */
    val cornerDp: Float,
    @ColorInt val armedFill: Int,
    /** 0: no shadow. */
    @ColorInt val shadow: Int,
    val shadowDxDp: Float,
    val shadowDyDp: Float,
    val videoCornerDp: Float,
    @ColorInt val dark: Int,
    @ColorInt val light: Int,
    @ColorInt val accent: Int,
    val typeface: Typeface?,
    val stiffness: Float,
    val damping: Float,
) {
    @ColorInt
    fun fill(dir: YtHoldDir): Int = fills[dir.ordinal]

    /** The text and icon colour on [fill]: the design's ink on light fills, its light on dark ones. */
    @ColorInt
    fun on(@ColorInt fill: Int): Int {
        val opaque = ColorUtils.setAlphaComponent(fill, 255)
        val inkContrast = ColorUtils.calculateContrast(dark, opaque)
        val lightContrast = ColorUtils.calculateContrast(light, opaque)
        return if (inkContrast >= lightContrast) dark else light
    }

    companion object {
        fun current(context: Context): YtHoldTileStyle {
            val hud = YtPlayerHudStyle.current(context)
            val palette = YtDesign.current().palette
            val accent = ColorUtils.setAlphaComponent(hud.accent, 255)

            fun style(
                shape: YtHoldTileShape,
                backdrop: Int,
                fills: IntArray,
                armed: Int = accent,
                stroke: Int = 0,
                strokeDp: Float = 0f,
                cornerDp: Float = 14f,
                shadow: Int = 0,
                shadowDx: Float = 0f,
                shadowDy: Float = 0f,
                videoCorner: Float = 12f,
                // at night a light design's ink is light: the dark choice stays dark
                dark: Int = palette?.ink?.toArgb()?.takeIf { !ytNight } ?: 0xFF101012.toInt(),
                light: Int = 0xFFFFFFFF.toInt(),
            ) = YtHoldTileStyle(
                shape, opaque(backdrop), IntArray(4) { opaque(fills[it % fills.size]) }, stroke, strokeDp, cornerDp,
                opaque(armed), shadow, shadowDx, shadowDy, videoCorner, opaque(dark), opaque(light), accent,
                hud.typeface, hud.stiffness, hud.damping,
            )

            if (palette == null) {
                return style(YtHoldTileShape.ROUNDED, 0xFF0E0E10.toInt(), intArrayOf(0xFF2A2A30.toInt()))
            }
            val bg = palette.background.toArgb()
            val surface = palette.surface.toArgb()
            val raised = palette.raised.toArgb()
            val ink = palette.ink.toArgb()
            val text = palette.text.toArgb()
            val lime = palette.lime.toArgb()
            val lavender = palette.lavender.toArgb()
            val pink = palette.pink.toArgb()
            val paletteAccent = palette.accent.toArgb()
            return when (palette.signature) {
                YtSignature.YOUTUBE -> style(
                    YtHoldTileShape.ROUNDED, bg, intArrayOf(raised), cornerDp = 16f, videoCorner = 14f, light = text,
                )

                // coloured tiles, one colour per direction; armed, a white frame and the light colour
                YtSignature.MOVO -> style(
                    YtHoldTileShape.TILE, bg, intArrayOf(paletteAccent, lime, pink, lavender), armed = text,
                    stroke = 0xFFFFFFFF.toInt(), strokeDp = 3f, cornerDp = 10f, videoCorner = 10f, light = text,
                )

                // film frames: darker than the room, the sprockets in the room's colour
                YtSignature.AFTER -> style(
                    YtHoldTileShape.FILM, bg, intArrayOf(ColorUtils.blendARGB(bg, 0xFF000000.toInt(), 0.55f)),
                    cornerDp = 2f, videoCorner = 2f, light = text,
                )

                YtSignature.SIDE -> style(
                    YtHoldTileShape.BUBBLE, bg, intArrayOf(raised), cornerDp = 18f, videoCorner = 16f, light = text,
                )

                YtSignature.CLAY -> style(
                    YtHoldTileShape.DOUGH, bg, intArrayOf(palette.raised.toArgb()), stroke = if (ytNight) 0x26FFF0E4 else 0x99FFFFFF.toInt(),
                    strokeDp = 1.5f, cornerDp = 24f, shadow = ColorUtils.setAlphaComponent(if (ytNight) 0xFF000000.toInt() else ink, 0x38),
                    shadowDy = 5f, videoCorner = 22f,
                )

                // paper boxes; armed the box is printed black
                YtSignature.EDITORIAL -> style(
                    YtHoldTileShape.PRINT, bg, intArrayOf(if (ytNight) raised else 0xFFFFFFFF.toInt()), armed = ink, stroke = ink,
                    strokeDp = 1.5f, cornerDp = 0f, shadow = ink, shadowDx = 3f, shadowDy = 3f, videoCorner = 0f,
                )

                YtSignature.GLASS -> style(
                    YtHoldTileShape.GLASS, bg, intArrayOf(ColorUtils.blendARGB(bg, 0xFFFFFFFF.toInt(), if (ytNight) 0.12f else 0.72f)),
                    armed = paletteAccent, stroke = if (ytNight) 0x66CFEDE7 else 0xFFFFFFFF.toInt(), strokeDp = 1.2f, cornerDp = -1f,
                    shadow = ColorUtils.setAlphaComponent(if (ytNight) 0xFF000000.toInt() else ink, 0x1F), shadowDy = 4f, videoCorner = 20f,
                )

                YtSignature.POP -> style(
                    YtHoldTileShape.BLOB, bg, intArrayOf(lime, lavender, pink, palette.raised.toArgb()),
                    armed = paletteAccent, cornerDp = 22f, videoCorner = 24f,
                )

                // cookies in the four candy colours; armed, the light surface
                YtSignature.SHAPES -> style(
                    YtHoldTileShape.COOKIE, bg, intArrayOf(lime, lavender, pink, paletteAccent), armed = surface,
                    cornerDp = 20f, videoCorner = 18f,
                )

                // Frost's own surfaces are see-through white on sage, too pale for white text: here
                // the tiles are its ink frosted with a little sage, the room a darker sage
                YtSignature.FROST -> style(
                    YtHoldTileShape.FROST, ColorUtils.blendARGB(bg, ink, 0.45f), intArrayOf(ColorUtils.blendARGB(ink, bg, 0.22f)),
                    stroke = 0x47FFFFFF, strokeDp = 1f, cornerDp = 18f, videoCorner = 18f, light = text,
                )

                YtSignature.KIESEL -> style(
                    // at night the black capsules turn into the lighter panels, with light type
                    YtHoldTileShape.CAPSULE, bg, intArrayOf(if (ytNight) KieselPanel.toArgb() else ink), armed = paletteAccent, cornerDp = -1f,
                    videoCorner = 28f, light = if (ytNight) text else surface,
                )

            }
        }

        private fun opaque(@ColorInt colour: Int): Int = ColorUtils.setAlphaComponent(colour, 255)
    }
}
