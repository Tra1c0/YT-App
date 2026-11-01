package com.github.libretube.ui.yt.mini

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtPalette

/**
 * YT: the mini player of Formen: night blue, a lime cookie turning behind the picture, the pink
 * cookie behind play, the lime wave of progress.
 * Wave 2 gives it its own preview, drop look, motion and haptics (override Under, Over, constrain,
 * peekGeometry, land, accept, reject, haptic).
 */
object YtMiniShapes : YtMiniLookDesign(YtDesign.SHAPES) {
    override val spec: YtMiniSpec = YtMiniSpecs.SHAPES
    override val motion: YtMiniMotion = YtMiniMotion(duration = 320, interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT)
    override val landing: YtMiniLanding get() = YtMiniLanding.MORPH
    override val dip: Float get() = 2f

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        frame = YtMiniFrame.COOKIE, frameColor = palette.lime,
        fill = Color(0xFF1E1D44), border = Color(0x26FFFFFF),
        title = TextStyle(color = Color.White, fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
        subtitle = TextStyle(color = Color(0xA6FFFFFF), fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
        icon = Color.White, playIcon = palette.ink, mark = YtMiniMark.COOKIE, markColor = palette.pink, markSize = 42,
        bar = YtMiniBar.WAVE, barColor = palette.lime, track = Color(0x26FFFFFF), barWidth = 2.5f,
    )
}
