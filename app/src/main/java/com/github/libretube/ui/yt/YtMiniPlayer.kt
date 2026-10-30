package com.github.libretube.ui.yt

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.MotionScene
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.animation.doOnEnd
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.AspectRatioFrameLayout
import com.github.libretube.R
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.liquid.YtViewBackdrop
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * YT: what the navigation bar knows of the mini player docked on it. The bar squares its top
 * corners while a player sits on it (for the designs where both form one surface) and dips
 * together with the player when it lands.
 */
object YtDock {
    /** True while a mini player sits on the navigation bar. */
    var docked by mutableStateOf(false)

    /** How far bar and player dip together as the player lands, in px. */
    var dip by mutableFloatStateOf(0f)
}

/**
 * YT: the mini player as a part of the design's navigation bar. Minimized, the player docks on
 * the bar instead of floating above it, in one of three ways:
 * - STACK: as wide as the bar and set on it; the bar squares its top corners, so player and bar
 *   are one surface with a seam (YouTube, Movo, After, Editorial, Glas, Frost).
 * - BULGE: a narrower body that grows out of the bar with concave curves (Side, Formen, Kiesel).
 * - TUCK: a blob pressed onto the blobs of the bar, overlapping them (Clay, Pop).
 *
 * Each design has its own layout inside: crop and shape of the picture (wide, cinemascope,
 * square, pill), type, the mark behind the play button and how the progress shows (the seam,
 * an underline, a wave, a ring). The video fills the picture, cut to its shape instead of
 * showing black bars. Minimizing runs in the motion character of the design and the landing
 * moves player and bar together.
 */
object YtMiniPlayer {
    private enum class Joint { STACK, BULGE, TUCK }

    /** The shape behind the play button. */
    private enum class Mark { NONE, DISC, BLOB, COOKIE }

    /** How the progress of the video shows. */
    private enum class Bar { SEAM, UNDERLINE, WAVE, RING }

    /** What frames the picture: a black rule, a sticker shadow, a scalloped shape, a glint. */
    private enum class Frame { NONE, RULE, STICKER, COOKIE, GLINT }

    private enum class Landing { NONE, SETTLE, CURTAIN, RISE, SQUISH, RULE, SWELL, JELLY, MORPH, FROST, DROP }

    private class Look(
        val joint: Joint,
        /** The edges of the navigation bar's surface, from the screen edges, in dp. */
        val start: Int,
        val end: Int = start,
        /** Frost: the bar is as wide as its tabs and centered. */
        val width: Int? = null,
        /** From the top of the navigation view to the top of its surface. */
        val navTop: Int = 6,
        /** How far the player reaches down into the bar. */
        val overlap: Int = 1,
        /** The player's own height above the bar. */
        val height: Int,
        val radius: Int,
        /** BULGE: the corner radius of the bar and the curves from the bar up into the body. */
        val navRadius: Int = 0,
        val fillet: Int = 0,
        val thumbWidth: Int,
        val thumbHeight: Int,
        val thumbStart: Int,
        val thumbRadius: Int,
        val frame: Frame = Frame.NONE,
        val frameColor: Color = Color.Unspecified,
        val fill: Color,
        val fillTop: Color? = null,
        val border: Color? = null,
        val borderWidth: Float = 1f,
        val shadow: Color? = null,
        /** The tint of the glass for the glass designs; null for a solid player. */
        val glassTint: Color? = null,
        val title: TextStyle,
        val subtitle: TextStyle,
        val caps: Boolean = false,
        /** Editorial: a kicker ("Jetzt") and the channel above the title. */
        @StringRes val kicker: Int? = null,
        val icon: Color,
        val playIcon: Color = icon,
        val mark: Mark = Mark.NONE,
        val markColor: Color = Color.Unspecified,
        val markSize: Int = 40,
        val bar: Bar,
        val barColor: Color,
        val track: Color,
        val barWidth: Float = 2f,
        val trackWidth: Float = barWidth,
        val duration: Int,
        val interpolator: Int,
        val landing: Landing,
        /** How far player and bar dip together when the player lands, in dp. */
        val dip: Float = 0f,
    ) {
        /** BULGE: the inset of the body from the bar's edges. */
        val body: Int get() = if (joint == Joint.BULGE) navRadius + fillet else 0
    }

    private const val EASE_OUT = MotionScene.Transition.INTERPOLATE_EASE_OUT
    private const val EASE_IN_OUT = MotionScene.Transition.INTERPOLATE_EASE_IN_OUT
    private const val OVERSHOOT = MotionScene.Transition.INTERPOLATE_OVERSHOOT

    /** The play and close buttons: their size, the gap between them and the inset of close. */
    private const val BUTTON = 40
    private const val BUTTON_GAP = 2
    private const val CLOSE_END = 6
    private const val TEXT_GAP = 12

