package com.github.libretube.ui.yt

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.yt.feed.YtSeenMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** YT: "Gesehenes markieren" (Gesten & Interaktion → Startseite), on by default. */
object YtSeenPrefs {
    const val KEY = "yt_seen_marks"
    val enabled: Boolean get() = PreferenceHelper.getBoolean(KEY, true)
}

/**
 * YT: every watch position by video id, for the watched marks of the home cards. One query of
 * the whole table, shared by all cards and repeated only when the table changes (the player
 * saves on pause and at the end); it stops 5 s after the last card left the screen.
 */
object YtSeenStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val positions: StateFlow<Map<String, Long>> by lazy {
        Database.watchPositionDao().observeAll()
            .map { list -> list.associate { it.videoId to it.position } }
            .catch { emit(emptyMap()) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }
}

/** How the "Gesehen" badge looks in a design. */
internal class SeenBadge(
    val shape: Shape,
    val fill: Color,
    val ink: Color,
    val uppercase: Boolean,
    val iconOnly: Boolean,
    /** The corner for the View rows (search results); negative: fully round. */
    val cornerDp: Float,
)

internal fun seenBadge(palette: YtPalette): SeenBadge = when (palette.signature) {
    YtSignature.EDITORIAL -> SeenBadge(RectangleShape, Color.Black.copy(alpha = 0.66f), Color.White, uppercase = true, iconOnly = false, cornerDp = 0f)
    YtSignature.SHAPES -> SeenBadge(CircleShape, palette.accent, palette.onAccent, uppercase = false, iconOnly = true, cornerDp = -1f)
    YtSignature.POP -> SeenBadge(RoundedCornerShape(8.dp), palette.accent, palette.onAccent, uppercase = false, iconOnly = false, cornerDp = 8f)
    YtSignature.GLASS, YtSignature.FROST -> SeenBadge(RoundedCornerShape(50), Color.White.copy(alpha = 0.24f), Color.White, uppercase = false, iconOnly = false, cornerDp = -1f)
    YtSignature.CLAY -> SeenBadge(RoundedCornerShape(50), palette.surface.copy(alpha = 0.9f), palette.text, uppercase = false, iconOnly = false, cornerDp = -1f)
    else -> SeenBadge(RoundedCornerShape(4.dp), Color.Black.copy(alpha = 0.66f), Color.White, uppercase = false, iconOnly = false, cornerDp = 4f)
}

/**
 * YT: marks a thumbnail of [video] as watched, quietly: once it is finished, a thumbnail dimmed
 * to [YtSeenMark.WATCHED_BRIGHTNESS] with a small "Gesehen" badge in the top corner. The bar of
 * a started video is drawn by [ytSeenTop], above the thumbnail's overlays. Put it after the
 * thumbnail's clip. Nothing for live streams, unwatched videos or with the setting off.
 */
@Composable
fun Modifier.ytSeen(video: StreamItem, palette: YtPalette): Modifier {
    if (video.isLive || !remember { YtSeenPrefs.enabled }) return this
    val id = remember(video.url) { video.url?.toID() } ?: return this
    val positions by YtSeenStore.positions.collectAsState()
    val mark = YtSeenMark.of(positions[id], video.duration) ?: return this
    if (!mark.watched) return this
    val measurer = rememberTextMeasurer()
    val badge = remember(palette) { seenBadge(palette) }
    val word = stringResource(R.string.yt_seen_badge).let { if (badge.uppercase) it.uppercase() else it }
    val style = TextStyle(
        color = badge.ink,
        fontFamily = palette.font,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = if (badge.uppercase) 0.8.sp else 0.sp,
    )
    return this.drawWithCache {
        val inset = 6.dp.toPx()
        val icon = 9.dp.toPx()
        val pad = 5.dp.toPx()
        val text = if (!badge.iconOnly) measurer.measure(word, style) else null
        val badgeHeight = maxOf(icon, text?.size?.height?.toFloat() ?: 0f) + 2 * 3.dp.toPx()
        val badgeWidth = if (badge.iconOnly) badgeHeight else pad + icon + 3.dp.toPx() + (text?.size?.width ?: 0) + pad
        val outline = badge.shape.createOutline(Size(badgeWidth, badgeHeight), layoutDirection, this)
        val check = Path().apply {
            moveTo(0f, icon * 0.55f)
            lineTo(icon * 0.38f, icon * 0.9f)
            lineTo(icon, icon * 0.15f)
        }
        val checkStroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        onDrawWithContent {
            drawContent()
            // a dimmed picture: quiet, but the eye skips it
            drawRect(Color.Black, alpha = 1f - YtSeenMark.WATCHED_BRIGHTNESS)
            translate(inset, inset) {
                drawBadge(outline, badge.fill)
                val iconX = if (badge.iconOnly) (badgeHeight - icon) / 2f else pad
                translate(iconX, (badgeHeight - icon) / 2f) { drawPath(check, badge.ink, style = checkStroke) }
                if (text != null) {
                    drawText(text, topLeft = Offset(pad + icon + 3.dp.toPx(), (badgeHeight - text.size.height) / 2f))
                }
            }
        }
    }
}

