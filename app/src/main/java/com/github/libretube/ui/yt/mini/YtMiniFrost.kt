package com.github.libretube.ui.yt.mini

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtNav
import com.github.libretube.ui.yt.YtPalette

/**
 * YT: the mini player of Frost: a pane of dark frosted glass as narrow as the bar, the white disc
 * behind play, the orange seam.
 * Wave 2 gives it its own preview, drop look, motion and haptics (override Under, Over, constrain,
 * peekGeometry, land, accept, reject, haptic).
 */
object YtMiniFrost : YtMiniLookDesign(YtDesign.FROST) {
    // as wide as the bar, which is as wide as its tabs
    override val spec: YtMiniSpec get() = YtMiniSpecs.frost(YtNav.items(YtDesign.FROST).size)
    override val motion: YtMiniMotion = YtMiniMotion(duration = 340, interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT)
    override val landing: YtMiniLanding get() = YtMiniLanding.FROST

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        frame = YtMiniFrame.GLINT, frameColor = Color(0x4DFFFFFF),
        fill = Color(0xF21F2A26), shadow = Color(0x59000000), glassTint = Color(0x9E1F2A26),
        title = TextStyle(color = Color.White, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
        subtitle = TextStyle(color = Color(0xA6FFFFFF), fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp),
        icon = Color.White, playIcon = palette.ink, mark = YtMiniMark.DISC, markColor = Color.White, markSize = 36,
        bar = YtMiniBar.SEAM, barColor = palette.accent, track = Color(0x1FFFFFFF), barWidth = 2f,
    )
}
