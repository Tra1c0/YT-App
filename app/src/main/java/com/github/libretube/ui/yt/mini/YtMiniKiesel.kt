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
 * YT: the mini player of Kiesel: a black pebble bulging out of the black bar, the picture cut to
 * a stadium, the lavender disc with its cream ring.
 * Wave 2 gives it its own preview, drop look, motion and haptics (override Under, Over, constrain,
 * peekGeometry, land, accept, reject, haptic).
 */
object YtMiniKiesel : YtMiniLookDesign(YtDesign.KIESEL) {
    override val spec: YtMiniSpec = YtMiniSpecs.KIESEL
    override val motion: YtMiniMotion = YtMiniMotion(duration = 360, interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT)
    override val landing: YtMiniLanding get() = YtMiniLanding.DROP
    override val dip: Float get() = 4f

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = Color(0xFF0E0E0E), shadow = Color(0x59000000),
        title = TextStyle(color = Color(0xFFEDE8E3), fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.5.sp),
        subtitle = TextStyle(color = Color(0x8CEDE8E3), fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp),
        icon = Color(0xFFEDE8E3), playIcon = Color(0xFF0E0E0E), mark = YtMiniMark.DISC, markColor = palette.accent, markSize = 34,
        bar = YtMiniBar.RING, barColor = Color(0xFFEDE8E3), track = Color(0x24EDE8E3), barWidth = 2f,
    )
}
