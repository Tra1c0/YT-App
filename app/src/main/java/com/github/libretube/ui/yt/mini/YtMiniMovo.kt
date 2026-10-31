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
 * YT: the mini player of Movo: a dark tile on the bar, the lime disc behind play, the cobalt
 * underline of progress.
 * Wave 2 gives it its own preview, drop look, motion and haptics (override Under, Over, constrain,
 * peekGeometry, land, accept, reject, haptic).
 */
object YtMiniMovo : YtMiniLookDesign(YtDesign.MOVO) {
    override val spec: YtMiniSpec = YtMiniSpecs.MOVO
    override val motion: YtMiniMotion = YtMiniMotion(duration = 360, interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT)
    override val landing: YtMiniLanding get() = YtMiniLanding.SETTLE
    override val dip: Float get() = 4f

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = Color(0xFF17171A), border = Color(0xFF2B2B31),
        title = TextStyle(color = Color.White, fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 14.sp),
        subtitle = TextStyle(color = Color(0xFF8E8E97), fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
        icon = Color(0xFFB9B9C2), playIcon = palette.ink, mark = YtMiniMark.DISC, markColor = palette.lime,
        bar = YtMiniBar.UNDERLINE, barColor = palette.accent, track = Color(0x24FFFFFF), barWidth = 3f,
    )
}
