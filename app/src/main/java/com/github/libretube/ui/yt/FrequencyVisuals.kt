package com.github.libretube.ui.yt

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.github.libretube.ui.yt.FrequencyMotionMath as F
import com.github.libretube.ui.yt.FrequencyPulseMath as M
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * YT: the visualizers of the design "Frequency": the background (aura, gooey blobs that merge
 * and split, a soft spectrum wave), the header's spectrum bars and the wave along the bottom
 * edge of the nav bar. Each draws in its own layer (graphicsLayer first), so a frame redraws only
 * that layer, never the list above it; everything is read while drawing, paths and brushes are
 * made once per size, and the tier (FrequencyPulse.tier) decides how much is drawn.
 */

/** A colour along the spectrum, bass (coral) over the mids (violet) to the highs (aqua). */
fun frequencySpectrumColor(palette: YtPalette, at: Float): Color {
    val t = at.coerceIn(0f, 1f)
    return if (t < 0.5f) lerp(palette.accent, palette.lavender, t * 2f) else lerp(palette.lavender, palette.lime, (t - 0.5f) * 2f)
}

/**
 * The spectrum as a smooth line into [path] (reset first) from [left] to [right], its rest at
 * [baseY] and its peaks [height] above (or below, with a negative height), from [count] bars of
 * FrequencyPulse.bands. [mirror]: the bass in the middle, the highs at both ends. [close]: down
 * (or up) to [closeY], for a filled wave.
 */
fun frequencyWavePath(
    path: Path,
    left: Float,
    right: Float,
    baseY: Float,
    height: Float,
    count: Int,
    mirror: Boolean,
    closeY: Float? = null,
) {
    path.reset()
    val bands = FrequencyPulse.bands
    val n = count.coerceAtLeast(2)
    val points = if (mirror) n * 2 - 1 else n
    val step = (right - left) / (points - 1)
    var px = left
    var py = baseY - height * value(bands, 0, n, points, mirror)
    if (closeY != null) {
        path.moveTo(left, closeY)
        path.lineTo(px, py)
    } else {
        path.moveTo(px, py)
    }
    for (p in 1 until points) {
        val x = left + step * p
        val y = baseY - height * value(bands, p, n, points, mirror)
        // through the midpoints: a smooth curve with no overshoot
        path.quadraticTo(px, py, (px + x) / 2f, (py + y) / 2f)
        px = x
        py = y
    }
    path.lineTo(px, py)
    if (closeY != null) {
        path.lineTo(right, closeY)
        path.close()
    }
}

private fun value(bands: FloatArray, p: Int, n: Int, points: Int, mirror: Boolean): Float {
    val i = if (mirror) abs(p - (points - 1) / 2) else p
    // ends taper to the rest line, so the wave meets the edges softly
    val edge = if (p == 0 || p == points - 1) 0.4f else 1f
    return F.bar(bands, i, n) * edge
}

/**
 * The background of a Frequency screen, drawn behind its content (put it first in a Box with
 * Modifier.matchParentSize()): the night, three soft lights (coral, violet, aqua) drifting with
 * the bass, mids and highs; gooey blobs that pull together with the bass (they merge) and drift
 * apart with the mids (they split), made with a RuntimeShader on Android 13+ in the high tier and
 * from soft gradients otherwise; and a large soft spectrum wave low on the screen. At rest: a
 * calm gradient and a gentle arch.
 */