    private fun joint(design: YtDesign): Joint? = when (design) {
        YtDesign.YOUTUBE, YtDesign.MOVO, YtDesign.AFTER, YtDesign.EDITORIAL, YtDesign.GLASS, YtDesign.FROST -> Joint.STACK
        YtDesign.SIDE, YtDesign.SHAPES, YtDesign.KIESEL -> Joint.BULGE
        YtDesign.CLAY, YtDesign.POP -> Joint.TUCK
        YtDesign.STANDARD -> null
    }

    /** Whether player and bar of [design] form one surface, so the bar squares its top corners. */
    fun stacks(design: YtDesign): Boolean = joint(design) == Joint.STACK

    private fun look(design: YtDesign, palette: YtPalette): Look? {
        val joint = joint(design) ?: return null
        return when (design) {
            YtDesign.YOUTUBE -> Look(
                joint = joint, start = 12, height = 64, radius = 24,
                thumbWidth = 89, thumbHeight = 50, thumbStart = 7, thumbRadius = 10,
                fill = Color(0xFF17171D), border = Color(0xFF2A2A33),
                title = TextStyle(color = Color(0xFFE6E6EC), fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                subtitle = TextStyle(color = Color(0xFF9A9AA6), fontFamily = YtFonts.geist, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
                icon = Color(0xFFE6E6EC), playIcon = Color.White,
                bar = Bar.SEAM, barColor = palette.accent, track = Color(0x26FFFFFF), barWidth = 2.5f,
                duration = 280, interpolator = EASE_OUT, landing = Landing.NONE,
            )
            YtDesign.MOVO -> Look(
                joint = joint, start = 12, height = 68, radius = 26,
                thumbWidth = 96, thumbHeight = 54, thumbStart = 7, thumbRadius = 14,
                fill = Color(0xFF17171A), border = Color(0xFF2B2B31),
                title = TextStyle(color = Color.White, fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                subtitle = TextStyle(color = Color(0xFF8E8E97), fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
                icon = Color(0xFFB9B9C2), playIcon = palette.ink, mark = Mark.DISC, markColor = palette.lime,
                bar = Bar.UNDERLINE, barColor = palette.accent, track = Color(0x24FFFFFF), barWidth = 3f,
                duration = 360, interpolator = OVERSHOOT, landing = Landing.SETTLE, dip = 4f,
            )
            YtDesign.AFTER -> Look(
                joint = joint, start = 12, height = 58, radius = 22,
                // cinemascope: the picture is cut to 2.39:1
                thumbWidth = 100, thumbHeight = 42, thumbStart = 8, thumbRadius = 3,
                fill = Color(0xF2240E15), border = palette.text.copy(alpha = 0.08f),
                title = TextStyle(color = palette.text, fontFamily = YtFonts.anton, fontSize = 15.sp, letterSpacing = 0.03.em),
                subtitle = TextStyle(color = Color(0xFFB9A79E), fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.14.em),
                caps = true,
                icon = palette.text,
                bar = Bar.SEAM, barColor = palette.accent, track = palette.text.copy(alpha = 0.1f), barWidth = 1.5f,
                duration = 420, interpolator = EASE_IN_OUT, landing = Landing.CURTAIN,
            )
            YtDesign.SIDE -> Look(
                joint = joint, start = 12, navRadius = 26, fillet = 12, height = 60, radius = 22,
                thumbWidth = 80, thumbHeight = 45, thumbStart = 8, thumbRadius = 12,
                fill = palette.surface, border = Color(0xFF3F4043),
                title = TextStyle(color = palette.text, fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                subtitle = TextStyle(color = palette.textSecondary, fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
                icon = palette.textSecondary, playIcon = palette.ink, mark = Mark.DISC, markColor = palette.accent, markSize = 34,
                bar = Bar.RING, barColor = palette.accent, track = palette.text.copy(alpha = 0.12f), barWidth = 2f,
                duration = 320, interpolator = EASE_OUT, landing = Landing.RISE,
            )
            YtDesign.CLAY -> Look(
                joint = joint, start = 22, overlap = 12, height = 66, radius = 30,
                thumbWidth = 90, thumbHeight = 51, thumbStart = 8, thumbRadius = 20,
                fill = Color(0xFFF3E1D2), fillTop = Color(0xFFFBEDE2), border = Color(0x99FFFFFF), borderWidth = 1.5f, shadow = Color(0x66B07A66),
                title = TextStyle(color = Color(0xFF5C5260), fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp),
                subtitle = TextStyle(color = Color(0xFF8C7880), fontFamily = YtFonts.nunito, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
                icon = Color(0xFF7A5560), playIcon = Color.White, mark = Mark.BLOB, markColor = Color(0xFFA8696F), markSize = 42,
                bar = Bar.UNDERLINE, barColor = Color(0xFFA8696F), track = Color(0x1F7A5560), barWidth = 4f,
                duration = 360, interpolator = OVERSHOOT, landing = Landing.SQUISH, dip = 3f,
            )
            YtDesign.EDITORIAL -> Look(
                joint = joint, start = 14, height = 60, radius = 0,
                // a square cut in a black frame, as the pictures of a newspaper column
                thumbWidth = 44, thumbHeight = 44, thumbStart = 10, thumbRadius = 0, frame = Frame.RULE, frameColor = palette.text,
                fill = palette.background, border = palette.text,
                title = TextStyle(color = palette.text, fontFamily = YtFonts.inter, fontWeight = FontWeight.Bold, fontSize = 13.5.sp),
                subtitle = TextStyle(color = palette.accent, fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 9.5.sp, letterSpacing = 0.12.em),
                kicker = R.string.yt_mini_now,
                icon = palette.text,
                // the rule between player and bar; the blue mark of the bar runs along it
                bar = Bar.SEAM, barColor = palette.accent, track = palette.text, barWidth = 3f, trackWidth = 1f,
                duration = 160, interpolator = EASE_OUT, landing = Landing.RULE,
            )
            YtDesign.GLASS -> Look(
                joint = joint, start = 13, end = 11, height = 62, radius = 29,
                thumbWidth = 88, thumbHeight = 50, thumbStart = 7, thumbRadius = 16, frame = Frame.GLINT, frameColor = Color(0xB3FFFFFF),
                fill = Color(0xE6FFFFFF), border = Color(0xCCFFFFFF), shadow = Color(0x405C8C85), glassTint = Color(0x8CFFFFFF),
                title = TextStyle(color = palette.text, fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                subtitle = TextStyle(color = palette.text.copy(alpha = 0.62f), fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
                icon = palette.text, mark = Mark.DISC, markColor = Color(0xF2FFFFFF),
                bar = Bar.SEAM, barColor = Color(0xFF0A8F7F), track = Color(0x40FFFFFF), barWidth = 2f,
                duration = 300, interpolator = EASE_OUT, landing = Landing.SWELL, dip = 2f,
            )
            YtDesign.POP -> Look(
                joint = joint, start = 18, navTop = 12, overlap = 4, height = 60, radius = 30,
                thumbWidth = 84, thumbHeight = 48, thumbStart = 6, thumbRadius = 24, frame = Frame.STICKER, frameColor = palette.pink,
                fill = Color(0xFFD9D1F6),
                title = TextStyle(color = Color(0xFF3B2054), fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp),
                subtitle = TextStyle(color = Color(0xA63B2054), fontFamily = YtFonts.nunito, fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
                icon = Color(0xFF3B2054), playIcon = Color.White, mark = Mark.BLOB, markColor = palette.accent, markSize = 44,
                bar = Bar.UNDERLINE, barColor = palette.pink, track = Color(0x263B2054), barWidth = 4f,
                duration = 340, interpolator = OVERSHOOT, landing = Landing.JELLY, dip = 4f,
            )
            YtDesign.SHAPES -> Look(
                joint = joint, start = 14, navRadius = 32, fillet = 12, height = 62, radius = 28,
                thumbWidth = 84, thumbHeight = 48, thumbStart = 8, thumbRadius = 24, frame = Frame.COOKIE, frameColor = palette.lime,
                fill = Color(0xFF1E1D44), border = Color(0x26FFFFFF),
                title = TextStyle(color = Color.White, fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                subtitle = TextStyle(color = Color(0xA6FFFFFF), fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Normal, fontSize = 11.5.sp),
                icon = Color.White, playIcon = palette.ink, mark = Mark.COOKIE, markColor = palette.pink, markSize = 42,
                bar = Bar.WAVE, barColor = palette.lime, track = Color(0x26FFFFFF), barWidth = 2.5f,
                duration = 320, interpolator = OVERSHOOT, landing = Landing.MORPH, dip = 2f,
            )
            YtDesign.FROST -> Look(
                joint = joint, start = 0, width = 12 + 118 + 54 * (YtNav.items(design).size - 1), height = 60, radius = 30,
                thumbWidth = 76, thumbHeight = 43, thumbStart = 8, thumbRadius = 14, frame = Frame.GLINT, frameColor = Color(0x4DFFFFFF),
                fill = Color(0xF21F2A26), shadow = Color(0x59000000), glassTint = Color(0x9E1F2A26),
                title = TextStyle(color = Color.White, fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                subtitle = TextStyle(color = Color(0xA6FFFFFF), fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                icon = Color.White, playIcon = palette.ink, mark = Mark.DISC, markColor = Color.White, markSize = 36,
                bar = Bar.SEAM, barColor = palette.accent, track = Color(0x1FFFFFFF), barWidth = 2f,
                duration = 340, interpolator = EASE_OUT, landing = Landing.FROST,
            )
            YtDesign.KIESEL -> Look(
                joint = joint, start = 16, navRadius = 26, fillet = 14, height = 62, radius = 26,
                // a pebble: the picture is cut to a stadium
                thumbWidth = 78, thumbHeight = 44, thumbStart = 9, thumbRadius = 22,
                fill = Color(0xFF0E0E0E), shadow = Color(0x59000000),
                title = TextStyle(color = Color(0xFFEDE8E3), fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.5.sp),
                subtitle = TextStyle(color = Color(0x8CEDE8E3), fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                icon = Color(0xFFEDE8E3), playIcon = Color(0xFF0E0E0E), mark = Mark.DISC, markColor = palette.accent, markSize = 34,
                bar = Bar.RING, barColor = Color(0xFFEDE8E3), track = Color(0x24EDE8E3), barWidth = 2f,
                duration = 360, interpolator = OVERSHOOT, landing = Landing.DROP, dip = 4f,
            )
            YtDesign.STANDARD -> null
        }
    }

    /** The duration of minimizing in the selected design; the classic 250 ms otherwise. */
    fun minimizeDuration(): Int {
        val design = YtDesign.current()
        val palette = design.palette ?: return 250
        return look(design, palette)?.duration ?: 250
    }

    /**
     * How much lower than the top of the navigation view the minimized player ends, in px: down
     * to the bar's surface and as far into it as the design's joint reaches.
     */
    fun dockOffsetPx(context: Context): Int {
        val design = YtDesign.current()
        val palette = design.palette ?: return 0
        val look = look(design, palette) ?: return 0
        return ((look.navTop + look.overlap) * barDensity(context)).roundToInt()
    }

    /**
     * px per dp of the navigation bar: the design screens scale with the screen width
     * ([ytScale]), so the player measures in the same units as the bar it joins.
     */
    private fun barDensity(context: Context): Float =
        context.resources.displayMetrics.density * ytScale(context.resources.configuration)

    /** Gives the mini player of [binding] its place on the design's navigation bar. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun attach(fragment: Fragment, binding: FragmentPlayerBinding) {
        val design = YtDesign.current()
        val palette = design.palette ?: return
        val look = look(design, palette) ?: return
        val context = binding.root.context
        val density = barDensity(context)
        fun dp(value: Int) = (value * density).roundToInt()
        val motion = binding.playerMotionLayout

        // the end state: on the bar, as wide as its surface (or the body), the picture inside
        val end = motion.getConstraintSet(R.id.end) ?: return
        if (look.width != null) {
            end.constrainWidth(R.id.main_container, dp(look.width))
            end.setMargin(R.id.main_container, ConstraintSet.START, 0)
            end.setMargin(R.id.main_container, ConstraintSet.END, 0)
        } else {
            end.constrainWidth(R.id.main_container, ConstraintSet.MATCH_CONSTRAINT)
            end.setMargin(R.id.main_container, ConstraintSet.START, dp(look.start))
            end.setMargin(R.id.main_container, ConstraintSet.END, dp(look.end))
        }
        end.constrainHeight(R.id.main_container, dp(look.height + look.overlap))
        end.setMargin(R.id.main_container, ConstraintSet.BOTTOM, 0)
        end.constrainWidth(R.id.player, dp(look.thumbWidth))
        end.constrainHeight(R.id.player, dp(look.thumbHeight))
        end.setMargin(R.id.player, ConstraintSet.START, dp(look.body + look.thumbStart))
        end.setMargin(R.id.player, ConstraintSet.TOP, 0)
        end.setMargin(R.id.player, ConstraintSet.BOTTOM, dp(look.overlap))
        for (button in listOf(R.id.close_imageView, R.id.play_imageView)) {
            end.constrainWidth(button, dp(BUTTON))
            end.constrainHeight(button, dp(BUTTON))
        }
        end.setMargin(R.id.close_imageView, ConstraintSet.END, dp(look.body + CLOSE_END))
        end.setMargin(R.id.close_imageView, ConstraintSet.TOP, 0)
        end.setMargin(R.id.close_imageView, ConstraintSet.BOTTOM, dp(look.overlap))
        end.setMargin(R.id.play_imageView, ConstraintSet.END, dp(BUTTON_GAP))
        motion.updateState(R.id.end, end)
        motion.rebuildScene()

        // minimizing in the motion character of the design
        motion.getTransition(R.id.yt_transition)?.let { transition ->
            transition.duration = look.duration
            transition.setInterpolatorInfo(look.interpolator, null, 0)
        }

        // the player's surface, title and marks: a composable behind the picture and the buttons
        binding.mainContainer.background = null
        binding.mainContainer.clipChildren = false
        binding.mainContainer.clipToPadding = false
        // the joint reaches into the bar and dips with it: nothing above may cut it off
        (binding.mainContainer.parent as? ViewGroup)?.clipChildren = false
        (binding.root.parent as? ViewGroup)?.clipChildren = false
        val glass = look.glassTint != null
        val backdrop = if (glass) {
            fragment.activity?.findViewById<View>(R.id.fragment)?.let { YtViewBackdrop(it, palette.background) }
        } else {
            null
        }
        val landings = mutableIntStateOf(0)
        val info = {
            val player = binding.player.player
            val duration = player?.duration ?: 0L
            MiniInfo(
                title = binding.titleTextView.text?.toString().orEmpty(),
                channel = binding.playerChannelName.text?.toString().orEmpty(),
                fraction = if (player != null && duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                playing = player?.isPlaying == true,
            )
        }
        val surface = ComposeView(context).apply {
            alpha = 0f
            setContent {
                YtTheme(palette) { MiniSurface(look, backdrop, info, landings.intValue) }
            }
        }
        backdrop?.let { surface.viewTreeObserver.addOnPreDrawListener(it.preDrawListener) }
        binding.mainContainer.addView(
            surface,
            0,
            ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            },
        )

        // the composable draws title and channel in the type of the design; the view stays for
        // accessibility and for the layout of the buttons
        binding.titleTextView.setTextColor(android.graphics.Color.TRANSPARENT)
        binding.playImageView.imageTintList = ColorStateList.valueOf(look.playIcon.toArgb())
        binding.closeImageView.imageTintList = ColorStateList.valueOf(look.icon.toArgb())
        binding.closeImageView.setPadding(dp(10), dp(10), dp(10), dp(10))

        // the picture rounds its corners as the player shrinks (where the video surface allows it)
        val thumbRadius = dp(look.thumbRadius).toFloat()
        if (thumbRadius > 0f) {
            binding.player.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, thumbRadius * motion.progress)
                }
            }
            binding.player.clipToOutline = true
        }

        // the picture fills its shape (cropped) while minimized and fits again when it grows
        var resizeBefore: Int? = null
        fun crop(mini: Boolean) {
            val view = binding.player
            if (mini && resizeBefore == null) {
                resizeBefore = view.resizeMode
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else if (!mini) {
                resizeBefore?.let { view.resizeMode = it }
                resizeBefore = null
            }
        }

        val miniViews = listOf(binding.mainContainer, binding.player, binding.titleTextView, binding.playImageView, binding.closeImageView)
        var landing: ValueAnimator? = null
        motion.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(motionLayout: MotionLayout?, startId: Int, endId: Int, progress: Float) {
                surface.alpha = progress
                binding.player.invalidateOutline()
                YtDock.docked = progress > 0.02f
                if (progress < 0.98f) crop(false)
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                val mini = currentId == R.id.end
                surface.alpha = if (mini) 1f else 0f
                binding.player.invalidateOutline()
                YtDock.docked = mini
                crop(mini)
                if (mini && YtMotionPrefs.enabled) {
                    landing?.cancel()
                    landing = land(look, surface, miniViews, density)
                    landings.intValue++
                }
            }
        })

        // closed: the bar rounds its corners again
        fragment.viewLifecycleOwner.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    landing?.cancel()
                    YtDock.docked = false
                    YtDock.dip = 0f
                }
            },
        )
    }

    /** Called as the mini player is swiped away: the bar no longer carries it. */
    fun undock() {
        YtDock.docked = false
        YtDock.dip = 0f
    }

    /**
     * The gesture with which the player lands on the bar, in the character of the design: the
     * player alone (it falls, squashes, rises, frosts over) and together with the bar (both dip
     * a little, as if the bar takes the weight).
     */
    private fun land(look: Look, surface: View, views: List<View>, density: Float): ValueAnimator {
        val duration = when (look.landing) {
            Landing.DROP -> 620L
            Landing.FROST -> 600L
            Landing.JELLY -> 560L
            else -> 420L
        }
        surface.pivotX = surface.width / 2f
        surface.pivotY = surface.height.toFloat()
        return ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = null
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                // the shared dip: down, back and a little past, settling
                val dipT = if (look.landing == Landing.DROP) ((t - 0.28f) / 0.72f).coerceAtLeast(0f) else t
                val shared = if (dipT > 0f) look.dip * density * 2.2f * wobble(dipT, 1.4f, 4.5f) else 0f
                var own = 0f
                var scaleX = 1f
                var scaleY = 1f
                var alpha = 1f
                when (look.landing) {
                    Landing.DROP -> own = -12f * density * (1f - bounce(t))
                    Landing.SQUISH -> {
                        val w = wobble(t, 1.2f, 5f)
                        scaleY = 1f - 0.16f * w
                        scaleX = 1f + 0.05f * w
                    }
                    Landing.JELLY -> {
                        val w = wobble(t, 2.2f, 4f)
                        scaleX = 1f + 0.07f * w
                        scaleY = 1f - 0.07f * w
                    }
                    Landing.RISE -> scaleY = 1f - 0.2f * exp(-6f * t) * kotlin.math.cos(2f * PI.toFloat() * 1.1f * t)
                    Landing.SWELL -> {
                        val w = exp(-5f * t)
                        scaleY = 1f + 0.05f * w
                    }
                    Landing.FROST -> alpha = 0.4f + 0.6f * t
                    else -> Unit
                }
                views.forEach { it.translationY = own + shared }
                surface.scaleX = scaleX
                surface.scaleY = scaleY
                surface.alpha = alpha
                YtDock.dip = shared
            }
            doOnEnd {
                views.forEach { it.translationY = 0f }
                surface.scaleX = 1f
                surface.scaleY = 1f
                surface.alpha = 1f
                YtDock.dip = 0f
            }
            start()
        }
    }

    /** A damped swing from 0: first towards 1, then back past 0, settling at 0. */
    private fun wobble(t: Float, frequency: Float, decay: Float): Float =
        exp(-decay * t) * sin(2f * PI.toFloat() * frequency * t)

    /** A drop that bounces twice before it rests: 0 to 1. */
    private fun bounce(t: Float): Float {
        val n = 7.5625f
        val d = 2.75f
        return when {
            t < 1f / d -> n * t * t
            t < 2f / d -> { val x = t - 1.5f / d; n * x * x + 0.75f }
            t < 2.5f / d -> { val x = t - 2.25f / d; n * x * x + 0.9375f }
            else -> { val x = t - 2.625f / d; n * x * x + 0.984375f }
        }
    }

    private data class MiniInfo(val title: String, val channel: String, val fraction: Float, val playing: Boolean)

    /** The outline of the player's surface in px: joined to the bar at its foot. */
    private fun bodyPath(look: Look, width: Float, height: Float, density: Float, open: Boolean): Path {
        val content = look.height * density
        val r = look.radius * density
        val path = Path()
        when (look.joint) {
            Joint.STACK -> {
                val inset = if (open) look.borderWidth * density / 2f else 0f
                path.moveTo(inset, height)
                path.lineTo(inset, r)
                if (r > 0f) path.arcTo(Rect(inset, inset, inset + 2 * r, inset + 2 * r), 180f, 90f, false) else path.lineTo(inset, inset)
                path.lineTo(width - inset - r, inset)
                if (r > 0f) path.arcTo(Rect(width - inset - 2 * r, inset, width - inset, inset + 2 * r), 270f, 90f, false) else path.lineTo(width - inset, inset)
                path.lineTo(width - inset, height)
                if (!open) path.close()
            }
            Joint.BULGE -> {
                val b = look.body * density
                val f = look.fillet * density
                val inset = if (open) look.borderWidth * density / 2f else 0f
                // from the bar's surface, curving up into the body
                val foot = if (open) content + inset else content
                path.moveTo(b - f, foot)
                path.arcTo(Rect(b - 2 * f, content - 2 * f, b, content), 90f, -90f, false)
                path.lineTo(b + inset, r)
                path.arcTo(Rect(b + inset, inset, b + inset + 2 * r, inset + 2 * r), 180f, 90f, false)
                path.lineTo(width - b - inset - r, inset)
                path.arcTo(Rect(width - b - inset - 2 * r, inset, width - b - inset, inset + 2 * r), 270f, 90f, false)
                path.lineTo(width - b - inset, content - f)
                path.arcTo(Rect(width - b, content - 2 * f, width - b + 2 * f, content), 180f, -90f, false)
                if (!open) {
                    path.lineTo(width - b + f, height)
                    path.lineTo(b - f, height)
                    path.close()
                }
            }
            Joint.TUCK -> {
                val inset = if (open) look.borderWidth * density / 2f else 0f
                path.addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        Rect(inset, inset, width - inset, height - inset),
                        CornerRadius(r),
                    ),
                )
            }
        }
        return path
    }

    @Composable
    private fun MiniSurface(look: Look, backdrop: Backdrop?, info: () -> MiniInfo, landings: Int) {
        var data by remember { mutableStateOf(MiniInfo("", "", 0f, false)) }
        LaunchedEffect(Unit) {
            while (true) {
                data = info()
                delay(if (YtDock.docked) 350L else 1500L)
            }
        }
        // the part of the landing drawn here: the rule draws in, titles come up, shapes turn in
        val settle = remember { Animatable(1f) }
        val motionOn = YtMotionPrefs.enabled
        LaunchedEffect(landings) {
            if (landings > 0 && motionOn) {
                settle.snapTo(0f)
                settle.animateTo(1f, tween(if (look.landing == Landing.CURTAIN) 620 else 420, easing = LinearOutSlowInEasing))
            }
        }
        val spin = if (motionOn && data.playing && (look.frame == Frame.COOKIE || look.mark == Mark.COOKIE)) rememberSpin(14000) else null
        val breath = if (motionOn && data.playing && look.mark == Mark.BLOB) {
            rememberInfiniteTransition(label = "miniBreath").animateFloat(
                0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "miniBreathValue",
            )
        } else {
            null
        }
        val density = LocalDensity.current.density

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat()
            val content = look.height * density
            val bodyStart = look.body * density
            val bodyEnd = w - look.body * density
            val thumb = Rect(
                offset = Offset(bodyStart + look.thumbStart * density, (content - look.thumbHeight * density) / 2f),
                size = Size(look.thumbWidth * density, look.thumbHeight * density),
            )
            val closeX = bodyEnd - (CLOSE_END + BUTTON / 2f) * density
            val playCenter = Offset(closeX - (BUTTON + BUTTON_GAP) * density, content / 2f)
            val textStart = thumb.right + TEXT_GAP * density
            val textEnd = playCenter.x - (BUTTON / 2f + 4f) * density

            // 1: the surface, joined to the bar
            val glass = look.glassTint != null && backdrop != null
            if (glass) {
                val shape = if (look.joint == Joint.STACK) {
                    RoundedCornerShape(topStart = look.radius.dp, topEnd = look.radius.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
                } else {
                    RoundedCornerShape(look.radius.dp)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .liquidGlass(backdrop!!, shape, look.glassTint!!, look.shadow, blur = 14.dp, refraction = 10.dp),
                )
            } else {
                val shape = remember(look) {
                    GenericShape { size, _ -> addPath(bodyPath(look, size.width, size.height, density, open = false)) }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (look.shadow != null) {
                                Modifier.shadow(10.dp, shape, clip = false, ambientColor = look.shadow, spotColor = look.shadow)
                            } else {
                                Modifier
                            },
                        )
                        .drawBehind {
                            val body = bodyPath(look, size.width, size.height, density, open = false)
                            drawPath(body, Brush.verticalGradient(listOf(look.fillTop ?: look.fill, look.fill), endY = content))
                            look.border?.let { border ->
                                drawPath(
                                    bodyPath(look, size.width, size.height, density, open = true),
                                    border,
                                    style = Stroke(look.borderWidth * density),
                                )
                            }
                        },
                )
            }

            // 2: the frame of the picture, the seam or ring of progress
            Canvas(Modifier.fillMaxSize()) {
                when (look.frame) {
                    Frame.RULE -> drawRect(
                        look.frameColor,
                        topLeft = thumb.topLeft - Offset(density, density),
                        size = Size(thumb.width + 2 * density, thumb.height + 2 * density),
                        style = Stroke(density),
                    )
                    Frame.STICKER -> drawRoundRect(
                        look.frameColor,
                        topLeft = thumb.topLeft + Offset(3 * density, 3 * density),
                        size = thumb.size,
                        cornerRadius = CornerRadius(look.thumbRadius * density),
                    )
                    Frame.GLINT -> drawRoundRect(
                        look.frameColor,
                        topLeft = thumb.topLeft - Offset(1.5f * density, 1.5f * density),
                        size = Size(thumb.width + 3 * density, thumb.height + 3 * density),
                        cornerRadius = CornerRadius((look.thumbRadius + 1.5f) * density),
                        style = Stroke(density),
                    )
                    else -> Unit
                }
                when (look.bar) {
                    Bar.SEAM -> {
                        val left = if (look.joint == Joint.STACK) (look.border?.let { look.borderWidth * density } ?: 0f) else bodyStart
                        val right = if (look.joint == Joint.STACK) w - left else bodyEnd
                        val reveal = if (look.landing == Landing.RULE || look.landing == Landing.CURTAIN) settle.value else 1f
                        val track = look.trackWidth * density
                        drawRect(look.track, Offset(left, content - track), Size((right - left) * reveal, track))
                        val bar = look.barWidth * density
                        drawRect(look.barColor, Offset(left, content - bar), Size((right - left) * data.fraction * reveal, bar))
                    }
                    Bar.RING -> {
                        val radius = (look.markSize / 2f + 4f) * density
                        val stroke = Stroke(look.barWidth * density, cap = StrokeCap.Round)
                        val topLeft = playCenter - Offset(radius, radius)
                        drawArc(look.track, 0f, 360f, false, topLeft, Size(2 * radius, 2 * radius), style = stroke)
                        drawArc(look.barColor, -90f, 360f * data.fraction, false, topLeft, Size(2 * radius, 2 * radius), style = stroke)
                    }
                    else -> Unit
                }
            }

            // 3: Formen: a scalloped shape behind the picture, turning while the video plays
            if (look.frame == Frame.COOKIE) {
                val side = (look.thumbHeight + 14) * density
                Box(
                    Modifier
                        .offset { IntOffset((thumb.center.x - side / 2f).roundToInt(), (thumb.center.y - side / 2f).roundToInt()) }
                        .size((look.thumbHeight + 14).dp)
                        .graphicsLayer {
                            rotationZ = (spin?.value ?: 0f) - 120f * (1f - settle.value)
                            scaleX = 0.4f + 0.6f * settle.value
                            scaleY = 0.4f + 0.6f * settle.value
                        }
                        .clip(CookieShape(9, 0.08f))
                        .background(look.frameColor),
                )
            }

            // 4: the mark behind the play button
            if (look.mark != Mark.NONE) {
                val shape = when (look.mark) {
                    Mark.BLOB -> BlobShape(0.55f, 0.45f, 0.5f, 0.6f)
                    Mark.COOKIE -> CookieShape(6, 0.1f)
                    else -> CircleShape
                }
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (playCenter.x - look.markSize * density / 2f).roundToInt(),
                                (playCenter.y - look.markSize * density / 2f).roundToInt(),
                            )
                        }
                        .size(look.markSize.dp)
                        .graphicsLayer {
                            val b = breath?.value ?: 0f
                            scaleX = 1f + 0.05f * b
                            scaleY = 1f - 0.03f * b
                            if (look.mark == Mark.COOKIE) rotationZ = (spin?.value ?: 0f) * 1.5f - 90f * (1f - settle.value)
                        }
                        .clip(shape)
                        .background(look.markColor),
                )
            }

            // 5: title and channel in the type of the design
            Box(
                Modifier
                    .offset { IntOffset(textStart.roundToInt(), 0) }
                    .width(((textEnd - textStart).coerceAtLeast(0f) / density).dp)
                    .height(look.height.dp)
                    .graphicsLayer {
                        if (look.landing == Landing.CURTAIN) {
                            alpha = settle.value
                            translationY = 6f * density * (1f - settle.value)
                        }
                    },
            ) {
                Column(Modifier.align(Alignment.CenterStart)) {
                    val channel = if (look.caps || look.kicker != null) data.channel.uppercase() else data.channel
                    if (look.kicker != null) {
                        val kicker = stringResource(look.kicker).uppercase()
                        Text(
                            text = if (channel.isBlank()) kicker else "$kicker — $channel",
                            style = look.subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        text = if (look.caps) data.title.uppercase() else data.title,
                        style = look.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (look.kicker == null && channel.isNotBlank()) {
                        Spacer(Modifier.height(1.dp))
                        Text(text = channel, style = look.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when (look.bar) {
                        Bar.UNDERLINE -> {
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(look.barWidth.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(look.track),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(data.fraction)
                                        .clip(RoundedCornerShape(50))
                                        .background(look.barColor),
                                )
                            }
                        }
                        Bar.WAVE -> {
                            Spacer(Modifier.height(4.dp))
                            Wave(look, data.fraction, data.playing && motionOn)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    /** Formen: the played part as a wave that flows while the video plays, the rest a line. */
    @Composable
    private fun Wave(look: Look, fraction: Float, flowing: Boolean) {
        val phase = if (flowing) {
            rememberInfiniteTransition(label = "miniWave").animateFloat(
                0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "miniWavePhase",
            ).value
        } else {
            0f
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(8.dp),
        ) {
            val mid = size.height / 2f
            val stroke = look.barWidth * density
            val split = size.width * fraction
            drawLine(look.track, Offset(split, mid), Offset(size.width, mid), stroke, StrokeCap.Round)
            if (split > 0f) {
                val wave = Path()
                val length = 12f * density
                val amplitude = 2.5f * density
                var x = 0f
                wave.moveTo(0f, mid)
                while (x <= split) {
                    wave.lineTo(x, mid + amplitude * sin(2f * PI.toFloat() * (x / length - phase)))
                    x += density
                }
                drawPath(wave, look.barColor, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}
