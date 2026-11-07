package com.github.libretube.ui.yt.mini

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import androidx.constraintlayout.widget.ConstraintSet
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.KieselPanel
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPalette
import kotlin.math.roundToInt

/**
 * YT: the mini player of Kiesel, "Schwarzer Kiesel": physical, black, cream, lavender and peach,
 * Jakarta bold. A black pebble bulges out of the black bar, its top domed a little like a
 * stone, the picture cut to a stadium, the lavender disc with its cream ring of progress.
 * Minimizing, a free stone falls and its foot flows into the bar's curves just before it lands;
 * it lands with a thud and bounces three times, squashing on the bar, which gives with it (the
 * haptic ticks at the same moments). A long press squeezes the pebble, then springs it up into
 * a tall pebble: the picture a wide stadium across it, title and channel under it, the disc at
 * the byline's end, and a peach "Als Nächstes" capsule rolling in from the right with the next
 * video's thumbnail turning as a wheel. A dragged video lifts the stone a little and a cream
 * capsule peeks out of its corner; a drop pops it with "+1", the pebble hops once under the
 * weight and the capsule opens into the label.
 */
object YtMiniKiesel : YtMiniLookDesign(YtDesign.KIESEL) {
    override val spec: YtMiniSpec = YtMiniSpecs.KIESEL
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 360,
        interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT,
        // springy: up past its size (the stretch of the tall pebble) and back; down onto the bar
        // with barely a swing, so it does not sink below its rest
        peekStiffness = 300f,
        peekDamping = 0.55f,
        releaseStiffness = 480f,
        releaseDamping = 0.82f,
    )
    override val landing: YtMiniLanding get() = YtMiniLanding.DROP
    override val dip: Float get() = 4f
    override val peekGrowth: Float get() = YtMiniKieselLayout.GROWTH

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = KieselPanel, shadow = Color(0x59000000),
        title = TextStyle(color = Color(0xFFEDE8E3), fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.5.sp),
        subtitle = TextStyle(color = Color(0x8CEDE8E3), fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp),
        icon = Color(0xFFEDE8E3), playIcon = Color(0xFF0E0E0E), mark = YtMiniMark.DISC, markColor = palette.accent,
        markSize = YtMiniKieselLayout.DISC,
        bar = YtMiniBar.RING, barColor = Color(0xFFEDE8E3), track = Color(0x24EDE8E3), barWidth = 2f,
    )

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtMiniKieselLayout.peek(spec, boxWidth, density, hasNext).geometry

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
        // close fades where it is, at the foot, instead of drifting up with the growing pebble
        set.clear(R.id.close_imageView, ConstraintSet.TOP)
        set.setMargin(R.id.close_imageView, ConstraintSet.BOTTOM, px(g.margins.buttonBottom + (g.content - button) / 2f))
    }

    @Composable
    override fun Under(scene: YtMiniScene) {
        KieselUnder(scene, this)
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        KieselOver(scene)
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniKieselTextures.of(moment)

    /** The landing: an impact, then three hops, each lower; the bar gives at every impact. */
    override fun land(views: YtMiniViews): Animator = bounce(views, YtMiniKieselLayout.DOCK, dip)

    /** A drop: the counter lands on the stone, which hops once under the weight. */
    override fun accept(views: YtMiniViews): Animator = bounce(views, YtMiniKieselLayout.ACCEPT, dip * 0.6f)

    /**
     * Not taken: the stone rocks side to side on the bar, heavy and short, and settles; it
     * sags a little at each turn.
     */
    override fun reject(views: YtMiniViews): Animator? = wobbleViews(views, 420L) { t ->
        val w = wobble(t, 2.6f, 5.5f)
        val x = 5f * views.density * w
        views.all.forEach { it.translationX = x }
        views.under.scaleY = 1f - 0.03f * kotlin.math.abs(w)
    }

    /**
     * Plays [run] on the views: the body (the composable under the picture) squashes and
     * stretches from its foot, the picture and the buttons rise and fall with the part of the
     * body they sit on, and bar and box dip together by up to [dipDp] at each impact. Everything
     * is back at rest when it ends or is cancelled.
     */
    private fun bounce(views: YtMiniViews, run: YtKieselBounce, dipDp: Float): Animator {
        val surface = views.under
        val box = views.box
        val riders: List<View> = listOf(views.player, views.title, views.play, views.close)
        surface.pivotX = surface.width / 2f
        surface.pivotY = surface.height.toFloat()
        val dipPx = dipDp * views.density
        return ValueAnimator.ofFloat(0f, run.duration).apply {
            duration = run.duration.toLong()
            interpolator = null
            addUpdateListener { animator ->
                val ms = animator.animatedValue as Float
                val v = run.value(ms)
                val shared = dipPx * run.dip(ms)
                surface.scaleY = 1f + v
                // the volume stays: taller is thinner, squashed is wider
                surface.scaleX = 1f - 0.45f * v
                box.translationY = shared
                val foot = box.bottom.toFloat()
                for (view in riders) {
                    // how high the view's middle sits above the foot, which the stretch scales
                    val above = (foot - (view.top + view.height / 2f)).coerceAtLeast(0f)
                    view.translationY = shared - v * above
                }
                YtDock.dip = shared
            }
            addListener(object : AnimatorListenerAdapter() {
                // also after a cancel (the player opens again while it bounces)
                override fun onAnimationEnd(animation: Animator) {
                    surface.scaleX = 1f
                    surface.scaleY = 1f
                    views.all.forEach { it.translationY = 0f }
                    YtDock.dip = 0f
                }
            })
            start()
        }
    }
}