/** Where a card's time-left pill sits: at the end (as the duration chips) or, by a cut corner, the start. */
enum class YtPillSide { END, START }

/**
 * YT: the watched bar of a started [video], drawn above everything of its thumbnail: a thin bar
 * in the design's accent along the bottom (filling from the start side). While its card is held
 * (see YtHeld.kt) the bar grows from 3 to 9 dp and a small dark pill above it says what is left
 * ("noch 12 Min."; shorter forms on small thumbnails, none if even the shortest does not fit);
 * chips marked with [ytHeldFade] fade meanwhile. Put it on the thumbnail's Box after its clip, so
 * it draws after the Box's children (duration chips, gradients, play buttons), or on a plain
 * thumbnail. [shape] clips the drawing where the Box itself is not clipped. Nothing for live
 * streams, unwatched or finished videos, unknown lengths or with the setting off.
 */
@Composable
fun Modifier.ytSeenTop(
    video: StreamItem,
    palette: YtPalette,
    shape: Shape = RectangleShape,
    pillSide: YtPillSide = YtPillSide.END,
): Modifier {
    if (video.isLive || !remember { YtSeenPrefs.enabled }) return this
    val id = remember(video.url) { video.url?.toID() } ?: return this
    val positions by YtSeenStore.positions.collectAsState()
    val position = positions[id]
    val mark = YtSeenMark.of(position, video.duration) ?: return this
    // a finished video has its badge (ytSeen), no bar
    if (mark.watched) return this
    val progress = mark.progress ?: return this
    val texts = YtSeenMark.left(position, video.duration)?.let { leftTexts(it) }.orEmpty()
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = Color.White, fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    return this.then(
        SeenTopElement(progress, palette.accent, texts, measurer, style, shape, pillSide, LocalYtMotion.current),
    )
}

/** The pill's texts, longest first: "noch 1 Std. 5 Min.", "1 Std. 5 Min.", "1:05 h". */
@Composable
private fun leftTexts(left: YtSeenMark.Left): List<String> {
    val amount = when (left) {
        is YtSeenMark.Left.Seconds -> stringResource(R.string.yt_seen_left_sec, left.s)
        is YtSeenMark.Left.Minutes -> stringResource(R.string.yt_seen_left_min, left.m)
        is YtSeenMark.Left.Hours ->
            if (left.m == 0) {
                stringResource(R.string.yt_seen_left_hour_only, left.h)
            } else {
                stringResource(R.string.yt_seen_left_hour, left.h, left.m)
            }
    }
    val compact = (left as? YtSeenMark.Left.Hours)?.takeIf { it.m != 0 }
        ?.let { stringResource(R.string.yt_seen_left_hour_compact, it.h, it.m) }
    return listOfNotNull(stringResource(R.string.yt_seen_left, amount), amount, compact)
}

private data class SeenTopElement(
    val progress: Float,
    val accent: Color,
    val texts: List<String>,
    val measurer: TextMeasurer,
    val style: TextStyle,
    val shape: Shape,
    val pillSide: YtPillSide,
    val motion: Boolean,
) : ModifierNodeElement<SeenTopNode>() {
    override fun create() = SeenTopNode(this)

    override fun update(node: SeenTopNode) = node.update(this)

    override fun InspectorInfo.inspectableProperties() {
        name = "ytSeenTop"
    }
}

