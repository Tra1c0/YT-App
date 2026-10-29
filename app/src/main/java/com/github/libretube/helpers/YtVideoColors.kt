package com.github.libretube.helpers

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.QuantizerCelebi
import com.google.android.material.color.utilities.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import kotlin.math.min

/**
 * YT: tints the area below the video with a soft gradient in the main color of the thumbnail,
 * so the player page picks up the mood of each video.
 */
object YtVideoColors {
    const val PREF_KEY = "yt_dynamic_player_colors"

    /** Returned by [Score] when no color of the image suits a theme, e.g. a grey thumbnail. */
    private const val NO_COLOR = 0
    private const val ANIMATION_MS = 650L

    /** Share of the glow that remains halfway down the gradient. */
    private const val MID_BLEND = 0.62f

    private val lastGlow = WeakHashMap<View, Int>()

    suspend fun apply(context: Context, target: View, thumbnailUrl: String?) {
        if (!PreferenceHelper.getBoolean(PREF_KEY, true)) return

        val background = ThemeHelper.getThemeColor(context, android.R.attr.colorBackground)
        val bitmap = runCatching { ImageHelper.getImage(context, thumbnailUrl) }.getOrNull()
        val seed = bitmap?.let { withContext(Dispatchers.Default) { seedColor(it) } }
        val glow = seed?.let { glowColor(it, ThemeHelper.isDarkMode(context)) } ?: background

        animateTo(target, glow, background)
    }

    private fun seedColor(source: Bitmap): Int? = runCatching {
        // Hardware bitmaps do not allow reading pixels.
        val readable = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        } ?: return null
        val small = Bitmap.createScaledBitmap(readable, 48, 27, true)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        Score.score(QuantizerCelebi.quantize(pixels, 64), 1, NO_COLOR)
            .firstOrNull()
            ?.takeIf { it != NO_COLOR }
    }.getOrNull()

    /** A deep, calm version of the seed in dark mode and a pale one in light mode. */
    private fun glowColor(seed: Int, dark: Boolean): Int {
        val hct = Hct.fromInt(seed)
        return if (dark) {
            Hct.from(hct.hue, min(hct.chroma, 40.0), 20.0).toInt()
        } else {
            Hct.from(hct.hue, min(hct.chroma, 28.0), 92.0).toInt()
        }
    }

    private fun gradientColors(glow: Int, background: Int) =
        intArrayOf(glow, ColorUtils.blendARGB(glow, background, MID_BLEND), background)

    private fun animateTo(target: View, glow: Int, background: Int) {
        val from = lastGlow[target] ?: background
        lastGlow[target] = glow

        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            gradientColors(from, background)
        )
        target.background = drawable

        if (from == glow) return
        ValueAnimator.ofArgb(from, glow).apply {
            duration = ANIMATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animator ->
                drawable.colors = gradientColors(animator.animatedValue as Int, background)
            }
            start()
        }
    }
}
