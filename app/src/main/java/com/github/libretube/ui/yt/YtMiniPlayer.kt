package com.github.libretube.ui.yt

import android.animation.TimeInterpolator
import android.content.res.ColorStateList
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.annotation.FontRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.MotionScene
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.github.libretube.R
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.liquid.YtViewBackdrop
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

/**
 * YT: the mini player in the form and colors of the design's navigation bar. Minimized, the
 * player becomes a floating bar with the inset of the navigation bar just above it: the same
 * corner radius, fill, border and type; a glass bar for Glas and Frost that sees the screen
 * below. The thumbnail sits inside with rounded corners. Minimizing runs in the motion
 * character of the design (crisp, cinematic, springy) and the bar lands with a small gesture:
 * clay squishes, pop wobbles, a pebble drops and bounces, glass swells, frost forms.
 */
object YtMiniPlayer {
    private enum class Landing { NONE, SQUISH, JELLY, DROP, SETTLE, SWELL, FROST }

    private class Look(
        /** Height, inset from the screen edges and gap above the navigation bar, in dp. */
        val height: Int,
        val inset: Int,
        val gap: Int,
        val radius: Int,
        val thumbWidth: Int,
        val thumbInset: Int,
        val thumbRadius: Int,
        val fill: Color,
        val fillTop: Color? = null,
        val border: Color? = null,
        val shadow: Color? = null,
        /** The tint of the glass for the glass designs; null for a solid bar. */
        val glassTint: Color? = null,
        val rule: Color? = null,
        val title: Color,
        val icon: Color,
        val playIcon: Color = icon,
        @FontRes val font: Int,
        val caps: Boolean = false,
        val titleSize: Float = 13.5f,
        val duration: Int,
        val interpolator: Int,
        val landing: Landing,
    )

    private fun look(design: YtDesign, palette: YtPalette): Look? = when (design) {
        YtDesign.YOUTUBE -> Look(
            height = 60, inset = 12, gap = 6, radius = 22, thumbWidth = 96, thumbInset = 5, thumbRadius = 14,
            fill = Color(0xFF17171D), border = Color(0xFF2A2A33),
            title = Color(0xFFE6E6EC), icon = Color(0xFFE6E6EC), playIcon = Color.White,
            font = R.font.font_geist_500, duration = 280, interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT, landing = Landing.NONE,
        )
        YtDesign.MOVO -> Look(
            height = 64, inset = 12, gap = 6, radius = 26, thumbWidth = 100, thumbInset = 6, thumbRadius = 18,
            fill = Color(0xFF17171A), border = Color(0xFF2B2B31),
            title = Color.White, icon = Color.White, playIcon = palette.lime,
            font = R.font.font_geist_700, duration = 360, interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT, landing = Landing.SETTLE,
        )
        YtDesign.AFTER -> Look(
            height = 56, inset = 12, gap = 6, radius = 22, thumbWidth = 100, thumbInset = 4, thumbRadius = 4,
            fill = Color(0xF2240E15), border = Color(0x14FFFFFF),
            title = palette.text, icon = palette.text,
            font = R.font.font_anton_400, caps = true, titleSize = 14f,
            duration = 420, interpolator = MotionScene.Transition.INTERPOLATE_EASE_IN_OUT, landing = Landing.NONE,
        )
        YtDesign.SIDE -> Look(
            height = 60, inset = 12, gap = 6, radius = 26, thumbWidth = 96, thumbInset = 5, thumbRadius = 16,
            fill = palette.surface, border = Color(0xFF3F4043),
            title = palette.text, icon = palette.textSecondary, playIcon = palette.accent,
            font = R.font.font_figtree_500, duration = 320, interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT, landing = Landing.NONE,
        )
        YtDesign.CLAY -> Look(
            height = 64, inset = 18, gap = 6, radius = 32, thumbWidth = 96, thumbInset = 6, thumbRadius = 22,
            fill = Color(0xFFF3E1D2), fillTop = Color(0xFFFBEDE2), border = Color(0x99FFFFFF), shadow = Color(0x66B07A66),
            title = Color(0xFF5C5260), icon = Color(0xFF7A5560),
            font = R.font.font_nunito_600, duration = 360, interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT, landing = Landing.SQUISH,
        )
        YtDesign.EDITORIAL -> Look(
            height = 56, inset = 0, gap = 0, radius = 0, thumbWidth = 100, thumbInset = 0, thumbRadius = 0,
            fill = palette.background, rule = palette.text,
            title = palette.text, icon = palette.text,
            font = R.font.font_plex_mono_500, caps = true, titleSize = 11.5f,
            duration = 160, interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT, landing = Landing.NONE,
        )
        YtDesign.GLASS -> Look(
            height = 60, inset = 13, gap = 6, radius = 30, thumbWidth = 96, thumbInset = 5, thumbRadius = 20,
            fill = Color(0xE6FFFFFF), border = Color(0xCCFFFFFF), shadow = Color(0x405C8C85), glassTint = Color(0x8CFFFFFF),
            title = palette.text, icon = palette.text,
            font = R.font.font_google_sans_500, duration = 300, interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT, landing = Landing.SWELL,
        )
        YtDesign.POP -> Look(
            height = 60, inset = 10, gap = 8, radius = 30, thumbWidth = 92, thumbInset = 5, thumbRadius = 25,
            fill = Color(0xFFD9D1F6),
            title = Color(0xFF3B2054), icon = Color(0xFF3B2054),
            font = R.font.font_nunito_800, duration = 340, interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT, landing = Landing.JELLY,
        )
        YtDesign.SHAPES -> Look(
            height = 62, inset = 14, gap = 6, radius = 31, thumbWidth = 96, thumbInset = 5, thumbRadius = 20,
            fill = Color(0xFF1E1D44), border = Color(0x26FFFFFF),
            title = Color.White, icon = Color.White, playIcon = palette.lime,
            font = R.font.font_google_sans_500, duration = 320, interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT, landing = Landing.SWELL,
        )
        YtDesign.FROST -> Look(
            height = 60, inset = 14, gap = 6, radius = 30, thumbWidth = 96, thumbInset = 5, thumbRadius = 20,
            fill = Color(0xF21F2A26), border = Color(0x33FFFFFF), glassTint = Color(0x9E1F2A26),
            title = Color.White, icon = Color.White,
            font = R.font.font_jakarta_600, duration = 340, interpolator = MotionScene.Transition.INTERPOLATE_EASE_OUT, landing = Landing.FROST,
        )
        YtDesign.KIESEL -> Look(
            height = 62, inset = 14, gap = 6, radius = 26, thumbWidth = 96, thumbInset = 5, thumbRadius = 18,
            fill = Color(0xFF0E0E0E), shadow = Color(0x59000000),
            title = Color(0xFFEDE8E3), icon = Color(0xFFEDE8E3), playIcon = Color(0xFFB4A7F5),
            font = R.font.font_jakarta_700, duration = 360, interpolator = MotionScene.Transition.INTERPOLATE_OVERSHOOT, landing = Landing.DROP,
        )
        YtDesign.STANDARD -> null
    }

