package com.github.libretube.ui.yt

import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.media3.ui.DefaultTimeBar
import com.github.libretube.helpers.YtDesign

/**
 * YT: the player's time bar in Frequency: its played part shimmers with the highs, lifting the
 * coral towards white by at most 35 % (FrequencyPulse.shimmer, rate-limited, so it never changes
 * faster than 3 times a second). In silence it is the plain coral. The bar is a View, so it
 * follows the pulse through a listener while it is attached; it only redraws when the colour
 * really changes (in steps of 1/48).
 */
object FrequencyTimeBar {
    private const val STEPS = 48

    fun attach(bar: DefaultTimeBar, base: Int) {
        if (YtDesign.current() != YtDesign.FREQUENCY) return
        var shown = -1
        val listener: () -> Unit = {
            val step = (FrequencyPulseMath.shimmerAlpha(FrequencyPulse.shimmer.floatValue) * STEPS).toInt()
            if (step != shown) {
                shown = step
                val color = ColorUtils.blendARGB(base, 0xFFFFFFFF.toInt(), step / STEPS.toFloat())
                bar.setPlayedColor(color)
                bar.setScrubberColor(color)
            }
        }
        bar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = FrequencyPulse.addViewListener(listener)

            override fun onViewDetachedFromWindow(v: View) {
                FrequencyPulse.removeViewListener(listener)
                // back to the plain coral, so a later attach starts from rest
                shown = -1
                bar.setPlayedColor(base)
                bar.setScrubberColor(base)
            }
        })
        if (bar.isAttachedToWindow) FrequencyPulse.addViewListener(listener)
    }
}
