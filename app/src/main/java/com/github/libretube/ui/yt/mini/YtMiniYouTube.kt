package com.github.libretube.ui.yt.mini

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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

/**
 * YT: the mini player of YouTube neu, "Rote Naht": calm and dark, Geist, the red accent. The card
 * and the bar are one dark surface; between them runs the red seam like a stitched line, the
 * played part solid and glowing up into the card. Minimizing rounds the card's corners with the
 * progress; once it sits on the bar a needle sews the seam in from the start. A long press grows
 * the picture 2.2 times in 16:9 (no overshoot), title, channel and a pause pill beside it and
 * an "Als Nächstes" row under it, whose red line fills while held. A dragged video makes the
 * seam glow, a drop pops a red "+1" pill out of the seam that widens into the label.
 *
 * Nothing moves the card off the bar (it is one surface with it): the landing, the accept and
 * the reject are drawn ([YoutubeUnder], [YoutubeOver]); only the reject shakes the picture.
 */
object YtMiniYouTube : YtMiniLookDesign(YtDesign.YOUTUBE) {
    override val spec: YtMiniSpec = YtMiniSpecs.YOUTUBE
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 280,
        interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT,
        // a calm ease out: critically damped, the preview never swings past its size
        peekStiffness = 300f,
        peekDamping = 1f,
        releaseStiffness = 520f,
        releaseDamping = 1f,
    )
    override val landing: YtMiniLanding get() = YtMiniLanding.NONE
    override val peekGrowth: Float get() = YtMiniYoutubeLayout.GROWTH

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = YtMiniYoutubeStyle.card, border = YtMiniYoutubeStyle.border,
        title = TextStyle(color = YtMiniYoutubeStyle.title, fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
        subtitle = TextStyle(color = YtMiniYoutubeStyle.secondary, fontFamily = YtFonts.geist, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
        icon = YtMiniYoutubeStyle.title, playIcon = Color.White,
        bar = YtMiniBar.SEAM, barColor = palette.accent, track = Color(0x26FFFFFF), barWidth = YtMiniYoutubeLayout.SEAM,
    )

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtMiniYoutubeLayout.peek(spec, boxWidth, density, hasNext)

    @Composable
    override fun Under(scene: YtMiniScene) {
        YoutubeUnder(scene, this)
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        YoutubeOver(scene, spec.thumbRadius.toFloat())
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniYoutubeTextures.of(moment)

    // the needle sews the seam in (YoutubeUnder): no View motion that would lift the card off
    // the bar
    override fun land(views: YtMiniViews): Animator? = null

    // the "+1" pill comes out of the seam and the seam pulses (YoutubeUnder)
    override fun accept(views: YtMiniViews): Animator? = null

    /**
     * A short shake of the picture and its buttons only: the card stays on the bar, the seam
     * dulls for a moment (YoutubeUnder).
     */
    override fun reject(views: YtMiniViews): Animator? {
        val moved = listOf(views.player, views.play, views.close)
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = null
            addUpdateListener { animator ->
                val x = 5f * views.density * wobble(animator.animatedValue as Float, 3f, 5f)
                moved.forEach { it.translationX = x }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    moved.forEach { it.translationX = 0f }
                }
            })
            start()
        }
    }
}
