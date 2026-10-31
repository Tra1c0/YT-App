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
 * YT: the mini player of Glas: a pane of liquid glass on the glass bar, the glint around the
 * picture, the petrol seam.
 * Wave 2 gives it its own preview, drop look, motion and haptics (override Under, Over, constrain,
 * peekGeometry, land, accept, reject, haptic).
 */
object YtMiniGlass : YtMiniLookDesign(YtDesign.GLASS) {
    override val spec: YtMiniSpec = YtMiniSpecs.GLASS
    override val motion: YtMiniMotion = YtMiniMotion(duration = 300, interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT, peekStiffness = 260f, peekDamping = 0.82f)
    override val landing: YtMiniLanding get() = YtMiniLanding.SWELL
    override val dip: Float get() = 2f

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        frame = YtMiniFrame.GLINT, frameColor = Color(0xB3FFFFFF),
        fill = Color(0xE6FFFFFF), border = Color(0xCCFFFFFF), shadow = Color(0x405C8C85), glassTint = Color(0x8CFFFFFF),
        title = TextStyle(color = palette.text, fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
        subtitle = TextStyle(color = palette.text.copy(alpha = 0.62f), fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
        icon = palette.text, mark = YtMiniMark.DISC, markColor = Color(0xF2FFFFFF),
        bar = YtMiniBar.SEAM, barColor = Color(0xFF0A8F7F), track = Color(0x40FFFFFF), barWidth = 2f,
    )
}
