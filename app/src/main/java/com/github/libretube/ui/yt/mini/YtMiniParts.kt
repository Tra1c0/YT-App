package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YT: the neutral parts every design's mini player starts from, until it brings its own: the
 * preview (title, play pill, the next video), the drop target's hover and accept label, the glyph
 * flash of play and pause. Styled with the design's colors and type ([Style]).
 */
object YtMiniParts {
    /** How much of the docked row still shows at preview [peek]: it gives way first. */
    fun restFade(peek: Float): Float = 1f - (peek / 0.35f).coerceIn(0f, 1f)

    /** How much of the preview's parts shows at [peek]: they come once the picture has grown a bit. */
    fun peekFade(peek: Float): Float = ((peek - 0.3f) / 0.6f).coerceIn(0f, 1f)

    /** The colors and type of the neutral parts. */
    data class Style(
        /** The row of the next video and the label's ground. */
        val surface: Color,
        val onSurface: Color,
        val secondary: Color,
        /** The play pill. */
        val pill: Color,
        val onPill: Color,
        /** The hover outline. */
        val accent: Color,
        val title: TextStyle,
        val label: TextStyle,
        val thumbRadius: Dp = 6.dp,
        val rowRadius: Dp = 14.dp,
    )

    /** The glyph flash: a disc and the glyph on it. */
    data class FlashColors(val disc: Color, val glyph: Color)

    fun flashColors(scene: YtMiniScene): FlashColors =
        FlashColors(Color(0x73000000), Color.White)