    /** The duration of minimizing in the selected design; the classic 250 ms otherwise. */
    fun minimizeDuration(): Int {
        val design = YtDesign.current()
        val palette = design.palette ?: return 250
        return look(design, palette)?.duration ?: 250
    }

    /** Gives the mini player of [binding] the form of the design's navigation bar. */
    fun attach(fragment: Fragment, binding: FragmentPlayerBinding) {
        val design = YtDesign.current()
        val palette = design.palette ?: return
        val look = look(design, palette) ?: return
        val context = binding.root.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val motion = binding.playerMotionLayout

        // the end state: a floating bar with the inset of the navigation bar, the thumbnail inside
        val end = motion.getConstraintSet(R.id.end) ?: return
        end.constrainWidth(R.id.main_container, ConstraintSet.MATCH_CONSTRAINT)
        end.constrainHeight(R.id.main_container, dp(look.height))
        end.setMargin(R.id.main_container, ConstraintSet.START, dp(look.inset))
        end.setMargin(R.id.main_container, ConstraintSet.END, dp(look.inset))
        end.setMargin(R.id.main_container, ConstraintSet.BOTTOM, dp(look.gap))
        end.constrainWidth(R.id.player, dp(look.thumbWidth))
        end.setMargin(R.id.player, ConstraintSet.START, dp(look.thumbInset))
        end.setMargin(R.id.player, ConstraintSet.TOP, dp(look.thumbInset))
        end.setMargin(R.id.player, ConstraintSet.BOTTOM, dp(look.thumbInset))
        end.setMargin(R.id.close_imageView, ConstraintSet.END, dp(if (look.inset == 0) 16 else 10))
        motion.updateState(R.id.end, end)
        motion.rebuildScene()

        // minimizing in the motion character of the design
        motion.getTransition(R.id.yt_transition)?.let { transition ->
            transition.duration = look.duration
            transition.setInterpolatorInfo(look.interpolator, null, 0)
        }

        // the bar: a composable surface behind the thumbnail, the title and the buttons
        binding.mainContainer.background = null
        binding.mainContainer.clipChildren = false
        binding.mainContainer.clipToPadding = false
        (binding.mainContainer.parent as? ViewGroup)?.clipChildren = false
        val glass = look.glassTint != null
        val backdrop = if (glass) {
            fragment.activity?.findViewById<View>(R.id.fragment)?.let { YtViewBackdrop(it, palette.background) }
        } else null
        val surface = ComposeView(context).apply {
            alpha = 0f
            setContent {
                YtTheme(palette) { MiniSurface(look, backdrop) }
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

        // type and icons of the design
        binding.titleTextView.apply {
            typeface = ResourcesCompat.getFont(context, look.font)
            setTextColor(look.title.toArgb())
            textSize = look.titleSize
            isAllCaps = look.caps
            if (look.caps) letterSpacing = 0.06f
        }
        binding.playImageView.imageTintList = ColorStateList.valueOf(look.playIcon.toArgb())
        binding.closeImageView.imageTintList = ColorStateList.valueOf(look.icon.toArgb())

        // the thumbnail rounds its corners as the player shrinks (where the video surface allows it)
        val thumbRadius = dp(look.thumbRadius).toFloat()
        if (thumbRadius > 0f) {
            binding.player.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, thumbRadius * motion.progress)
                }
            }
            binding.player.clipToOutline = true
        }

