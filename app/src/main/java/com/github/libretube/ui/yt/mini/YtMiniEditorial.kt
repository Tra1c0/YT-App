package com.github.libretube.ui.yt.mini

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPalette

/**
 * YT: the mini player of Editorial, "Druckspalte": a newspaper column on the white slip of the
 * bar. The picture is a square cut in a black rule frame, the kicker "JETZT — KANAL" in mono over
 * the title in Inter bold, the progress the blue mark running on the black rule at the joint.
 * - Minimizing is a straight wipe, no spring: the row is printed in from the start edge behind a
 *   blue print head; on landing the rule draws through and the slip takes one key strike.
 * - The long press unfolds a printed block upward, without springs: a skybox
 *   "02 — NÄCHSTES: …" over a hairline, the picture opening to 16:9 in its frame, the kicker and
 *   the time in mono and the play slip beside it, the headline under it. The rules come first,
 *   then the type prints left to right; the kicker is typed letter by letter with the haptic.
 * - A dragged card sets a blinking blue caret in the row; dropped, the label is typed on a black
 *   slip and its number counts up ("NR. 03"); a refused one jams the key.
 * - The haptics are a typewriter's (YtMiniEditorialTextures).
 *
 * [Under] draws the slip, the frame, the rows, the preview and the drop (YtMiniEditorialUnder);
 * [Over] the stamp of play and pause on the picture (YtMiniEditorialOver).
 */
object YtMiniEditorial : YtMiniLookDesign(YtDesign.EDITORIAL) {
    override val spec: YtMiniSpec = YtEditorialLayout.spec

    // minimizing stays the quick straight move of round 10; the preview opens and closes
    // critically damped (no overshoot, no swing): a block of type does not bounce
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 160,
        interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT,
        peekStiffness = 900f,
        peekDamping = 1f,
        releaseStiffness = 1100f,
        releaseDamping = 1f,
    )
    override val landing: YtMiniLanding get() = YtMiniLanding.RULE

    /**
     * How many strokes the preview's kicker types (its letters, at most eight), written by the
     * composable that shows it, so the haptic of the press ticks once per stroke. Read on the main
     * thread when the press takes.
     */
    @Volatile
    internal var kickerLetters: Int = YtEditorialMath.TYPE_STEPS

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

    /** The printed block of the preview; the constraints and the composables read the same one. */
    internal fun editorialPeek(boxWidth: Float, density: Float, hasNext: Boolean): YtEditorialPeek =
        YtEditorialLayout.peek(spec.copy(peekGrowth = peekGrowth), boxWidth, density, hasNext)

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        editorialPeek(boxWidth, density, hasNext).geometry

    @Composable
    override fun Under(scene: YtMiniScene) {
        val palette = scene.palette ?: return
        val look = remember(palette) { look(palette) }
        EditorialUnder(scene, palette, look)
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        EditorialOver(scene)
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniEditorialTextures.of(moment, kickerLetters)

    /**
     * Landing with the dock's key strike: the slip and the picture go down 1.5 dp at once and come
     * straight back, no swing. The bar stays: it is the platen the key strikes.
     */
    override fun land(views: YtMiniViews): Animator = strike(views, depth = 1.5f, down = 40, back = 90)

    /** The dropped card is set: a full key strike, a little deeper than the landing's. */
    override fun accept(views: YtMiniViews): Animator = strike(views, depth = 2.5f, down = 50, back = 140)

    /** A jammed key: the slip shakes in held steps, as the type bar that does not go through. */
    override fun reject(views: YtMiniViews): Animator {
        val total = YtEditorialMath.jamMs.toLong()
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = null
            addUpdateListener { animator ->
                val x = 4f * views.density * YtEditorialMath.jam((animator.animatedValue as Float) * total)
                views.all.forEach { it.translationX = x }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    views.all.forEach { it.translationX = 0f }
                }
            })
            start()
        }
    }

    private fun strike(views: YtMiniViews, depth: Float, down: Int, back: Int): Animator {
        val total = (down + back).toLong()
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = null
            addUpdateListener { animator ->
                val y = YtEditorialMath.strike((animator.animatedValue as Float) * total, depth, down, back) * views.density
                views.all.forEach { it.translationY = y }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    views.all.forEach { it.translationY = 0f }
                }
            })
            start()
        }
    }
}