@Composable
fun FrequencyBackdrop(palette: YtPalette, modifier: Modifier = Modifier) {
    val blobShader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) FrequencyBlobShader.createOrNull() else null }
    Spacer(
        modifier
            .clearAndSetSemantics {}
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val big = maxOf(w, h)
                val short = min(w, h).coerceAtLeast(1f)
                val r1 = big * 0.55f
                val r2 = big * 0.5f
                val r3 = big * 0.35f
                val coral = Brush.radialGradient(listOf(palette.accent, palette.accent.copy(alpha = 0f)), Offset.Zero, r1.coerceAtLeast(1f))
                val violet = Brush.radialGradient(listOf(palette.lavender, palette.lavender.copy(alpha = 0f)), Offset.Zero, r2.coerceAtLeast(1f))
                val aqua = Brush.radialGradient(listOf(palette.lime, palette.lime.copy(alpha = 0f)), Offset.Zero, r3.coerceAtLeast(1f))
                val blobColors = arrayOf(palette.accent, palette.lavender, palette.lime, palette.pink, palette.lavender)
                // the fallback blobs: one unit gradient per colour, scaled while drawing
                val blobBrushes = Array(blobColors.size) {
                    Brush.radialGradient(
                        0f to blobColors[it], 0.55f to blobColors[it].copy(alpha = 0.55f), 1f to blobColors[it].copy(alpha = 0f),
                        center = Offset.Zero, radius = 100f,
                    )
                }
                blobShader?.colors(blobColors)
                val wave = Path()
                val waveFill = Brush.verticalGradient(
                    listOf(palette.accent.copy(alpha = M.WAVE_FILL), palette.lavender.copy(alpha = M.WAVE_FILL / 2f), palette.background.copy(alpha = 0f)),
                    startY = h * 0.6f,
                    endY = h,
                )
                val waveLine = Stroke(1.5.dp.toPx())
                onDrawBehind {
                    drawRect(palette.background)
                    FrequencyPulse.tick.intValue
                    val tier = FrequencyPulse.tier
                    val amp = tier.amplitude
                    val bass = FrequencyPulse.bass.floatValue
                    val dx = 0.12f * w * (FrequencyPulse.mid.floatValue - FrequencyPulse.high.floatValue) * amp
                    val dy = -0.1f * h * bass * amp
                    val grow = 1f + 0.18f * bass * amp
                    val a = M.auraAlpha(FrequencyPulse.auraLight)
                    // the brushes are centred at 0,0 and moved by translate: nothing allocated
                    translate(w * 0.92f + dx, h * 0.04f + dy) { drawCircle(coral, r1 * grow, Offset.Zero, alpha = a * 0.7f) }
                    translate(w * 0.05f - dx, h * 0.42f + dy * 0.6f) { drawCircle(violet, r2 * grow, Offset.Zero, alpha = a * 0.55f) }
                    translate(w * 0.75f - dx * 0.5f, h * 0.9f - dy * 0.4f) { drawCircle(aqua, r3, Offset.Zero, alpha = a * 0.35f) }

                    val n = tier.blobs
                    if (n > 0) {
                        val flow = FrequencyPulse.flow
                        val dist = F.blobDistance(FrequencyPulse.pull, FrequencyPulse.spread) * short
                        val cx = w * 0.5f
                        val cy = h * 0.34f
                        val light = M.blobAlpha(FrequencyPulse.glow.floatValue)
                        val useShader = blobShader != null && FrequencyPulse.choice.shader
                        for (i in 0 until FrequencyBlobShader.MAX) {
                            if (i >= n) {
                                if (useShader) blobShader!!.blob(i, 0f, 0f, 0f)
                                continue
                            }
                            val turn = flow * (0.35f + 0.06f * i) * (if (i % 2 == 0) 1f else -1f) + i * M.TAU / n
                            val d = dist * (0.75f + 0.25f * sin(flow * 0.5f + i * 1.7f))
                            val x = cx + d * cos(turn)
                            val y = cy + d * sin(turn) * 1.3f
                            val band = FrequencyPulse.bands[(i * 3).coerceAtMost(FrequencyPulse.bands.size - 1)]
                            val r = short * (0.12f + 0.03f * (i % 2)) * (1f + (0.25f * FrequencyPulse.bounce(i) + 0.2f * band) * amp)
                            if (useShader) {
                                blobShader!!.blob(i, x, y, r)
                            } else {
                                translate(x, y) {
                                    scale(r / 100f, Offset.Zero) {
                                        // overlapping gradients add up: each a half, so three together stay near the peak
                                        drawCircle(blobBrushes[i], 100f, Offset.Zero, alpha = light * 0.5f)
                                    }
                                }
                            }
                        }
                        if (useShader) blobShader!!.draw(this, light)
                    }

                    if (tier.backgroundWave) {
                        val base = h * 0.8f
                        frequencyWavePath(wave, 0f, w, base, h * 0.16f * amp, 16, mirror = false, closeY = h)
                        drawPath(wave, waveFill)
                        drawPath(wave, palette.accent.copy(alpha = 0.14f), style = waveLine)
                    }
                }
            },
    )
}

