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
import com.github.libretube.ui.yt.YtNav
import com.github.libretube.ui.yt.YtPalette

/**
 * YT: the mini player of Frost, "Eisfenster": the narrow dark frost bar grows into a pane of the
 * same dark frosted glass above it, as wide as the bar; the video is a 16:9 window in it whose
 * edges are frosted over (a veil of blurred picture, white and grain, with crystals along the
 * rim). Title and channel in Jakarta on the frost, the white disc behind play, the orange seam of
 * progress where pane and bar meet.
 *
 * Minimizing freezes the window from its edges inward, and it settles once docked. A long press
 * frosts the window over and thaws it from the finger outward while the pane grows 1.8 times;
 * the next video stands beside it as a frozen frost card that thaws under the finger. A card
 * dragged near fogs the window, and the fog clears in a circle where it lands while the card
 * freezes into the pane. It feels like ice: crackles when it freezes, a slow rise when it thaws.
 *
 * [Under] draws the pane and everything beside the picture (YtMiniFrostUnder), [Over] the frost
 * on the picture (YtMiniFrostOver); the numbers are in YtFrostLayout.
 */
object YtMiniFrost : YtMiniLookDesign(YtDesign.FROST) {
    // as wide as the bar, which is as wide as its tabs
    override val spec: YtMiniSpec get() = YtFrostLayout.spec(YtNav.items(YtDesign.FROST).size)

    // soft, as the design moves: a calm freeze into the dock, a slow spring for the thaw
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 360,
        interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT,
        peekStiffness = 300f,
        peekDamping = 0.8f,
        releaseStiffness = 460f,
        releaseDamping = 0.92f,
    )

    // the landing is its own (land below), the frost of it is drawn over the picture
    override val landing: YtMiniLanding get() = YtMiniLanding.NONE

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        frame = YtMiniFrame.NONE,
        fill = YtFrostStyle.paneSolid, shadow = YtFrostStyle.shadow, glassTint = YtFrostStyle.pane,
        title = TextStyle(color = Color.White, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
        subtitle = TextStyle(color = YtFrostStyle.secondary, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp),
        icon = Color.White, playIcon = palette.ink, mark = YtMiniMark.DISC, markColor = Color.White, markSize = 36,
        bar = YtMiniBar.SEAM, barColor = palette.accent, track = YtFrostStyle.track, barWidth = 2f,
    )

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtFrostLayout.peek(boxWidth, density, hasNext)

    @Composable
    override fun Under(scene: YtMiniScene) {
        // the palette's orange (it is always there for Frost; the fallback is the same colour)
        val accent = scene.palette?.accent ?: Color(0xFFF08A3C)
        FrostUnder(scene, accent)
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        FrostOverlay(scene)
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtFrostTextures.of(moment)

    /** Docked: the ice settles, the pane gives a little at its foot and is still. */
    override fun land(views: YtMiniViews): Animator? = wobbleViews(views, 560L) { t ->
        val w = wobble(t, 1.6f, 5.5f)
        views.under.scaleY = 1f + 0.02f * w
        views.under.scaleX = 1f - 0.006f * w
    }

    /** A card freezes into the pane: it takes the weight and settles (the frost is drawn). */
    override fun accept(views: YtMiniViews): Animator? = wobbleViews(views, 520L) { t ->
        val w = wobble(t, 1.3f, 5f)
        views.under.scaleY = 1f + 0.035f * w
        views.under.scaleX = 1f + 0.008f * w
    }
}
