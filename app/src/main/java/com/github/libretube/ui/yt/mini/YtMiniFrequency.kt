package com.github.libretube.ui.yt.mini

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.FrequencyPulse
import com.github.libretube.ui.yt.FrequencyPulseHost
import com.github.libretube.ui.yt.FrequencyPulseMath
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.cookiePath

/**
 * YT: the mini player of Frequency, "Pulsnaht": YouTube neu's stacked card ("Rote Naht": card and
 * bar are one surface, the seam between them, the needle sews it in when the player lands, the
 * same preview with "Als Nächstes"), drawn in Frequency's night with its coral, and alive with
 * the sound: the glow of the played seam rises and lights with the level, its head swells on
 * hits, a coral rim inside the picture breathes (drawn over the picture, so it follows its live
 * bounds while dragged; the video itself cannot be changed), and a Formen cookie ring around the
 * play button deepens with the bass and turns while it plays. In silence all of it rests.
 */
object YtMiniFrequency : YtMiniLookDesign(YtDesign.FREQUENCY) {
    override val spec: YtMiniSpec = YtMiniSpecs.FREQUENCY
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 300,
        interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT,
        // YouTube neu's calm card with a little of Formen's spring
        peekStiffness = 320f,
        peekDamping = 0.8f,
        releaseStiffness = 520f,
        releaseDamping = 0.85f,
    )
    override val landing: YtMiniLanding get() = YtMiniLanding.NONE
    override val peekGrowth: Float get() = YtMiniYoutubeLayout.GROWTH

    /** The night of the card and the bar (YtNavBar's Frequency bar has the same). */
    val card = Color(0xFF17152B)

    internal val colors = YtMiniYoutubeColors(
        card = card,
        cardTop = Color(0xFF1D1A35),
        border = Color(0xFF2C2850),
        title = Color(0xFFF4F2FF),
        secondary = Color(0xFFB7B2D6),
        pill = Color(0xFF231F3F),
        row = Color(0xFF1D1A35),
        rowActive = Color(0xFF241F45),
        thumbGround = Color(0xFF231F3F),
        dull = Color(0xFF5A5670),
        hot = Color(0xFFFFB3C1),
        onAccent = Color(0xFF170812),
        pulse = true,
    )

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = colors.card, border = colors.border,
        title = TextStyle(color = colors.title, fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
        subtitle = TextStyle(color = colors.secondary, fontFamily = YtFonts.geist, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
        icon = colors.title, playIcon = Color.White,
        bar = YtMiniBar.SEAM, barColor = palette.accent, track = Color(0x26FFFFFF), barWidth = YtMiniYoutubeLayout.SEAM,
    )

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtMiniYoutubeLayout.peek(spec, boxWidth, density, hasNext)

    @Composable
    override fun Under(scene: YtMiniScene) {
        // the mini player holds the pulse too: it is shown over every screen of the design
        FrequencyPulseHost()
        Box(Modifier.fillMaxSize()) {
            YoutubeUnder(scene, this@YtMiniFrequency, colors)
            PlayRing(scene)
        }
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        YoutubeOver(scene, spec.thumbRadius.toFloat(), pulse = true)
    }

    /** The cookie ring around the play button: aqua, deepening with the bass, turning while it plays. */
    @Composable
    private fun PlayRing(scene: YtMiniScene) {
        val palette = scene.palette ?: return
        val density = LocalDensity.current.density
        val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
        val path = remember { Path() }
        val stroke = remember(density) { Stroke(1.6f * density) }
        val ring = palette.lime
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val shown = YtMiniYoutubeMath.restText(YtDock.progress, YtDock.peek)
            if (shown <= 0.01f) return@Canvas
            val g = geometry(size.width, density)
            val play = g.play ?: return@Canvas
            val restTop = size.height - (spec.height + spec.overlap) * density
            val cx = if (mirror) size.width - play.centerX else play.centerX
            val center = Offset(cx, restTop + play.centerY)
            val radius = 17f * density
            val e = FrequencyPulse.energy.floatValue
            cookiePath(
                path, center, radius, 8, FrequencyPulseMath.cookieDepth(0.06f, e),
                Math.toRadians(FrequencyPulse.turn.floatValue.toDouble()).toFloat(),
            )
            drawPath(path, ring.copy(alpha = (0.55f + 0.35f * FrequencyPulse.glow.floatValue) * shown), style = stroke)
        }
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniFrequencyTextures.of(moment)

    // the needle sews the seam in (YoutubeUnder): the card stays on the bar
    override fun land(views: YtMiniViews): Animator? = null

    // the "+1" pill comes out of the seam (YoutubeUnder)
    override fun accept(views: YtMiniViews): Animator? = null

    /** A short shake of the picture and its buttons; the card stays on the bar, the seam dulls. */
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
