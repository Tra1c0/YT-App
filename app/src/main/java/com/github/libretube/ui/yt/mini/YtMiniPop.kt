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
 * YT: the mini player of Pop: a pill with a pink sticker shadow behind the picture, the plum blob
 * behind play.
 * Wave 2 gives it its own preview, drop look, motion and haptics (override Under, Over, constrain,
 * peekGeometry, land, accept, reject, haptic).
 */
object YtMiniPop : YtMiniLookDesign(YtDesign.POP) {
    override val spec: YtMiniSpec = YtMiniSpecs.POP
    override val motion: YtMiniMotion = YtMiniMotion(duration = 340, interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT)
    override val landing: YtMiniLanding get() = YtMiniLanding.JELLY
    override val dip: Float get() = 4f

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        frame = YtMiniFrame.STICKER, frameColor = palette.pink,
        fill = Color(0xFFD9D1F6),
        title = TextStyle(color = Color(0xFF3B2054), fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp),
        subtitle = TextStyle(color = Color(0xA63B2054), fontFamily = YtFonts.nunito, fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
        icon = Color(0xFF3B2054), playIcon = Color.White, mark = YtMiniMark.BLOB, markColor = palette.accent, markSize = 44,
        bar = YtMiniBar.UNDERLINE, barColor = palette.pink, track = Color(0x263B2054), barWidth = 4f,
    )
}