private class SeenTopNode(var spec: SeenTopElement) : Modifier.Node(), DrawModifierNode {
    /** 0: the thin bar, 1: grown with the pill in. */
    private val grow = Animatable(0f)

    private var scope: YtHeldScopeNode? = null

    /** The pill's text for the current size, or null when none fits (then no pill and no fade). */
    private var pillText: TextLayoutResult? = null
    private var pillFor: Pair<Size, List<String>>? = null

    private val pill: () -> Float = { if (pillText != null) grow.value else 0f }

    fun update(next: SeenTopElement) {
        val textsChanged = next.texts != spec.texts || next.style != spec.style
        spec = next
        if (textsChanged) pillFor = null
        invalidateDraw()
    }

    override fun onAttach() {
        coroutineScope.launch {
            // the card's instance may change (a reused list item): follow it, and its held state
            var first = true
            snapshotFlow { heldScope().also { scope = it }?.isHeld == true }.collectLatest { held ->
                scope?.pill = pill
                val to = if (held) 1f else 0f
                // a card that comes back starts where it is, without an animation
                if (spec.motion && !first) grow.animateTo(to, tween(220)) else grow.snapTo(to)
                first = false
            }
        }
    }

    override fun onDetach() {
        if (scope?.pill === pill) scope?.pill = null
        scope = null
        pillFor = null
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val outline = spec.shape.takeIf { it !== RectangleShape }?.createOutline(size, layoutDirection, this)
        if (outline != null) {
            clipPath(Path().apply { addOutline(outline) }) { drawBar() }
        } else {
            drawBar()
        }
    }

    private fun DrawScope.drawBar() {
        val g = grow.value
        val thin = 3.dp.toPx()
        val thick = 9.dp.toPx()
        val bar = thin + (thick - thin) * g
        val top = size.height - bar
        val rtl = layoutDirection == LayoutDirection.Rtl
        drawRect(Color.White.copy(alpha = 0.32f), topLeft = Offset(0f, top), size = Size(size.width, bar))
        val filled = size.width * spec.progress
        drawRoundRect(
            spec.accent,
            topLeft = Offset(if (rtl) size.width - filled else 0f, top),
            size = Size(filled, bar),
            cornerRadius = CornerRadius(bar / 2f),
        )
        val text = layoutPill() ?: return
        if (g <= 0.01f) return
        // a small dark pill above the grown bar, fading with the growth
        val inset = 6.dp.toPx()
        val padH = 6.dp.toPx()
        val padV = 2.dp.toPx()
        val w = text.size.width + 2 * padH
        val h = text.size.height + 2 * padV
        val atRight = (spec.pillSide == YtPillSide.END) != rtl
        val x = if (atRight) size.width - w - inset else inset
        val y = size.height - thick - h - 4.dp.toPx()
        drawRoundRect(Color.Black.copy(alpha = 0.66f * g), Offset(x, y), Size(w, h), CornerRadius(h / 2f))
        drawText(text, topLeft = Offset(x + padH, y + padV), alpha = g)
    }

    /** Picks the longest text that fits the thumbnail (width and height), once per size. */
    private fun DrawScope.layoutPill(): TextLayoutResult? {
        val key = size to spec.texts
        if (pillFor == key) return pillText
        pillFor = key
        val padH = 6.dp.toPx()
        val padV = 2.dp.toPx()
        val inset = 6.dp.toPx()
        val layouts = spec.texts.map { spec.measurer.measure(it, spec.style, maxLines = 1, softWrap = false) }
        val available = (size.width - 2 * inset).toInt()
        val index = YtSeenMark.pickFit(layouts.map { it.size.width + (2 * padH).toInt() }, available)
        val chosen = index?.let { layouts[it] }
        // room above the grown bar, or no pill
        val room = size.height - 9.dp.toPx() - 4.dp.toPx() - 2.dp.toPx()
        pillText = chosen?.takeIf { it.size.height + 2 * padV <= room }
        return pillText
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBadge(outline: Outline, fill: Color) = drawOutline(outline, fill)
