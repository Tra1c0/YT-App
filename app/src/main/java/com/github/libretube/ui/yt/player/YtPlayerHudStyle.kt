package com.github.libretube.ui.yt.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import androidx.annotation.ColorInt
import androidx.annotation.FontRes
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.github.libretube.R
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtMotionPrefs
import com.github.libretube.ui.yt.YtMotionStyle
import com.github.libretube.ui.yt.YtSignature
import com.github.libretube.ui.yt.palette

/**
 * YT: how the player's View HUDs (the return chip, the time magnifier's lens) look in the current
 * design: its timeline colour as the accent, its type, its corners and the feel of its springs.
 * They sit on the video in the page and in fullscreen (a plain Dialog without Compose owners),
 * hence Views. The video is a SurfaceView, so a glass design gets a frosted fill, not real blur.
 */
class YtPlayerHudStyle private constructor(
    @ColorInt val accent: Int,
    @ColorInt val fill: Int,
    @ColorInt val text: Int,
    @ColorInt val textSecondary: Int,
    /** 0: no outline. */
    @ColorInt val stroke: Int,
    val strokeDp: Float,
    /** The corner radius as a part of the height: 0.5 is a pill, 0 square (Editorial). */
    val corner: Float,
    val typeface: Typeface?,
    val mono: Typeface?,
    val stiffness: Float,
    val damping: Float,
) {
    companion object {
        /** Whether the HUDs animate: the design switch and the system's animator scale (accessibility). */
        val motion: Boolean get() = YtMotionPrefs.enabled && ValueAnimator.areAnimatorsEnabled()

        private val fonts = HashMap<Int, Typeface?>()

        private fun font(context: Context, @FontRes id: Int): Typeface? =
            fonts.getOrPut(id) { runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() }

        fun current(context: Context): YtPlayerHudStyle {
            val design = YtDesign.current()
            val palette = design.palette
            val accent = design.timelineColor
                ?: ThemeHelper.getThemeColor(context, com.google.android.material.R.attr.colorSecondary)
            val signature = palette?.signature
            val motionStyle = palette?.motion ?: YtMotionStyle.CALM
            val (stiffness, damping) = when (motionStyle) {
                YtMotionStyle.CALM -> 700f to 1f
                YtMotionStyle.SPRINGY -> 520f to 0.62f
                YtMotionStyle.CINEMATIC -> 240f to 1f
                YtMotionStyle.CRISP -> 1400f to 1f
                YtMotionStyle.SOFT -> 340f to 0.82f
            }
            val textFont = when (signature) {
                null -> null
                YtSignature.CLAY, YtSignature.POP -> font(context, R.font.font_nunito_800)
                YtSignature.EDITORIAL -> font(context, R.font.font_inter_600)
                YtSignature.GLASS, YtSignature.SHAPES -> font(context, R.font.font_google_sans_500)
                YtSignature.FROST, YtSignature.KIESEL -> font(context, R.font.font_jakarta_600)
                YtSignature.SIDE -> font(context, R.font.font_figtree_600)
                else -> font(context, R.font.font_geist_600)
            }
            // digits that do not shift while they count
            val mono = if (signature == YtSignature.EDITORIAL) {
                font(context, R.font.font_plex_mono_500)
            } else {
                font(context, R.font.font_geist_mono_500)
            }

            if (palette == null) {
                // the classic look: the dark scrim of the player's own overlays
                return YtPlayerHudStyle(
                    accent, 0xD9161619.toInt(), 0xFFFFFFFF.toInt(), 0xB3FFFFFF.toInt(), 0, 0f, 0.5f,
                    null, mono, stiffness, damping,
                )
            }
            val text = palette.text.toArgb()
            val secondary = palette.textSecondary.toArgb()
            return when (signature) {
                // Editorial: paper white, a thin black rule, square corners
                YtSignature.EDITORIAL -> YtPlayerHudStyle(
                    accent, 0xF7FFFFFF.toInt(), text, secondary, 0xFF0A0A0A.toInt(), 1f, 0f,
                    textFont, mono, stiffness, damping,
                )

                // Glas: frosted white with a bright edge
                YtSignature.GLASS -> YtPlayerHudStyle(
                    accent, 0xD1FFFFFF.toInt(), text, secondary, 0xE6FFFFFF.toInt(), 1f, 0.5f,
                    textFont, mono, stiffness, damping,
                )

                // Frost: a dark frosted pill with a faint edge
                YtSignature.FROST -> YtPlayerHudStyle(
                    accent, 0xB3263330.toInt(), text, secondary, 0x40FFFFFF, 1f, 0.5f,
                    textFont, mono, stiffness, damping,
                )

                // the light designs keep their light surfaces, solid enough to read on any picture
                YtSignature.CLAY, YtSignature.POP, YtSignature.KIESEL -> YtPlayerHudStyle(
                    accent, ColorUtils.setAlphaComponent(palette.surface.toArgb(), 0xF2), text, secondary,
                    0, 0f, 0.5f, textFont, mono, stiffness, damping,
                )

                // the dark designs: their background, a little see-through
                else -> YtPlayerHudStyle(
                    accent, ColorUtils.setAlphaComponent(palette.background.toArgb(), 0xE0), text, secondary,
                    0, 0f, 0.5f, textFont, mono, stiffness, damping,
                )
            }
        }
    }
}
