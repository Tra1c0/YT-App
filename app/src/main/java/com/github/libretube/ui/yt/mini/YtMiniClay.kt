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
 * YT: the mini player of Clay: a dough ball pressed onto the blobs of the bar, the mauve blob
 * behind play breathing.
 * Wave 2 gives it its own preview, drop look, motion and haptics (override Under, Over, constrain,
 * peekGeometry, land, accept, reject, haptic).
 */
object YtMiniClay : YtMiniLookDesign(YtDesign.CLAY) {
    override val spec: YtMiniSpec = YtMiniSpecs.CLAY
    override val motion: YtMiniMotion = YtMiniMotion(duration = 360, interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT)
    override val landing: YtMiniLanding get() = YtMiniLanding.SQUISH
    override val dip: Float get() = 3f

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = Color(0xFFF3E1D2), fillTop = Color(0xFFFBEDE2), border = Color(0x99FFFFFF), borderWidth = 1.5f, shadow = Color(0x66B07A66),
        title = TextStyle(color = Color(0xFF5C5260), fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp),
        subtitle = TextStyle(color = Color(0xFF8C7880), fontFamily = YtFonts.nunito, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
        icon = Color(0xFF7A5560), playIcon = Color.White, mark = YtMiniMark.BLOB, markColor = Color(0xFFA8696F), markSize = 42,
        bar = YtMiniBar.UNDERLINE, barColor = Color(0xFFA8696F), track = Color(0x1F7A5560), barWidth = 4f,
    )
}
