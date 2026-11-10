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
import com.github.libretube.ui.yt.FrequencyPulseMath as M

/** The resting heights of the logo's three bars (silence still shows a little equaliser). */
private val LOGO_REST = floatArrayOf(0.42f, 0.7f, 0.52f)

/**
 * YT: the mark of Frequency in the header: a coral Formen cookie with three night bars on it,
 * like a small equaliser. The cookie breathes with the bass and turns with the music, the bars
 * follow bass, mids and highs (rate-limited by FrequencyPulse); in silence the bars rest at
 * their own heights and the cookie stands still. Drawn in one Canvas, everything read while
 * drawing.
 */
@Composable
fun FrequencyLogo(palette: YtPalette, modifier: Modifier = Modifier) {
    val path = remember { Path() }
    Canvas(modifier.clearAndSetSemantics {}) {
        val e = FrequencyPulse.energy.floatValue
        val outer = size.minDimension / 2f / M.breathScale(1f) * M.breathScale(e)
        val turn = Math.toRadians(FrequencyPulse.turn.floatValue.toDouble()).toFloat()
        cookiePath(path, center, outer, 9, M.cookieDepth(0.08f, e), turn)
        drawPath(path, palette.accent)
        // soft light on hits, rate-safe
        val lift = M.pulseLift(FrequencyPulse.punch.floatValue)
        if (lift > 0.003f) drawPath(path, androidx.compose.ui.graphics.Color.White, alpha = lift)

        val w = size.minDimension * 0.11f
        val gap = w * 0.8f
        val maxH = size.minDimension * 0.42f
        val left = center.x - (3f * w + 2f * gap) / 2f
        for (i in 0 until 3) {
            // the rest height, lifted by the band (never past the full height)
            val band = when (i) {
                0 -> FrequencyPulse.bass.floatValue
                1 -> FrequencyPulse.mid.floatValue
                else -> FrequencyPulse.high.floatValue
            }
            val h = maxH * (LOGO_REST[i] + (1f - LOGO_REST[i]) * M.clamp01(band))
            drawRoundRect(
                palette.ink,
                Offset(left + i * (w + gap), center.y - h / 2f),
                Size(w, h),
                CornerRadius(w / 2f),
            )
        }
    }
}
