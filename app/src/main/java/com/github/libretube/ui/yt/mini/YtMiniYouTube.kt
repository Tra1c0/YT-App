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
 * YT: the mini player of YouTube. YouTube neu: a calm dark card stacked on the bar, the red seam of progress as the joint.
 * Wave 2 gives it its own preview, drop look, motion and haptics (override Under, Over, constrain,
 * peekGeometry, land, accept, reject, haptic).
 */
object YtMiniYouTube : YtMiniLookDesign(YtDesign.YOUTUBE) {
    override val spec: YtMiniSpec = YtMiniSpecs.YOUTUBE
    override val motion: YtMiniMotion = YtMiniMotion(duration = 280, interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT)
    override val landing: YtMiniLanding get() = YtMiniLanding.NONE

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = Color(0xFF17171D), border = Color(0xFF2A2A33),
        title = TextStyle(color = Color(0xFFE6E6EC), fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
        subtitle = TextStyle(color = Color(0xFF9A9AA6), fontFamily = YtFonts.geist, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
        icon = Color(0xFFE6E6EC), playIcon = Color.White,
        bar = YtMiniBar.SEAM, barColor = palette.accent, track = Color(0x26FFFFFF), barWidth = 2.5f,
    )
}