/**
 * The metaball blobs on Android 13+: a field of r²/d² per blob, a soft body where it passes 1
 * (so close blobs flow into one) and a faint halo. Uniforms are set in place; the paint is kept.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class FrequencyBlobShader private constructor(private val shader: RuntimeShader) {
    private val paint = android.graphics.Paint().apply { this.shader = this@FrequencyBlobShader.shader }

    fun colors(colors: Array<Color>) {
        for (i in 0 until MAX) {
            val c = colors[i % colors.size]
            shader.setFloatUniform(COLOR_NAMES[i], c.red, c.green, c.blue, 1f)
        }
    }

    fun blob(i: Int, x: Float, y: Float, r: Float) {
        shader.setFloatUniform(BLOB_NAMES[i], x, y, r, 0f)
    }

    fun draw(scope: DrawScope, alpha: Float) {
        shader.setFloatUniform("uAlpha", alpha.coerceIn(0f, 1f))
        // the uniforms changed: hand the shader over again so the paint takes the new values
        paint.shader = shader
        scope.drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, scope.size.width, scope.size.height, paint) }
    }

    companion object {
        const val MAX = 5
        private val BLOB_NAMES = Array(MAX) { "uB$it" }
        private val COLOR_NAMES = Array(MAX) { "uC$it" }

        private const val SOURCE = """
            uniform float4 uB0; uniform float4 uB1; uniform float4 uB2; uniform float4 uB3; uniform float4 uB4;
            uniform half4 uC0; uniform half4 uC1; uniform half4 uC2; uniform half4 uC3; uniform half4 uC4;
            uniform float uAlpha;
            float w(float4 b, float2 p) {
                float2 d = p - b.xy;
                return b.z * b.z / (dot(d, d) + 1.0);
            }
            half4 main(float2 p) {
                float w0 = w(uB0, p); float w1 = w(uB1, p); float w2 = w(uB2, p); float w3 = w(uB3, p); float w4 = w(uB4, p);
                float f = w0 + w1 + w2 + w3 + w4;
                half3 col = (uC0.rgb * w0 + uC1.rgb * w1 + uC2.rgb * w2 + uC3.rgb * w3 + uC4.rgb * w4) / max(f, 0.0001);
                float body = smoothstep(0.7, 1.3, f);
                float halo = smoothstep(0.08, 0.7, f) * 0.3;
                float a = clamp(body * 0.8 + halo, 0.0, 1.0) * uAlpha;
                return half4(col * a, a);
            }
        """

        fun createOrNull(): FrequencyBlobShader? = runCatching { FrequencyBlobShader(RuntimeShader(SOURCE)) }.getOrNull()
    }
}

/**
 * The header's visualizer: the spectrum as mirrored bars (bass left, highs right) in the
 * spectrum's colours, full height on loud parts; at rest a calm arch. Bar count by tier.
 */
@Composable
fun FrequencySpectrum(palette: YtPalette, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .clearAndSetSemantics {}
            .graphicsLayer()
            .drawWithCache {
                val cy = size.height / 2f
                onDrawBehind {
                    FrequencyPulse.tick.intValue
                    val count = FrequencyPulse.tier.bars
                    val slot = size.width / count
                    val barW = (slot * 0.56f).coerceAtMost(4.dp.toPx())
                    val radius = CornerRadius(barW / 2f)
                    for (i in 0 until count) {
                        val h = (size.height * F.bar(FrequencyPulse.bands, i, count)).coerceAtLeast(barW)
                        val x = slot * i + (slot - barW) / 2f
                        drawRoundRect(
                            frequencySpectrumColor(palette, i / (count - 1f)),
                            Offset(x, cy - h / 2f),
                            Size(barW, h),
                            radius,
                        )
                    }
                }
            },
    )
}

/**
 * A wave of the spectrum along the bottom edge of the element (the nav bar): the bass in the
 * middle, the highs at the ends, up to [maxHeight] dp; a soft fill of the spectrum's colours
 * and a thin line on top. At rest a low arch. Draw it behind the content.
 */
fun Modifier.frequencyEdgeWave(palette: YtPalette, maxHeight: Float = 10f): Modifier = drawWithCache {
    val path = Path()
    val line = Stroke(1.4.dp.toPx())
    val top = size.height - maxHeight.dp.toPx() - 2.dp.toPx()
    val fill = Brush.verticalGradient(
        listOf(palette.lavender.copy(alpha = 0f), palette.accent.copy(alpha = 0.32f)),
        startY = top,
        endY = size.height,
    )
    val stroke = Brush.horizontalGradient(
        listOf(palette.lime.copy(alpha = 0.7f), palette.accent.copy(alpha = 0.9f), palette.lime.copy(alpha = 0.7f)),
    )
    onDrawBehind {
        FrequencyPulse.tick.intValue
        val h = maxHeight.dp.toPx() * FrequencyPulse.tier.amplitude
        frequencyWavePath(path, 0f, size.width, size.height - 1.dp.toPx(), h, FrequencyPulse.tier.bars, mirror = true, closeY = size.height)
        drawPath(path, fill)
        frequencyWavePath(path, 0f, size.width, size.height - 1.dp.toPx(), h, FrequencyPulse.tier.bars, mirror = true)
        drawPath(path, stroke, style = line)
    }
}
