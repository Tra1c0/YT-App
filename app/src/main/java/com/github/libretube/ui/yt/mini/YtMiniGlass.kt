package com.github.libretube.ui.yt.mini

import android.animation.Animator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionScene
import androidx.constraintlayout.widget.ConstraintSet
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtHapticTexture
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.glassTint
import com.github.libretube.ui.yt.ytLightAngle
import com.github.libretube.ui.yt.LocalYtTilt
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import kotlin.math.roundToInt

/**
 * YT: what the docked Glas player and the Glas bar share: they draw one slab of glass. The bar
 * grows upward behind the player (YtLiquidNav) and tints its glass with the video's colour.
 */
object YtGlassSlab {
    /** The colour bleed of the playing video while a Glas player is on screen, else null. */
    var bleed by mutableStateOf<YtMiniBleed?>(null)

    /**
     * True while the Glas bar draws the slab above itself. Without it (no glass bar, or a bar
     * that cannot draw above its own view) the player draws the upper part of the slab itself.
     */
    var navDraws by mutableStateOf(false)

    /** The glass of the bar of Glas (LiquidNav), which the player's fallback slab repeats. */
    val tint = Color(0x61FFFFFF)
    val handle = Color(0x59000000)
}

/**
 * YT: the mini player of Glas, the user's Mint reference: one slab of liquid glass, the floating
 * bar grown upward, with a wide strip of the video in it whose edges dissolve into the glass, a
 * grab handle, round glass buttons for the previous and next video and "Titel · Kanal" over the
 * picture's foot. No play or close button: a double tap toggles play, a swipe down closes. A long
 * press grows the strip 2.3 times; the title turns into a dark glass pill, the next video's row
 * slides up from under the tab row.
 *
 * The bar draws the slab (YtGlassSlab, YtLiquidNav); [Under] draws nothing unless the bar cannot.
 * [Over] draws everything on the picture (GlassOverlay).
 */
object YtMiniGlass : YtMiniLookDesign(YtDesign.GLASS) {
    override val spec: YtMiniSpec = YtMiniSpecs.GLASS
    override val motion: YtMiniMotion = YtMiniMotion(
        duration = 300,
        interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT,
        peekStiffness = 260f,
        peekDamping = 0.82f,
        // back without a bounce: the row slides down first, then the pill turns into the title
        releaseStiffness = 420f,
        releaseDamping = 0.92f,
    )

    // the slab carries the weight: player and bar dip together, nothing of its own
    override val landing: YtMiniLanding get() = YtMiniLanding.NONE
    override val dip: Float get() = 2f
    override val roundsTopOnly: Boolean get() = true
    override val takesDoubleTap: Boolean get() = true

    override fun look(palette: YtPalette): YtMiniLook = YtMiniLook(
        spec = spec,
        frame = YtMiniFrame.NONE,
        fill = Color(0xE6FFFFFF), border = Color(0xCCFFFFFF), shadow = Color(0x405C8C85), glassTint = YtGlassSlab.tint,
        title = TextStyle(color = Color.White, fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 11.sp),
        subtitle = TextStyle(color = Color.White.copy(alpha = 0.8f), fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Normal, fontSize = 12.sp),
        icon = palette.text, mark = YtMiniMark.NONE,
        bar = YtMiniBar.SEAM, barColor = YtGlassOverStyle.petrol, track = Color.Transparent, barWidth = 2f,
    )

    override fun geometry(boxWidth: Float, density: Float): YtMiniGeometry = YtGlassLayout.geometry(boxWidth, density)

    override fun peekGeometry(boxWidth: Float, density: Float, hasNext: Boolean): YtMiniPeekGeometry =
        YtGlassLayout.peek(boxWidth, density, hasNext)

