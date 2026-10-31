package com.github.libretube.ui.yt.mini

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtPalette

/**
 * YT: the mini player of Editorial: a square cut in a black rule frame, the kicker "JETZT —
 * KANAL" in mono, the rule of progress.
 * Wave 2 gives it its own preview, drop look, motion and haptics (override Under, Over, constrain,
 * peekGeometry, land, accept, reject, haptic).
 */
object YtMiniEditorial : YtMiniLookDesign(YtDesign.EDITORIAL) {
    override val spec: YtMiniSpec = YtMiniSpecs.EDITORIAL
    override val motion: YtMiniMotion = YtMiniMotion(duration = 160, interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT)
    override val landing: YtMiniLanding get() = YtMiniLanding.RULE

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        frame = YtMiniFrame.RULE, frameColor = palette.text,
        fill = palette.background, border = palette.text,
        title = TextStyle(color = palette.text, fontFamily = YtFonts.inter, fontWeight = FontWeight.Bold, fontSize = 13.5.sp),
        subtitle = TextStyle(color = palette.accent, fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 9.5.sp, letterSpacing = 0.12.em),
        kicker = R.string.yt_mini_now,
        icon = palette.text,
        // the rule between player and bar; the blue mark of the bar runs along it
        bar = YtMiniBar.SEAM, barColor = palette.accent, track = palette.text, barWidth = 3f, trackWidth = 1f,
    )
}
