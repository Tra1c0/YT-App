package com.github.libretube.ui.yt.shorts

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.BlobShape
import com.github.libretube.ui.yt.CookieShape
import com.github.libretube.ui.yt.YtContrast
import com.github.libretube.ui.yt.YtEaseOut
import com.github.libretube.ui.yt.YtFonts

/**
 * YT: [YtShortsLook] in Compose terms: colours, type, the pill shape, the handle's shape and
 * size, and how the timeline grows in (the design's motion character).
 */
class YtShortsStyle(val look: YtShortsLook) {
    val accent = Color(look.accent)
    val track = Color(look.track)
    val chip = Color(look.chip)
    val chipInk = Color(look.chipInk)
    val heart = Color(look.heart)
    val font: FontFamily = family(look.font)
    val timeFont: FontFamily = family(look.timeFont)
    val upper: Boolean = look.upper
    val barHeight: Dp = look.barDp.dp

    /** The pills: capsule, rounded or square, after the design. */
    val chipShape: Shape = when {
        look.radiusDp < 0 -> RoundedCornerShape(50)
        look.radiusDp == 0 -> RectangleShape
        else -> RoundedCornerShape(look.radiusDp.dp)
    }

    /** The bar itself: square for the square designs, else rounded ends. */
    val barShape: Shape = if (look.radiusDp in 0..2) RectangleShape else RoundedCornerShape(50)

    val knobShape: Shape = when (look.knob) {
        YtShortsKnob.CIRCLE -> RoundedCornerShape(50)
        YtShortsKnob.CAPSULE -> RoundedCornerShape(50)
        YtShortsKnob.SQUARE, YtShortsKnob.FRAME -> RectangleShape
        YtShortsKnob.BLOB -> BlobShape(0.6f, 0.5f, 0.56f, 0.64f)
        YtShortsKnob.COOKIE -> CookieShape(8, 0.08f)
        YtShortsKnob.PEBBLE -> RoundedCornerShape(topStartPercent = 58, topEndPercent = 44, bottomEndPercent = 52, bottomStartPercent = 40)
    }

    /** Width and height of the handle. */
    val knobWidth: Dp = when (look.knob) {
        YtShortsKnob.CAPSULE -> 26.dp
        YtShortsKnob.BLOB, YtShortsKnob.COOKIE, YtShortsKnob.PEBBLE -> 24.dp
        YtShortsKnob.FRAME -> 16.dp
        else -> 18.dp
    }
    val knobHeight: Dp = when (look.knob) {
        YtShortsKnob.CAPSULE -> 14.dp
        YtShortsKnob.BLOB, YtShortsKnob.COOKIE -> 24.dp
        YtShortsKnob.PEBBLE -> 20.dp
        YtShortsKnob.FRAME -> 22.dp
        else -> 18.dp
    }

    /** The handle turns with the position (the cookie of Formen). */
    val knobTurns: Boolean = look.knob == YtShortsKnob.COOKIE

    /** The handle is a frame (After): an outline in the accent. */
    val knobOutline: Boolean = look.knob == YtShortsKnob.FRAME

    /** How the timeline grows in and out. */
    fun reveal(): AnimationSpec<Float> = when (look.motion) {
        YtShortsMotion.CALM -> tween(200, easing = YtEaseOut)
        YtShortsMotion.SPRINGY -> spring(dampingRatio = 0.55f, stiffness = 420f)
        YtShortsMotion.CRISP -> tween(110, easing = LinearEasing)
        YtShortsMotion.CINEMATIC -> tween(340, easing = FastOutSlowInEasing)
        YtShortsMotion.FLUID -> spring(dampingRatio = 0.78f, stiffness = 260f)
    }

    /** How the handle swells while held. */
    fun press(): AnimationSpec<Float> = when (look.motion) {
        YtShortsMotion.CRISP -> tween(80, easing = LinearEasing)
        YtShortsMotion.SPRINGY -> spring(dampingRatio = 0.4f, stiffness = 520f)
        else -> spring(dampingRatio = 0.7f, stiffness = 600f)
    }

    /** The accent where it marks something on [background] (a chip): darkened or lightened until it shows (3:1). */
    fun accentOn(background: Color): Color {
        val bg = YtContrast.over(background.toArgb(), 0xFF000000.toInt())
        return Color(YtContrast.readable(look.accent, bg, 3.0))
    }

    /** A label in the design's case. */
    fun label(text: String): String = if (upper) text.uppercase() else text

    private fun family(font: YtShortsFont): FontFamily = when (font) {
        YtShortsFont.SYSTEM -> FontFamily.Default
        YtShortsFont.GEIST -> YtFonts.geist
        YtShortsFont.GEIST_MONO -> YtFonts.geistMono
        YtShortsFont.FIGTREE -> YtFonts.figtree
        YtShortsFont.ARCHIVO -> YtFonts.archivoNarrow
        YtShortsFont.NUNITO -> YtFonts.nunito
        YtShortsFont.INTER -> YtFonts.inter
        YtShortsFont.PLEX_MONO -> YtFonts.plexMono
        YtShortsFont.GOOGLE_SANS -> YtFonts.googleSans
        YtShortsFont.JAKARTA -> YtFonts.jakarta
    }
}
