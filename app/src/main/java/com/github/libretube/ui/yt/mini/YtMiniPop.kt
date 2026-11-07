package com.github.libretube.ui.yt.mini

import android.animation.Animator
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.ytDayNight

/**
 * YT: the mini player of Pop, "Sticker": a lavender pill pressed onto the nav band, the picture
 * on it as a sticker with a pink shadow, the plum blob of the nav behind play, title and channel
 * in Nunito ExtraBold, the pink underline of progress.
 *
 * Minimizing overshoots into the pill; on the band it lands as jelly (the shared landing), the
 * sticker shadow slaps on and the blob pops. A long press inflates the pill into a balloon
 * (1.6 times the picture) with a jelly overshoot whose width lags its height; the next video
 * slaps on as a yellow sticker with a white border, turned -6 degrees; the blob stretches into
 * the pill of play and pause. A card dragged near makes the blob open its mouth towards it;
 * dropped, the blob gulps it, swells, and the pill wobbles; a refused card is spat out with a
 * shake.
 *
 * [Under] (YtMiniPopUnder) draws the balloon and everything beside the picture, [Over]
 * (YtMiniPopOver) the glyph flash and the light on the picture; the numbers are in YtPopLayout.
 */
object YtMiniPop : YtMiniLookDesign(YtDesign.POP) {
    override val spec: YtMiniSpec = YtMiniSpecs.POP

    // springy, as Pop moves: minimizing overshoots, the balloon swings past its size and back
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 340,
        interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT,
        peekStiffness = 340f,
        peekDamping = 0.5f,
        releaseStiffness = 520f,
        releaseDamping = 0.6f,
    )
    override val landing: YtMiniLanding get() = YtMiniLanding.JELLY
    override val dip: Float get() = 4f
    override val peekGrowth: Float get() = YtPopLayout.GROWTH

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        frame = YtMiniFrame.STICKER, frameColor = palette.pink,
        fill = PopColors.band,
        title = TextStyle(color = PopColors.onBand, fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp),
        subtitle = TextStyle(color = PopColors.onBand.copy(alpha = 0.65f), fontFamily = YtFonts.nunito, fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
        icon = PopColors.onBand, playIcon = palette.onAccent, mark = YtMiniMark.BLOB, markColor = palette.accent, markSize = YtPopLayout.BLOB,
        bar = YtMiniBar.UNDERLINE, barColor = palette.pink, track = PopColors.onBand.copy(alpha = 0.15f), barWidth = 4f,
    )

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtPopLayout.peek(spec, boxWidth, density, hasNext)

    @Composable
    override fun Under(scene: YtMiniScene) {
        PopUnder(this, scene)
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        PopOver(scene)
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniPopTextures.of(moment)

    /**
     * The card is gulped: once the mouth has snapped shut the pill wobbles as jelly, wide and
     * flat, tall and narrow, settling (the gulp itself is drawn by [Under]).
     */
    override fun accept(views: YtMiniViews): Animator = wobbleViews(views, 680L) { t ->
        val s = ((t - 0.15f) / 0.85f).coerceIn(0f, 1f)
        val w = wobble(s, 2.4f, 4.5f)
        views.under.scaleX = 1f + 0.06f * w
        views.under.scaleY = 1f - 0.07f * w
    }

    /** Spat out: a quick, rubbery shake side to side that squeezes the pill as it swings. */
    override fun reject(views: YtMiniViews): Animator = wobbleViews(views, 440L) { t ->
        val w = wobble(t, 3.2f, 5f)
        val x = 7f * views.density * w
        views.all.forEach { it.translationX = x }
        views.under.scaleY = 1f - 0.04f * kotlin.math.abs(w)
    }
}

/** YT: the colours of the Pop player beside the palette's (as Pop's home and nav draw them). */
internal object PopColors {
    /** The lavender of the nav band, the pill's body (deep plum at night). */
    val band: Color get() = ytDayNight(Color(0xFFD9D1F6), Color(0xFF382548))
    val plum = Color(0xFF3B2054)

    /** The words and icons on the band: plum, light lilac at night. */
    val onBand: Color get() = ytDayNight(plum, Color(0xFFF5ECFF))

    /** The butter yellow of the feature card, the next video's sticker. */
    val yellow = Color(0xFFFDE682)
    val white = Color.White
}
