package com.github.libretube.ui.yt

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import com.github.libretube.ui.yt.FrequencyMotionMath as F
import com.github.libretube.ui.yt.FrequencyPulseMath as M

/** The resting heights of the logo's three bars (silence still shows a little equaliser). */
private val LOGO_REST = floatArrayOf(0.36f, 0.62f, 0.46f)

/**
 * YT: the mark of Frequency in the header: a coral Formen cookie with three night bars on it,
 * like a small equaliser. With the sound the cookie grows with the beat, bounces on hits, turns
 * and morphs on (cookie, blob, squircle, circle); the bars follow bass, mids and highs over
 * their whole height and hop one after another on hits; a soft veil lifts on hits (guarded).
 * In silence the bars rest at their own heights and the cookie stands still. Drawn in one
 * Canvas, everything read while drawing.
 */
@Composable
fun FrequencyLogo(palette: YtPalette, modifier: Modifier = Modifier) {
    val path = remember { Path() }
    Canvas(modifier.clearAndSetSemantics {}) {
        FrequencyPulse.tick.intValue
        val e = FrequencyPulse.energy.floatValue
        val amp = FrequencyPulse.tier.amplitude
        val grow = F.scale(e, FrequencyPulse.bounce(0), 0.2f, amp)
        val outer = size.minDimension / 2f * 0.9f * grow
        val turn = Math.toRadians(FrequencyPulse.turn.floatValue.toDouble()).toFloat()
        frequencyShapePath(path, center, outer, 9, M.cookieDepth(0.08f, e), frequencyPhase(0), turn)
        drawPath(path, palette.accent)
        // soft light on hits, rate-safe
        val lift = M.pulseLift(FrequencyPulse.punch.floatValue)
        if (lift > 0.003f) drawPath(path, Color.White, alpha = lift)

        val w = size.minDimension * 0.11f
        val gap = w * 0.8f
        val maxH = size.minDimension * 0.5f
        val left = center.x - (3f * w + 2f * gap) / 2f
        for (i in 0 until 3) {
            // the rest height, lifted by the band (never past the full height)
            val band = when (i) {
                0 -> FrequencyPulse.bass.floatValue
                1 -> FrequencyPulse.mid.floatValue
                else -> FrequencyPulse.high.floatValue
            }
            val h = maxH * (LOGO_REST[i] + (1f - LOGO_REST[i]) * M.clamp01(band))
            val hop = -size.minDimension * 0.06f * FrequencyPulse.bounce(i + 1) * amp
            drawRoundRect(
                palette.ink,
                Offset(left + i * (w + gap), center.y - h / 2f + hop),
                Size(w, h),
                CornerRadius(w / 2f),
            )
        }
    }
}
