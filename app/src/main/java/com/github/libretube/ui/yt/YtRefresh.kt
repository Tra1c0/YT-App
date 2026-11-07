package com.github.libretube.ui.yt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * YT: pull to refresh in the material of each design. The indicator follows the pull 1:1 and
 * ticks when the pull is far enough; while loading it keeps moving in the character of the
 * design: a red arc (YouTube neu), a stretching cobalt pill with a bouncing lime dot (Movo), a
 * film leader (After), an apricot dot that hops (Side), a drop of clay (Clay), a rule with
 * AKTUALISIEREN in mono (Editorial), a glass drop (Glas), a blob with a spinning star (Pop), a
 * turning cookie (Formen), a frost ring (Frost), a rolling pebble (Kiesel).
 */

/** The pull distance (in dp) at which a refresh starts; the indicator rests there while loading. */
private val RefreshThreshold = 80.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtRefreshBox(
    palette: YtPalette,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    /** Room above the indicator, e.g. for chrome floating over the list. */
    indicatorTop: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val haptics = rememberYtHaptics()
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction >= 1f }.collect { armed -> if (armed) haptics.tick() }
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            YtRefreshIndicator(
                palette, state, isRefreshing,
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = indicatorTop),
            )
        },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YtRefreshIndicator(palette: YtPalette, state: PullToRefreshState, refreshing: Boolean, modifier: Modifier) {
    val thresholdPx = with(LocalDensity.current) { RefreshThreshold.toPx() }
    // the ongoing motion while loading, 0 to 360, still when motion is off
    val spin = if (refreshing) rememberSpin(if (palette.signature == YtSignature.SIDE) 700 else 1100) else null
    val measurer = rememberTextMeasurer()
    val label = if (palette.signature == YtSignature.EDITORIAL) {
        measurer.measure(
            "AKTUALISIEREN",
            TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.5.sp, color = palette.text),
        )
    } else null
    Box(
        modifier
            .size(64.dp)
            .graphicsLayer {
                val f = state.distanceFraction
                translationY = f.coerceAtMost(1.6f) * thresholdPx - size.height
                alpha = (f * 1.6f).coerceIn(0f, 1f)
            },
    ) {
        Canvas(Modifier.size(64.dp)) {
            val f = state.distanceFraction.coerceIn(0f, 1.6f)
            val p = f.coerceAtMost(1f)
            val s = spin?.value ?: 0f
            val rad = s * PI.toFloat() / 180f
            when (palette.signature) {
                YtSignature.YOUTUBE -> {
                    val r = 11.dp.toPx()
                    drawArc(
                        color = palette.accent,
                        startAngle = if (refreshing) s - 90f else -90f,
                        sweepAngle = if (refreshing) 260f else 330f * p,
                        useCenter = false,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                YtSignature.MOVO -> {
                    val w = (24 + 28 * f).dp.toPx()
                    val h = 11.dp.toPx()
                    drawRoundRect(palette.accent, Offset(center.x - w / 2, center.y - h / 2), Size(w, h), CornerRadius(h / 2))
                    if (refreshing) {
                        drawCircle(palette.lime, 3.5.dp.toPx(), Offset(center.x + sin(rad) * (w / 2 - h / 2), center.y))
                    }
                }
                YtSignature.AFTER -> {
                    val r = 13.dp.toPx()
                    drawCircle(palette.text.copy(alpha = 0.3f), r, center, style = Stroke(width = 2.dp.toPx()))
                    drawArc(
                        color = palette.accent,
                        startAngle = if (refreshing) s - 90f else -90f,
                        sweepAngle = if (refreshing) 80f else 360f * p,
                        useCenter = false,
                        topLeft = Offset(center.x - r + 3.dp.toPx(), center.y - r + 3.dp.toPx()),
                        size = Size((r - 3.dp.toPx()) * 2, (r - 3.dp.toPx()) * 2),
                        style = Stroke(width = 6.dp.toPx()),
                    )
                }
                YtSignature.SIDE -> {
                    val hop = if (refreshing) -abs(sin(rad)) * 7.dp.toPx() else 0f
                    drawCircle(palette.accent, (4 + 7 * p).dp.toPx(), Offset(center.x, center.y + hop))
                }
                YtSignature.CLAY -> {
                    // a drop of clay hanging from the top: stretches with the pull, wobbles while loading
                    val wobble = if (refreshing) sin(rad * 2) * 0.08f else 0f
                    scale(1f - 0.22f * p + wobble, 1f + 0.75f * p - wobble, pivot = Offset(center.x, center.y - 14.dp.toPx())) {
                        drawCircle(ytDayNight(Color(0x33B07A66), Color(0x33000000)), 12.dp.toPx(), Offset(center.x, center.y + 2.dp.toPx()))
                        drawCircle(ytDayNight(Color(0xFFFBEDE2), Color(0xFF46352E)), 11.dp.toPx(), center)
                        drawCircle(ytDayNight(Color(0x80FFFFFF), Color(0x1FFFF0E4)), 11.dp.toPx(), center, style = Stroke(1.dp.toPx()))
                    }
                }
                YtSignature.EDITORIAL -> {
                    val w = 120.dp.toPx() * p
                    drawLine(palette.text, Offset(center.x - w / 2, center.y), Offset(center.x + w / 2, center.y), 1.5.dp.toPx())
                    if (refreshing) {
                        val x = center.x - 60.dp.toPx() + (s / 360f) * 120.dp.toPx()
                        drawLine(palette.text, Offset(x - 10.dp.toPx(), center.y), Offset(x + 10.dp.toPx(), center.y), 4.dp.toPx())
                    }
                    if (label != null && f > 0.6f) {
                        drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y + 6.dp.toPx()), alpha = ((f - 0.6f) * 2.5f).coerceIn(0f, 1f))
                    }
                }
                YtSignature.GLASS -> {
                    val breath = if (refreshing) 1f + 0.08f * sin(rad) else 1f
                    val r = (7 + 9 * p).dp.toPx() * breath
                    drawCircle(Color(0x33000000), r, Offset(center.x, center.y + 2.dp.toPx()))
                    drawCircle(ytDayNight(Color(0x99FFFFFF), Color(0x4D6FB5AA)), r, center)
                    drawCircle(ytDayNight(Color(0xE6FFFFFF), Color(0x80CFEDE7)), r, center, style = Stroke(1.dp.toPx()))
                    drawCircle(ytDayNight(Color(0xB3FFFFFF), Color(0x66CFEDE7)), r * 0.28f, Offset(center.x - r * 0.35f, center.y - r * 0.38f))
                }
                YtSignature.POP -> {
                    val r = (9 + 10 * p).dp.toPx()
                    drawCircle(palette.raised, r, center)
                    rotate(if (refreshing) s else 72f * p, center) {
                        drawPath(star(center, r * 0.5f), palette.accent)
                    }
                }
                YtSignature.SHAPES -> {
                    val r = (9 + 6 * p).dp.toPx()
                    rotate(180f * p + s, center) {
                        val outline = CookieShape(7, 0.1f).createOutline(Size(r * 2, r * 2), layoutDirection, this)
                        translate(center.x - r, center.y - r) { drawOutline(outline, palette.lime) }
                    }
                }
                YtSignature.FROST -> {
                    val breath = if (refreshing) 1f + 0.1f * sin(rad) else 1f
                    val r = 12.dp.toPx() * (1.25f - 0.25f * p) * breath
                    drawCircle(Color.White.copy(alpha = 0.2f + 0.65f * p), r, center, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.12f * p), r - 3.dp.toPx(), center)
                }
                YtSignature.KIESEL -> {
                    // a pebble rolling in from the left, turning as it goes
                    val x = center.x + (p - 1f) * 44.dp.toPx()
                    val wobble = if (refreshing) sin(rad) * 10f else 0f
                    rotate(360f * p + wobble, Offset(x, center.y)) {
                        drawRoundRect(palette.surface, Offset(x - 13.dp.toPx(), center.y - 6.dp.toPx()), Size(26.dp.toPx(), 12.dp.toPx()), CornerRadius(6.dp.toPx()))
                    }
                    drawCircle(palette.accent, 4.dp.toPx(), Offset(center.x + 18.dp.toPx(), center.y + 10.dp.toPx()), alpha = p)
                }
            }
        }
    }
}

private fun abs(x: Float) = if (x < 0f) -x else x

/** A five-pointed star around [center]. */
private fun DrawScope.star(center: Offset, radius: Float): Path = Path().apply {
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius else radius * 0.45f
        val a = -PI / 2 + i * PI / 5
        val x = center.x + (r * cos(a)).toFloat()
        val y = center.y + (r * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
