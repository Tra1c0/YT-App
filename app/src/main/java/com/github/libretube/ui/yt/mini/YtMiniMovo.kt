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

/**
 * YT: the mini player of Movo, "Kachel": Movo's home is a mosaic of coloured tiles, so its player
 * is one. On the dark body joined to the bar the picture sits as a tile in a 3 dp cobalt frame,
 * on a pink tile turned a little behind it; a lime disc lies behind play, the progress is the
 * cobalt underline. The tiles tilt under the finger as Movo's cards do.
 *
 * Minimizing overshoots into the tile; on the bar, player and bar sag together while the frame
 * snaps in, the pink tile swings into place and the disc pops (two clicks). The long press
 * (×1.9) splits the tile into a mosaic: the pink tile flips up as "Als Nächstes", a cobalt tile
 * slides out from under the picture as play and pause, the disc becomes the queue's count.
 * A dragged video lights the mosaic lime; taken, it falls in as a small tile, the tiles shuffle
 * once and the label shows on a lime tile.
 *
 * [Under] (YtMiniMovoUnder) draws the body and the tiles, [Over] (YtMiniMovoOver) the frame and
 * the light on the picture; the numbers are in YtMovoLayout.
 */
object YtMiniMovo : YtMiniLookDesign(YtDesign.MOVO) {
    override val spec: YtMiniSpec = YtMiniSpecs.MOVO
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 360,
        interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT,
        // bouncy: the mosaic swings past its size and back, as Movo's springs do
        peekStiffness = 380f,
        peekDamping = 0.55f,
        releaseStiffness = 560f,
        releaseDamping = 0.72f,
    )
    override val landing: YtMiniLanding get() = YtMiniLanding.SETTLE
    override val dip: Float get() = 4f
    override val peekGrowth: Float get() = YtMovoLayout.GROWTH
    override val followsFinger: Boolean get() = true

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        fill = Color(0xFF17171A), border = Color(0xFF2B2B31),
        title = TextStyle(color = Color.White, fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 14.sp),
        subtitle = TextStyle(color = Color(0xFF8E8E97), fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
        icon = Color(0xFFB9B9C2), playIcon = palette.ink, mark = YtMiniMark.DISC, markColor = palette.lime,
        bar = YtMiniBar.UNDERLINE, barColor = palette.accent, track = Color(0x24FFFFFF), barWidth = 3f,
    )

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtMovoLayout.peek(spec, boxWidth, density, hasNext).frame

    @Composable
    override fun Under(scene: YtMiniScene) {
        MovoUnder(this, scene)
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        MovoOver(scene)
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniMovoTextures.of(moment)

    /**
     * A taken video drops in with weight: player and bar sag together once and bounce back
     * (the tiles' shuffle and the label are drawn by [Under]).
     */
    override fun accept(views: YtMiniViews): Animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 520L
        interpolator = null
        addUpdateListener {
            val t = it.animatedValue as Float
            val y = 3f * views.density * wobble(t, 1.6f, 4.5f)
            views.all.forEach { view -> view.translationY = y }
            YtDock.dip = y
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                views.all.forEach { it.translationY = 0f }
                YtDock.dip = 0f
            }
        })
        start()
    }

    /** Knocked back: a quick, springy shake (three swings) side to side. */
    override fun reject(views: YtMiniViews): Animator = wobbleViews(views, 420L) { t ->
        val x = 7f * views.density * wobble(t, 3.4f, 5.5f)
        views.all.forEach { it.translationX = x }
    }
}