    override fun constrain(set: ConstraintSet, g: YtMiniGeometry, peek: YtMiniPeekGeometry?, state: YtMiniState) {
        fun px(value: Float) = value.roundToInt()
        val m = g.margins
        when (state) {
            YtMiniState.REST -> {
                // the slab between the bar's margins, the strip across it, its foot on the box's
                set.constrainWidth(R.id.main_container, ConstraintSet.MATCH_CONSTRAINT)
                set.setMargin(R.id.main_container, ConstraintSet.START, px(m.boxStart))
                set.setMargin(R.id.main_container, ConstraintSet.END, px(m.boxEnd))
                set.constrainHeight(R.id.main_container, px(m.boxHeight))
                set.setMargin(R.id.main_container, ConstraintSet.BOTTOM, 0)

                set.connect(R.id.player, ConstraintSet.START, R.id.main_container, ConstraintSet.START, px(m.playerStart))
                set.connect(R.id.player, ConstraintSet.END, R.id.main_container, ConstraintSet.END, px(m.closeEnd))
                set.constrainWidth(R.id.player, ConstraintSet.MATCH_CONSTRAINT)
                set.constrainHeight(R.id.player, px(m.playerHeight))
                set.setMargin(R.id.player, ConstraintSet.TOP, 0)
                set.setMargin(R.id.player, ConstraintSet.BOTTOM, 0)
                // grows upward from the bar in the preview
                set.setVerticalBias(R.id.player, 1f)

                // no play and close button (the reference): double tap, the preview's pill and a
                // swipe down; the title view's accessibility actions offer all of them
                set.setVisibility(R.id.play_imageView, android.view.View.GONE)
                set.setVisibility(R.id.close_imageView, android.view.View.GONE)

                // the (transparent) title view lies over the strip's foot, for accessibility
                for (anchor in listOf(ConstraintSet.TOP, ConstraintSet.BOTTOM, ConstraintSet.START, ConstraintSet.END)) {
                    set.clear(R.id.title_textView, anchor)
                }
                set.connect(R.id.title_textView, ConstraintSet.START, R.id.main_container, ConstraintSet.START, px(m.playerStart))
                set.connect(R.id.title_textView, ConstraintSet.END, R.id.main_container, ConstraintSet.END, px(m.closeEnd))
                set.connect(R.id.title_textView, ConstraintSet.BOTTOM, R.id.main_container, ConstraintSet.BOTTOM, 0)
                set.constrainWidth(R.id.title_textView, ConstraintSet.MATCH_CONSTRAINT)
                set.constrainHeight(R.id.title_textView, ConstraintSet.WRAP_CONTENT)
            }

            YtMiniState.PEEK -> {
                val p = peek ?: return
                // the box grows upward (its vertical bias is 1), the strip with it
                set.constrainHeight(R.id.main_container, px(p.boxHeight))
                set.constrainHeight(R.id.player, px(p.player.height))
            }
        }
    }

    @Composable
    override fun Under(scene: YtMiniScene) {
        // the bar draws the slab; only without it the player draws the slab's upper part
        if (!YtGlassSlab.navDraws) FallbackSlab(scene)
    }

    @Composable
    override fun Over(scene: YtMiniScene) {
        GlassOverlay(scene)
    }

    override fun haptic(moment: YtMoment): YtHapticTexture = YtMiniTextures.glass(moment)

    // the droplet merging into the glass is drawn over the picture (YtGlassOver), the bar's lens
    // ripples with it: no View animation
    override fun accept(views: YtMiniViews): Animator? = null

    /**
     * The upper slab when the bar does not draw it: the same glass as the bar of Glas (blur 8,
     * lens 20/22, its tint), from the box's top to the tab row's rim, with the handle.
     */
    @Composable
    private fun FallbackSlab(scene: YtMiniScene) {
        val backdrop = scene.backdrop ?: return
        val tilt = LocalYtTilt.current
        val shape = RoundedCornerShape(topStart = YtGlassLayout.SLAB_CORNER.dp, topEnd = YtGlassLayout.SLAB_CORNER.dp)
        Box(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                // down to the tab row's rim, which the bar draws
                .layout { measurable, constraints ->
                    val rim = (YtGlassLayout.RIM * density).roundToInt()
                    val height = (constraints.maxHeight - rim).coerceAtLeast(0)
                    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(20.dp.toPx(), 22.dp.toPx())
                    },
                    highlight = { Highlight(style = HighlightStyle.Default(angle = ytLightAngle(tilt.value))) },
                    shadow = null,
                    onDrawSurface = {
                        drawRect(glassTint(YtGlassSlab.tint))
                        val w = YtGlassLayout.HANDLE_WIDTH.dp.toPx()
                        val h = YtGlassLayout.HANDLE_HEIGHT.dp.toPx()
                        drawRoundRect(
                            YtGlassSlab.handle,
                            topLeft = Offset((size.width - w) / 2f, YtGlassLayout.HANDLE_TOP.dp.toPx()),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(h / 2f),
                        )
                    },
                ),
        )
    }
}
