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
 * YT: the mini player of Side: a speech bubble that bulges out of the bar, the peach disc with
 * its ring of progress.
 * Wave 2 gives it its own preview, drop look, motion and haptics (override Under, Over, constrain,
 * peekGeometry, land, accept, reject, haptic).
 */
object YtMiniSide : YtMiniLookDesign(YtDesign.SIDE) {
    override val spec: YtMiniSpec = YtMiniSpecs.SIDE
    override val motion: YtMiniMotion = YtMiniMotion(duration = 320, interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT)
    override val landing: YtMiniLanding get() = YtMiniLanding.RISE

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = palette.surface, border = Color(0xFF3F4043),
        title = TextStyle(color = palette.text, fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
        subtitle = TextStyle(color = palette.textSecondary, fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
        icon = palette.textSecondary, playIcon = palette.ink, mark = YtMiniMark.DISC, markColor = palette.accent, markSize = 34,
        bar = YtMiniBar.RING, barColor = palette.accent, track = palette.text.copy(alpha = 0.12f), barWidth = 2f,
    )
}