        val miniViews = listOf(binding.mainContainer, binding.player, binding.titleTextView, binding.playImageView, binding.closeImageView)
        motion.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(motionLayout: MotionLayout?, startId: Int, endId: Int, progress: Float) {
                surface.alpha = progress
                binding.player.invalidateOutline()
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                val mini = currentId == R.id.end
                surface.alpha = if (mini) 1f else 0f
                binding.player.invalidateOutline()
                if (mini && YtMotionPrefs.enabled) land(look, surface, miniViews, density)
            }
        })
    }

    /** The small gesture with which the bar lands, in the character of the design. */
    private fun land(look: Look, surface: View, views: List<View>, density: Float) {
        when (look.landing) {
            Landing.SQUISH -> {
                surface.scaleX = 1.03f
                surface.scaleY = 0.9f
                surface.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2f)).start()
                drop(views, 6f * density, 300, OvershootInterpolator(1.5f))
            }
            Landing.JELLY -> {
                surface.scaleX = 1.06f
                surface.scaleY = 0.92f
                surface.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3f)).start()
            }
            Landing.DROP -> drop(views, -10f * density, 420, BounceInterpolator())
            Landing.SETTLE -> drop(views, 8f * density, 320, OvershootInterpolator(1.4f))
            Landing.SWELL -> {
                surface.scaleX = 1.04f
                surface.scaleY = 1.04f
                surface.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
            }
            Landing.FROST -> {
                surface.alpha = 0.4f
                surface.animate().alpha(1f).setDuration(600).setInterpolator(DecelerateInterpolator()).start()
            }
            Landing.NONE -> Unit
        }
    }

    private fun drop(views: List<View>, fromPx: Float, duration: Long, interpolator: TimeInterpolator) {
        views.forEach { view ->
            view.translationY = fromPx
            view.animate().translationY(0f).setDuration(duration).setInterpolator(interpolator).start()
        }
    }

    @Composable
    private fun MiniSurface(look: Look, backdrop: Backdrop?) {
        val shape = RoundedCornerShape(look.radius.dp)
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (look.glassTint != null && backdrop != null) {
                        Modifier.liquidGlass(backdrop, shape, look.glassTint, look.shadow, blur = 14.dp, refraction = 10.dp)
                    } else {
                        Modifier
                            .then(if (look.shadow != null) Modifier.shadow(10.dp, shape, clip = false, ambientColor = look.shadow, spotColor = look.shadow) else Modifier)
                            .clip(shape)
                            .background(if (look.fillTop != null) Brush.verticalGradient(listOf(look.fillTop, look.fill)) else Brush.verticalGradient(listOf(look.fill, look.fill)))
                            .then(if (look.border != null) Modifier.border(1.dp, look.border, shape) else Modifier)
                    },
                ),
        ) {
            // Editorial: a rule on top, as the navigation sheet has one
            look.rule?.let { rule ->
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(rule),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(rule.copy(alpha = 0.3f)),
                )
            }
        }
    }
}