    /**
     * The preview's title, play pill and next video, placed from the bottom of the box (which
     * grows upward from the bar), so they rise with it and are revealed as it grows.
     */
    @Composable
    fun Peek(scene: YtMiniScene, style: Style) {
        val grown = scene.peekLayout ?: return
        val density = LocalDensity.current.density
        val next = scene.next
        val target = scene.target
        val playing = scene.info.playing
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val shown = peekFade(scene.peek())
                    alpha = shown
                    translationY = 10f * density * (1f - shown)
                },
            content = {
                Box(Modifier.layoutId("title")) {
                    Text(
                        text = scene.info.title,
                        style = style.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .layoutId("pill")
                        .graphicsLayer {
                            val s = if (target == YtMiniTarget.PILL) 1.06f else 1f
                            scaleX = s
                            scaleY = s
                        }
                        .clip(RoundedCornerShape(50))
                        .background(if (target == YtMiniTarget.PILL) style.pill else style.pill.copy(alpha = style.pill.alpha * 0.86f)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
                        Glyph(playing, style.onPill, Modifier.size(12.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(if (playing) R.string.yt_mini_peek_playing else R.string.yt_mini_peek_paused),
                            style = style.label.copy(color = style.onPill),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (next != null) {
                    NextRow(next, style, target == YtMiniTarget.NEXT, Modifier.layoutId("next"))
                }
            },
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val parts = listOfNotNull(
                measurables.firstOrNull { it.layoutId == "title" }?.let { it to grown.title },
                measurables.firstOrNull { it.layoutId == "pill" }?.let { it to grown.pill },
                grown.next?.let { box -> measurables.firstOrNull { it.layoutId == "next" }?.let { it to box } },
            )
            val placed = parts.map { (measurable, box) ->
                val width = box.width.roundToInt().coerceAtLeast(0)
                val height = box.height.roundToInt().coerceAtLeast(0)
                measurable.measure(Constraints.fixed(width, height)) to box
            }
            layout(w, h) {
                // from the bottom: the box's foot stays on the bar while it grows
                placed.forEach { (placeable, box) ->
                    placeable.placeRelative(box.left.roundToInt(), (h - (grown.boxHeight - box.top)).roundToInt())
                }
            }
        }
    }

    @Composable
    private fun NextRow(next: YtMiniNext, style: Style, active: Boolean, modifier: Modifier) {
        val shape = RoundedCornerShape(style.rowRadius)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .graphicsLayer {
                    val s = if (active) 1.03f else 1f
                    scaleX = s
                    scaleY = s
                }
                .clip(shape)
                .background(if (active) style.surface.copy(alpha = (style.surface.alpha * 1.6f).coerceAtMost(1f)) else style.surface)
                .padding(6.dp),
        ) {
            AsyncImage(
                model = next.thumbnail,
                contentDescription = null,
                imageLoader = ImageHelper.loader(),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(78.dp)
                    .clip(RoundedCornerShape(style.thumbRadius))
                    .background(style.secondary.copy(alpha = 0.2f)),
            )
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = stringResource(R.string.yt_mini_peek_next).uppercase(),
                    style = style.label.copy(color = style.secondary, fontSize = 10.sp, letterSpacing = 0.12.em),
                    maxLines = 1,
                )
                Text(
                    text = next.title,
                    style = style.title.copy(color = style.onSurface, fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (next.channel.isNotBlank()) {
                    Text(
                        text = next.channel,
                        style = style.label.copy(color = style.secondary, fontSize = 11.sp, fontWeight = FontWeight.Normal),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    /** ▶ while it plays, ❚❚ while paused, drawn (no font has both the same size). */
    @Composable
    fun Glyph(playing: Boolean, color: Color, modifier: Modifier) {
        Canvas(modifier) { glyph(playing, color, center, size.minDimension / 2f) }
    }

    private fun DrawScope.glyph(playing: Boolean, color: Color, center: Offset, radius: Float) {
        if (playing) {
            val path = Path().apply {
                moveTo(center.x - radius * 0.7f, center.y - radius)
                lineTo(center.x + radius, center.y)
                lineTo(center.x - radius * 0.7f, center.y + radius)
                close()
            }
            drawPath(path, color)
        } else {
            val bar = radius * 0.6f
            drawRect(color, Offset(center.x - radius * 0.85f, center.y - radius), Size(bar, radius * 2f))
            drawRect(color, Offset(center.x + radius * 0.85f - bar, center.y - radius), Size(bar, radius * 2f))
        }
    }

    /**
     * The drop target: an outline that brightens as a dragged video comes near ([outline] of the
     * body, null for none), and the label of an accepted drop over [anchor] (px of the box at
     * rest, left to right; centered in it).
     */
    @Composable
    fun Drop(scene: YtMiniScene, style: Style, anchor: YtMiniBox, outline: ((Size) -> Path)?) {
        val density = LocalDensity.current.density
        if (outline != null) {
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                val near = scene.hover()
                if (near > 0f && scene.peek() <= 0f) {
                    drawPath(outline(size), style.accent.copy(alpha = near), style = Stroke(2f * density))
                }
            }
        }

        // the label of the last accepted drop: in at once, held, out
        val label = remember { Animatable(0f) }
        val accepted = YtDock.accepted
        var seen by remember { mutableIntStateOf(accepted) }
        val motion = LocalYtMotion.current
        LaunchedEffect(accepted) {
            if (accepted == seen) return@LaunchedEffect
            seen = accepted
            if (motion) {
                label.animateTo(1f, tween(180, easing = LinearEasing))
                delay(1400)
                label.animateTo(0f, tween(220, easing = LinearEasing))
            } else {
                label.snapTo(1f)
                delay(1400)
                label.snapTo(0f)
            }
        }
        val drop = YtDock.lastDrop
        if (drop != null) {
            val text = if (drop.next) {
                stringResource(R.string.yt_mini_drop_next)
            } else {
                stringResource(R.string.yt_mini_drop_end, drop.position)
            }
            Layout(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val v = label.value
                        alpha = v
                        scaleX = 0.9f + 0.1f * v
                        scaleY = 0.9f + 0.1f * v
                    },
                content = {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(style.pill)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(text, style = style.label.copy(color = style.onPill), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            ) { measurables, constraints ->
                val most = anchor.width.roundToInt().coerceAtLeast(0)
                val placeable = measurables.first().measure(Constraints(maxWidth = min(most, constraints.maxWidth), maxHeight = constraints.maxHeight))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.placeRelative(
                        (anchor.centerX - placeable.width / 2f).roundToInt(),
                        (anchor.centerY - placeable.height / 2f).roundToInt(),
                    )
                }
            }
        }
    }

    /** The glyph of play or pause in the picture's middle, briefly, when the player toggles it. */
    @Composable
    fun Flash(scene: YtMiniScene, colors: FlashColors) {
        val flash = remember { Animatable(0f) }
        val flashes = scene.flashes
        var seen by remember { mutableIntStateOf(flashes) }
        val motion = LocalYtMotion.current
        LaunchedEffect(flashes) {
            if (flashes == seen) return@LaunchedEffect
            seen = flashes
            if (motion) {
                flash.snapTo(0f)
                flash.animateTo(1f, tween(560, easing = LinearEasing))
            } else {
                flash.snapTo(0.3f)
                delay(450)
            }
            flash.snapTo(0f)
        }
        val playing = scene.flashPlaying
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val t = flash.value
                if (t <= 0f) return@Canvas
                // up in a quarter, then fading while it swells
                val shown = if (t < 0.25f) t / 0.25f else 1f - (t - 0.25f) / 0.75f
                val radius = min(size.minDimension * 0.34f, 24.dp.toPx()) * (0.85f + 0.25f * t)
                drawCircle(colors.disc.copy(alpha = colors.disc.alpha * shown), radius, center)
                glyph(playing, colors.glyph.copy(alpha = colors.glyph.alpha * shown), center, radius * 0.42f)
            }
        }
    }

    /** A hover outline that follows a rounded rect of [radius] at the top (STACK) or all round. */
    fun roundedOutline(radius: Float, topOnly: Boolean): (Size) -> Path = { size ->
        Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 1f,
                    top = 1f,
                    right = size.width - 1f,
                    bottom = size.height - 1f,
                    topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                    topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                    bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(if (topOnly) 0f else radius),
                    bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(if (topOnly) 0f else radius),
                ),
            )
        }
    }
}
