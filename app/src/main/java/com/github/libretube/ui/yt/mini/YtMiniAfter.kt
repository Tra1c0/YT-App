package com.github.libretube.ui.yt.mini

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtPalette

/**
 * YT: the mini player of After: the cinemascope strip, title in Anton caps, the channel letter-
 * spaced.
 * Wave 2 gives it its own preview, drop look, motion and haptics (override Under, Over, constrain,
 * peekGeometry, land, accept, reject, haptic).
 */
object YtMiniAfter : YtMiniLookDesign(YtDesign.AFTER) {
    override val spec: YtMiniSpec = YtMiniSpecs.AFTER
    override val motion: YtMiniMotion = YtMiniMotion(duration = 420, interpolator = MotionScene.Transition.INTERPOLATE_EASE_IN_OUT)
    override val landing: YtMiniLanding get() = YtMiniLanding.CURTAIN

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = Color(0xF2240E15), border = palette.text.copy(alpha = 0.08f),
        title = TextStyle(color = palette.text, fontFamily = YtFonts.anton, fontSize = 15.sp, letterSpacing = 0.03.em),
        subtitle = TextStyle(color = Color(0xFFB9A79E), fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.14.em),
        caps = true,
        icon = palette.text,
        bar = YtMiniBar.SEAM, barColor = palette.accent, track = palette.text.copy(alpha = 0.1f), barWidth = 1.5f,
    )
}
