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
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPalette
import kotlin.math.abs

/**
 * YT: the mini player of Clay, "Knetkugel": a puffy ball of cream dough pressed onto the blobs of
 * the bar (12 dp deep), lit from above with a soft sheen and a warm shadow, the picture pressed
 * into it like a dent, title and channel in Nunito, the progress as a thick mauve strand of dough
 * and the mauve blob behind play breathing while the video plays.
 *
 * Minimizing overshoots; on the bar the ball squashes under its own weight and wobbles like jelly
 * while the bar dips with it (a deep thud). A long press kneads it (low ticks) and stretches the
 * dough upward (×1.7): a head around the grown picture rises out of the body on a neck that
 * thins as it is pulled and fills out again, swinging past its size like jelly; the next video
 * squeezes out beside it as a small ball of dough, the play blob stretches into a pill. A dragged
 * video presses a dent into the top of the dough; taken, the dent closes over it and ripples
 * outward, and the label pops up as a mauve blob.
 *
 * [Under] (YtMiniClayUnder) draws the dough, [Over] (YtMiniClayOver) the dent's shading on the
 * picture and the glyph flash; the numbers are in YtMiniClayLayout.
 */
object YtMiniClay : YtMiniLookDesign(YtDesign.CLAY) {
    override val spec: YtMiniSpec = YtMiniSpecs.CLAY
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 360,
        interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT,
        // jelly: the dough swings past its size and back; let go, it sinks back with a wobble
        peekStiffness = 300f,
        peekDamping = 0.5f,
        releaseStiffness = 480f,
        releaseDamping = 0.62f,
    )
    override val landing: YtMiniLanding get() = YtMiniLanding.SQUISH
    override val dip: Float get() = 3f
    override val peekGrowth: Float get() = YtMiniClayLayout.GROWTH

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = ClayStyle.CREAM, fillTop = ClayStyle.CREAM_TOP, border = Color(0x99FFFFFF), borderWidth = 1.5f, shadow = Color(0x66B07A66),
        title = TextStyle(color = ClayStyle.INK, fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp),
        subtitle = TextStyle(color = ClayStyle.MUTED, fontFamily = YtFonts.nunito, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
        icon = Color(0xFF7A5560), playIcon = Color.White, mark = YtMiniMark.BLOB, markColor = ClayStyle.MAUVE, markSize = YtMiniClayLayout.BLOB,
        bar = YtMiniBar.UNDERLINE, barColor = ClayStyle.MAUVE, track = Color(0x1F7A5560), barWidth = 4f,
    )

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtMiniClayLayout.peek(spec, boxWidth, density, hasNext).geometry

    @Composable
    override fun Under(scene: YtMiniScene) {
        ClayUnder(scene, this)
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        ClayOver(scene)
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniClayTextures.of(moment)

    /**
     * The ball lands with weight: it squashes from its foot and wobbles back like jelly, the
     * picture pressed into it squashes a little with it, and player and bar dip together.
     */
    override fun land(views: YtMiniViews): Animator {
        val density = views.density
        val surface = views.under
        val player = views.player
        surface.pivotX = surface.width / 2f
        surface.pivotY = surface.height.toFloat()
        // the picture squashes about the same point as the dough around it (the box's foot)
        val pivotX = player.pivotX
        val pivotY = player.pivotY
        player.pivotX = (views.box.left + views.box.width / 2f) - player.left
        player.pivotY = views.box.bottom.toFloat() - player.top
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560L
            interpolator = null
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                val w = YtMiniClayLayout.squash(t)
                surface.scaleY = 1f - 0.15f * w
                surface.scaleX = 1f + 0.06f * w
                player.scaleY = 1f - 0.07f * w
                player.scaleX = 1f + 0.03f * w
                val shared = dip * density * 2.2f * wobble(t, 1.4f, 4.5f)
                views.all.forEach { it.translationY = shared }
                YtDock.dip = shared
            }
            addListener(object : AnimatorListenerAdapter() {
                // also after a cancel (the player opens again while landing)
                override fun onAnimationEnd(animation: Animator) {
                    views.all.forEach { it.translationY = 0f }
                    surface.scaleX = 1f
                    surface.scaleY = 1f
                    player.scaleX = 1f
                    player.scaleY = 1f
                    player.pivotX = pivotX
                    player.pivotY = pivotY
                    YtDock.dip = 0f
                }
            })
            start()
        }
    }

    /** A dropped video sinks into the dough: the ball gulps it (down and back, a little jelly). */
    override fun accept(views: YtMiniViews): Animator = wobbleViews(views, 620L) { t ->
        val w = wobble(t, 1.5f, 4.5f)
        views.under.scaleY = 1f - 0.06f * w
        views.under.scaleX = 1f + 0.025f * w
    }

    /** Spat out: the dough shakes its head, squeezing narrower on each swing. */
    override fun reject(views: YtMiniViews): Animator = wobbleViews(views, 440L) { t ->
        val w = wobble(t, 2.6f, 5f)
        val x = 6f * views.density * w
        views.all.forEach { it.translationX = x }
        views.under.scaleX = 1f - 0.03f * abs(w)
    }
}
