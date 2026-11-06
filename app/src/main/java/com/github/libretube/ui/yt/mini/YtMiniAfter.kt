package com.github.libretube.ui.yt.mini

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPalette

/**
 * YT: the mini player of After, "Filmvorführung": a screening on the bar. The bordeaux strip
 * carries a row of sprocket holes along its top edge that fill tangerine as the video plays; the
 * picture sits in a black gate, cut to cinemascope; the title in Anton caps, the channel
 * letter-spaced.
 * - Minimizing closes a film gate: black bars come in from top and bottom while the frame
 *   shrinks into the 2.39 strip; on landing the picture rattles in the gate, the lamp flickers,
 *   the film threads hole by hole and the title rises.
 * - The long press opens the gate: the frame grows to twice its height in cinemascope, then the
 *   bars retract to 16:9; the title and a ticket (play and pause) stand beside it, a credits line
 *   "NÄCHSTE VORSTELLUNG" slides up under it.
 * - A dragged card shows as a film frame coming in from the right; dropped, it is pulled through
 *   into the gate in three jerks, the label "IM PROGRAMM · NR. N" follows. A refused one jams.
 * - The haptics are a projector's (YtMiniAfterTextures).
 *
 * [Under] draws the body, the sprockets, the text, the preview and the drop (YtMiniAfterUnder);
 * [Over] the gate's bars, the flicker and the glyph flash on the picture (YtMiniAfterOver).
 */
object YtMiniAfter : YtMiniLookDesign(YtDesign.AFTER) {
    override val spec: YtMiniSpec = YtAfterLayout.spec

    // minimizing as before; the preview opens with weight, as a heavy gate, and closes without a
    // bounce
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 420,
        interpolator = MotionScene.Transition.INTERPOLATE_EASE_IN_OUT,
        peekStiffness = 300f,
        peekDamping = 0.86f,
        releaseStiffness = 520f,
        releaseDamping = 0.95f,
    )
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

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtAfterLayout.peek(spec.copy(peekGrowth = peekGrowth), boxWidth, density, hasNext)

    @Composable
    override fun Under(scene: YtMiniScene) {
        val palette = scene.palette ?: return
        val look = remember(palette) { look(palette) }
        AfterUnder(scene, palette, look)
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        AfterOver(scene)
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniAfterTextures.of(moment)

    /**
     * Landing in step with the dock haptic: the picture jumps in the gate with each of the four
     * ticks of the rattle (35 ms apart), then the thud: player and bar dip a little together and
     * settle. Every value starts and ends at rest.
     */
    override fun land(views: YtMiniViews): Animator {
        val density = views.density
        val rattleMs = (YtAfterMath.RATTLE_STEP * YtAfterMath.RATTLE_STEPS).toFloat()
        val total = 460L
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = null
            addUpdateListener { animator ->
                val ms = (animator.animatedValue as Float) * total
                val jump = YtAfterMath.rattle(ms) * density
                val after = (ms - rattleMs) / (total - rattleMs)
                val dip = if (after > 0f) 1.6f * density * wobble(after, 1.2f, 5f) else 0f
                views.all.forEach { it.translationY = dip }
                views.player.translationY = dip + jump
                YtDock.dip = dip
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    views.all.forEach { it.translationY = 0f }
                    YtDock.dip = 0f
                }
            })
            start()
        }
    }

    /** The drop is pulled into the gate (drawn under the picture); the picture takes it with a jolt. */
    override fun accept(views: YtMiniViews): Animator = wobbleViews(views, YtAfterMath.PULL_MS.toLong()) { t ->
        val ms = t * YtAfterMath.PULL_MS
        // the three pulls of the rattle, then the click as the frame reaches the gate
        val pull = if (ms < YtAfterMath.PULLS * YtAfterMath.RATTLE_STEP) YtAfterMath.rattle(ms) * 0.6f else 0f
        views.player.translationY = pull * views.density
        val settle = ((ms - 0.7f * YtAfterMath.PULL_MS) / (0.3f * YtAfterMath.PULL_MS)).coerceIn(0f, 1f)
        views.under.scaleY = 1f + 0.03f * wobble(settle, 1f, 4f)
    }.also { animator ->
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                views.player.translationY = 0f
            }
        })
    }

    /** A jammed film: the strip shakes, held for a frame at a time. */
    override fun reject(views: YtMiniViews): Animator = wobbleViews(views, 380L) { t ->
        val x = 5f * views.density * YtAfterMath.judder(t)
        views.all.forEach { it.translationX = x }
    }
}
