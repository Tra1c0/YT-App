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
import androidx.constraintlayout.widget.ConstraintSet
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPalette
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YT: the mini player of Side, "Gesprächsblase": gentle, apricot, Figtree. A speech bubble
 * bulges out of the bar (concave fillets into it), the channel's avatar in an apricot ring on
 * its top corner, the peach disc with its ring of progress. Minimizing, the bubble rises out of
 * the bar and the avatar pops in last with a small overshoot. A long press grows the bubble
 * into a post card: the picture across it, avatar, channel and two lines of title under it, the
 * disc at the byline's end, and the next video as a smaller reply bubble ("Als Nächstes ·
 * Kanal"). A dragged video makes someone type on the bubble; a drop turns it into an apricot
 * "+1" that bursts like the heart of a Side post, the label answering in a bubble. A heartbeat
 * (TICK, CLICK at +120 ms) where something arrives: on docking and on a drop.
 */
object YtMiniSide : YtMiniLookDesign(YtDesign.SIDE) {
    override val spec: YtMiniSpec = YtMiniSpecs.SIDE
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 320,
        interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT,
        // a soft swell with a hint of give, back without a bounce
        peekStiffness = 300f,
        peekDamping = 0.74f,
        releaseStiffness = 460f,
        releaseDamping = 0.9f,
    )
    override val landing: YtMiniLanding get() = YtMiniLanding.RISE
    override val peekGrowth: Float get() = YtMiniSideLayout.GROWTH

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = palette.surface, border = Color(0xFF3F4043),
        title = TextStyle(color = palette.text, fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
        subtitle = TextStyle(color = palette.textSecondary, fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
        icon = palette.textSecondary, playIcon = palette.ink, mark = YtMiniMark.DISC, markColor = palette.accent, markSize = YtMiniSideLayout.DISC,
        bar = YtMiniBar.RING, barColor = palette.accent, track = palette.text.copy(alpha = 0.12f), barWidth = 2f,
    )

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtMiniSideLayout.card(spec, boxWidth, density, hasNext).geometry

    override fun constrain(set: ConstraintSet, g: YtMiniGeometry, peek: YtMiniPeekGeometry?, state: YtMiniState) {
        super.constrain(set, g, peek, state)
        if (state != YtMiniState.PEEK || peek == null) return
        fun px(value: Float) = value.roundToInt()
        val button = g.margins.button
        // the play button rides with the disc to the byline's end and stays: letting go on it
        // plays or pauses
        val play = R.id.play_imageView
        for (anchor in listOf(ConstraintSet.TOP, ConstraintSet.BOTTOM, ConstraintSet.START, ConstraintSet.END)) {
            set.clear(play, anchor)
        }
        set.connect(play, ConstraintSet.TOP, R.id.main_container, ConstraintSet.TOP, px(peek.pill.centerY - button / 2f))
        set.connect(play, ConstraintSet.START, R.id.main_container, ConstraintSet.START, px(peek.pill.centerX - button / 2f))
        set.setAlpha(play, 1f)
        // close fades where it is, at the foot, instead of drifting up with the growing box
        set.clear(R.id.close_imageView, ConstraintSet.TOP)
        set.setMargin(R.id.close_imageView, ConstraintSet.BOTTOM, px(g.margins.buttonBottom + (g.content - button) / 2f))
    }

    @Composable
    override fun Under(scene: YtMiniScene) {
        SideUnder(scene, this)
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        SideOver(scene)
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniSideTextures.of(moment)

    /**
     * The bubble settles with a breath (it stretches up a little and back) and the avatar pops
     * in on its corner after it, with a small overshoot.
     */
    override fun land(views: YtMiniViews): Animator {
        val surface = views.under
        surface.pivotX = surface.width / 2f
        surface.pivotY = surface.height.toFloat()
        // hidden from the first frame on: the avatar must not show before it pops
        YtMiniSidePop.value = 0f
        val total = YtMiniSideLayout.POP_DELAY + YtMiniSideLayout.POP
        return ValueAnimator.ofFloat(0f, total).apply {
            duration = total.toLong()
            interpolator = null
            addUpdateListener { animator ->
                val ms = animator.animatedValue as Float
                surface.scaleY = 1f + 0.08f * wobble(min(1f, ms / 420f), 1.1f, 6f)
                YtMiniSidePop.value = YtMiniSideLayout.avatarPop(ms)
            }
            addListener(object : AnimatorListenerAdapter() {
                // also after a cancel (the player opens again while landing)
                override fun onAnimationEnd(animation: Animator) {
                    surface.scaleY = 1f
                    YtMiniSidePop.value = 1f
                }
            })
            start()
        }
    }

    /** A dropped video is taken: the bubble beats twice, with the heartbeat of the haptic. */
    override fun accept(views: YtMiniViews): Animator? = wobbleViews(views, YtMiniSideLayout.HEARTBEAT) { t ->
        val s = 1f + YtMiniSideLayout.heartbeat(t * YtMiniSideLayout.HEARTBEAT)
        views.under.scaleX = s
        views.under.scaleY = s
    }
}
