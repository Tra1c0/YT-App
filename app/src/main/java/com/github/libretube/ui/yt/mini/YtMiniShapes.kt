package com.github.libretube.ui.yt.mini

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
 * YT: the mini player of Formen, "Keksfenster". Formen's home is night blue with bright Material
 * shapes, so the player is one: on the night-blue body that grows out of the bar, the video is
 * seen through a 12-scalloped cookie window (the picture is cut square, the overlay paints the
 * body colour around a cookie-shaped hole, since the SurfaceView can only be clipped to a rounded
 * rectangle). The lime wave of progress runs around the cookie, the pink cookie behind play turns
 * slowly while the video plays, and so does the window.
 *
 * Minimizing overshoots; the cookie turns in out of the rounded picture as it lands and its
 * scallops wobble once. The long press (×1.8) unwinds the window: the scallops smooth out into a
 * round pill and it grows into a rounded 4:3 picture; the next video comes in a small cookie that
 * turns in, play and pause on a pink pill. A dragged video makes the cookie lean towards it and
 * its rim light lime; taken, the cookie turns a quarter in three detents and the label shows on a
 * pink pill; refused, it shrugs and its rim flashes pink.
 *
 * [Under] (YtMiniShapesUnder) draws the body, the pink cookie, the text and the preview's parts,
 * [Over] (YtMiniShapesOver) the window, the wave and the glyph flash; the numbers are in
 * YtShapesLayout and YtShapesWindow.
 */
object YtMiniShapes : YtMiniLookDesign(YtDesign.SHAPES) {
    override val spec: YtMiniSpec = YtShapesLayout.spec(YtMiniSpecs.SHAPES)
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 340,
        interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT,
        // springy as Formen's shapes: the window swings a little past its size and back
        peekStiffness = 300f,
        peekDamping = 0.62f,
        releaseStiffness = 520f,
        releaseDamping = 0.78f,
    )
    override val landing: YtMiniLanding get() = YtMiniLanding.MORPH
    override val dip: Float get() = 2f
    override val peekGrowth: Float get() = YtShapesLayout.GROWTH

    /** The night blue of the body and the bar: the window's frame is painted in it. */
    val body = Color(0xFF1E1D44)

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        frame = YtMiniFrame.COOKIE, frameColor = palette.lime,
        fill = body, border = Color(0x26FFFFFF),
        title = TextStyle(color = Color.White, fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
        subtitle = TextStyle(color = Color(0xA6FFFFFF), fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
        icon = Color.White, playIcon = palette.ink, mark = YtMiniMark.COOKIE, markColor = palette.pink, markSize = YtShapesLayout.MARK,
        bar = YtMiniBar.WAVE, barColor = palette.lime, track = Color(0x26FFFFFF), barWidth = 2.5f,
    )

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtShapesLayout.peek(spec, boxWidth, density, hasNext).frame

    @Composable
    override fun Under(scene: YtMiniScene) {
        ShapesUnder(this, scene)
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        ShapesOver(this, scene)
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniShapesTextures.of(moment)

    /** A taken video: the body gulps it, swelling round once and settling. */
    override fun accept(views: YtMiniViews): android.animation.Animator = wobbleViews(views, 480L) { t ->
        val w = wobble(t, 1.3f, 5f)
        views.under.scaleX = 1f + 0.035f * w
        views.under.scaleY = 1f + 0.07f * w
    }

    /** Refused: a short shake side to side (the cookie shrugs on top of it, in [Over]). */
    override fun reject(views: YtMiniViews): android.animation.Animator = wobbleViews(views, 400L) { t ->
        val x = 6f * views.density * wobble(t, 3f, 5f)
        views.all.forEach { it.translationX = x }
    }
}
