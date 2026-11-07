package com.github.libretube.ui.yt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.github.libretube.R
import com.github.libretube.helpers.YtDesign
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * YT: the search field of a home screen (Glas, Frost, Formen) turns into the header's search
 * button as the list scrolls, so the screen never shows two searches at once. The header and
 * the screen are two ComposeViews; they meet in this object: the field reports where it is and
 * how far it is covered, and the header's search button draws the morphing shape from the
 * field's place to its own, overflowing its view downwards (YtHeader lets it). Everything is
 * read in draw, layout and graphicsLayer lambdas, so scrolling only redraws. The progress is the
 * scroll position itself, not a timed animation, so it stops and runs back with the finger.
 */
object YtSearchMorph {
    /** The designs whose home has a search field of its own. */
    val designs = setOf(YtDesign.GLASS, YtDesign.FROST, YtDesign.SHAPES)

    /** Up to here (of the progress) the shape travels; after it the header button fades in. */
    const val TRAVEL_END = 0.85f

    /** Over the first part of the progress the field itself fades and the drawn shape appears. */
    const val FADE_IN = 0.2f

    /** Where the field is now, in window pixels, or null while no screen shows one. */
    internal var fieldBounds by mutableStateOf<Rect?>(null)
        private set
    private var source by mutableStateOf<(() -> Float)?>(null)
    private var owner: Any? = null

    /** 0 while the field is fully there, 1 once the header button has taken over (or no field). */
    val progress: Float
        get() {
            if (fieldBounds == null) return 1f
            return source?.invoke()?.coerceIn(0f, 1f) ?: 0f
        }

    /** How far the shape has travelled from the field to the button, 0 to 1. */
    val travel: Float get() = (progress / TRAVEL_END).coerceIn(0f, 1f)

    /** The header button's own drawing: 0 while the field stands in for it, 1 at rest. */
    val handoff: Float
        get() = if (fieldBounds == null) 1f else ((progress - TRAVEL_END) / (1f - TRAVEL_END)).coerceIn(0f, 1f)

    internal fun claim(token: Any, rect: Rect, progress: () -> Float) {
        if (owner !== token) {
            owner = token
            source = progress
        }
        fieldBounds = rect
    }

    internal fun release(token: Any) {
        if (owner !== token) return
        owner = null
        fieldBounds = null
        source = null
    }
}

/** How far above the header the field is when the morph begins. */
private val MorphLead = 24.dp

private class FieldToken {
    /** Window y of the screen's top, which is the header's bottom edge. */
    var top = 0f

    /** Starts when the field's top is [lead] below the header and ends when it is fully under it. */
    fun covered(lead: Float): Float {
        val f = YtSearchMorph.fieldBounds ?: return 0f
        val top = f.top - top
        return ((lead - top) / (lead + f.height)).coerceIn(0f, 1f)
    }
}

/**
 * Marks a home screen's search field. Put it where the modifier's bounds are the visible field
 * (after its padding and size). By default the progress follows how far the field has scrolled
 * under the header; a field that floats in place passes its own [progress] (Glas).
 */
@Composable
fun Modifier.ytSearchMorphField(progress: (() -> Float)? = null): Modifier {
    val lead = with(LocalDensity.current) { MorphLead.toPx() }
    val token = remember { FieldToken() }
    val source = remember(progress, lead) { progress ?: { token.covered(lead) } }
    DisposableEffect(token) { onDispose { YtSearchMorph.release(token) } }
    return this
        .onGloballyPositioned { c ->
            token.top = c.findRootCoordinates().positionInWindow().y
            YtSearchMorph.claim(token, Rect(c.positionInWindow(), c.size.toSize()), source)
        }
        .graphicsLayer { alpha = 1f - (YtSearchMorph.progress / YtSearchMorph.FADE_IN).coerceIn(0f, 1f) }
}

/** True while the field is the search to tap: the first half of the morph. */
@Composable
fun rememberSearchFieldLive(): State<Boolean> = remember { derivedStateOf { YtSearchMorph.progress < 0.5f } }

/** True while the header button is the search to tap: no field, or the second half of the morph. */
@Composable
internal fun rememberSearchButtonLive(): State<Boolean> =
    remember { derivedStateOf { YtSearchMorph.fieldBounds == null || YtSearchMorph.progress >= 0.5f } }

/**
 * How the field and the header button look, the two ends of the morph. The field's side copies
 * the home screens (GlassHome, FrostHome, ShapesHome), the button's side the header's Look.
 */
internal class MorphLook(
    val fillTop: Color,
    val fillBottom: Color,
    val outline: Color,
    val outlineWidth: Dp,
    val endFillTop: Color,
    val endFillBottom: Color,
    val endOutline: Color,
    val endOutlineWidth: Dp,
    val icon: Dp,
    val endIcon: Dp,
    val tint: Color,
    val endTint: Color,
    /** Centre of the field's search icon from its left edge. */
    val iconX: Dp,
    /** Start of the field's placeholder from its left edge. */
    val textX: Dp,
    val textStyle: TextStyle,
    /** Frost: the white disc behind the field's icon. */
    val disc: Color = Color.Transparent,
    val discSize: Dp = 0.dp,
    /** The Feed-Regler circle at the field's right end (Glas, Frost). */
    val trailing: Color = Color.Transparent,
    val trailingSize: Dp = 0.dp,
    val trailingInset: Dp = 0.dp,
    val trailingGlyph: Dp = 0.dp,
    val trailingInk: Color = Color.Transparent,
    /** Lobes of the button's cookie (Formen), 0 for a circle. */
    val lobes: Int = 0,
)

private fun morphLook(design: YtDesign, palette: YtPalette): MorphLook? = when (design) {
    YtDesign.GLASS -> MorphLook(
        fillTop = ytDayNight(Color(0xB3FFFFFF), Color(0x40A8E0D6)),
        fillBottom = ytDayNight(Color(0x80FFFFFF), Color(0x33A8E0D6)),
        outline = ytDayNight(Color(0xD9FFFFFF), Color(0x4DCFEDE7)),
        outlineWidth = 1.dp,
        endFillTop = ytDayNight(Color(0x99FFFFFF), Color(0x336FB5AA)),
        endFillBottom = ytDayNight(Color(0x4DFFFFFF), Color(0x296FB5AA)),
        endOutline = ytDayNight(Color(0xCCFFFFFF), Color(0x40CFEDE7)),
        endOutlineWidth = 1.dp,
        icon = 18.dp,
        endIcon = 20.dp,
        tint = palette.text,
        endTint = palette.text,
        iconX = 25.dp,
        textX = 49.dp,
        textStyle = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 13.sp, color = palette.textSecondary),
        trailing = ytDayNight(Color(0x99FFFFFF), Color(0x336FB5AA)),
        trailingSize = 30.dp,
        trailingInset = 6.dp,
        trailingGlyph = 16.dp,
        trailingInk = palette.text,
    )
    YtDesign.FROST -> MorphLook(
        fillTop = Color(0x4DFFFFFF),
        fillBottom = Color(0x26FFFFFF),
        outline = Color(0x66FFFFFF),
        outlineWidth = 1.dp,
        endFillTop = Color(0x47FFFFFF),
        endFillBottom = Color(0x1FFFFFFF),
        endOutline = Color(0x73FFFFFF),
        endOutlineWidth = 1.dp,
        icon = 17.dp,
        endIcon = 20.dp,
        tint = Color(0xFF1F2A26),
        endTint = palette.text,
        iconX = 23.dp,
        textX = 52.dp,
        textStyle = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 14.sp, color = palette.textSecondary),
        disc = Color.White,
        discSize = 34.dp,
        trailing = Color(0x33FFFFFF),
        trailingSize = 34.dp,
        trailingInset = 6.dp,
        trailingGlyph = 17.dp,
        trailingInk = palette.text,
    )
    YtDesign.SHAPES -> MorphLook(
        fillTop = palette.surface,
        fillBottom = palette.surface,
        outline = Color.Transparent,
        outlineWidth = 0.dp,
        endFillTop = palette.lime,
        endFillBottom = palette.lime,
        endOutline = Color.Transparent,
        endOutlineWidth = 0.dp,
        icon = 18.dp,
        endIcon = 20.dp,
        tint = palette.ink,
        endTint = palette.ink,
        iconX = 23.dp,
        textX = 40.dp,
        textStyle = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 13.sp, color = palette.ink.copy(alpha = 0.7f)),
        lobes = 9,
    )
    else -> null
}

/** What the header's search button needs to draw the morph, or null for designs without it. */
internal class SearchMorphDrawer(
    private val look: MorphLook,
    private val search: Painter,
    private val tune: Painter,
    private val text: TextLayoutResult,
) {
    /** Window position of the button, where the shape ends. */
    var origin = Offset.Zero
    private val path = Path()

    /** Draws the shape between the field and the button; [turn] is the cookie's spin (Formen). */
    fun DrawScope.draw(turn: () -> Float) {
        val start = YtSearchMorph.fieldBounds ?: return
        val progress = YtSearchMorph.progress
        val alpha = (progress / YtSearchMorph.FADE_IN).coerceIn(0f, 1f) * (1f - YtSearchMorph.handoff)
        if (alpha <= 0f) return
        val t = YtSearchMorph.travel
        // the form and the colours settle softly at both ends, the place follows the scroll
        val s = t * t * (3f - 2f * t)
        val r = lerp(start.translate(-origin), Rect(Offset.Zero, size), t)
        // the spin is read only here, so the cookie's turning does not redraw an idle button
        outline(r, s, if (look.lobes > 0) turn() * s else 0f)

        drawPath(
            path,
            Brush.verticalGradient(
                listOf(lerp(look.fillTop, look.endFillTop, s), lerp(look.fillBottom, look.endFillBottom, s)),
                startY = r.top,
                endY = r.bottom,
            ),
            alpha = alpha,
        )
        val stroke = androidx.compose.ui.util.lerp(look.outlineWidth.toPx(), look.endOutlineWidth.toPx(), s)
        if (stroke > 0f) drawPath(path, lerp(look.outline, look.endOutline, s), alpha = alpha, style = Stroke(stroke))

        // the placeholder and the Feed-Regler leave first, inside the shrinking shape
        val leave = (1f - t / 0.4f).coerceIn(0f, 1f)
        if (leave > 0f) {
            clipPath(path) {
                drawText(text, topLeft = Offset(r.left + look.textX.toPx(), r.center.y - text.size.height / 2f), alpha = alpha * leave)
                if (look.trailingSize > 0.dp) {
                    val d = look.trailingSize.toPx() * (0.6f + 0.4f * leave)
                    val c = Offset(r.right - look.trailingInset.toPx() - look.trailingSize.toPx() / 2f, r.center.y)
                    drawCircle(look.trailing, d / 2f, c, alpha = alpha * leave)
                    val g = look.trailingGlyph.toPx() * (0.6f + 0.4f * leave)
                    translate(c.x - g / 2f, c.y - g / 2f) {
                        with(tune) { draw(Size(g, g), alpha * leave, ColorFilter.tint(look.trailingInk)) }
                    }
                }
            }
        }

        val iconCentre = Offset(r.left + androidx.compose.ui.util.lerp(look.iconX.toPx(), r.width / 2f, s), r.center.y)
        if (look.discSize > 0.dp) {
            val fade = (1f - t / 0.6f).coerceIn(0f, 1f)
            if (fade > 0f) drawCircle(look.disc, look.discSize.toPx() / 2f, iconCentre, alpha = alpha * fade)
        }
        val icon = androidx.compose.ui.util.lerp(look.icon.toPx(), look.endIcon.toPx(), s)
        translate(iconCentre.x - icon / 2f, iconCentre.y - icon / 2f) {
            with(search) { draw(Size(icon, icon), alpha, ColorFilter.tint(lerp(look.tint, look.endTint, s))) }
        }
    }

    /** The field's stadium turning into the button's circle, or into its cookie for Formen. */
    private fun outline(r: Rect, s: Float, turnDegrees: Float) {
        path.reset()
        if (look.lobes == 0) {
            path.addRoundRect(RoundRect(r, CornerRadius(min(r.width, r.height) / 2f)))
            return
        }
        // both outlines sampled along the same rays from the centre and blended per ray
        val a = r.width / 2f
        val b = r.height / 2f
        val radius = min(a, b)
        val depth = 0.07f
        val turn = turnDegrees / 180f * PI.toFloat()
        val steps = look.lobes * 16
        for (i in 0..steps) {
            val angle = 2f * PI.toFloat() * i / steps
            val dx = cos(angle - PI.toFloat() / 2f)
            val dy = sin(angle - PI.toFloat() / 2f)
            val pill = stadiumRay(dx, dy, a, b)
            val cookie = radius * (1f - depth + depth * cos(look.lobes * (angle - turn)))
            val d = pill + (cookie - pill) * s
            val x = r.center.x + d * dx
            val y = r.center.y + d * dy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }
}

/** Distance from the centre of a stadium with half sizes [a] and [b] to its edge along (dx, dy). */
internal fun stadiumRay(dx: Float, dy: Float, a: Float, b: Float): Float {
    val wide = a >= b
    val along = abs(if (wide) dx else dy)
    val across = abs(if (wide) dy else dx)
    val radius = min(a, b)
    val straight = max(a, b) - radius
    if (across > 0f && radius * along / across <= straight) return radius / across
    return straight * along + sqrt(max(0f, radius * radius - straight * straight * across * across))
}

/** The morph of the header's search button for [design], or null when its home has no field. */
@Composable
internal fun rememberSearchMorph(design: YtDesign, palette: YtPalette): SearchMorphDrawer? {
    val look = remember(design, palette) { morphLook(design, palette) } ?: return null
    val search = painterResource(R.drawable.ic_yt_search)
    val tune = painterResource(R.drawable.ic_yt_tune)
    val measurer = rememberTextMeasurer()
    val label = stringResource(R.string.yt_search_youtube)
    val text = remember(measurer, label, look) { measurer.measure(label, look.textStyle, maxLines = 1, softWrap = false) }
    return remember(look, search, tune, text) { SearchMorphDrawer(look, search, tune, text) }
}

/** Draws [drawer]'s morph behind the header search button; [turn] is the cookie's spin. */
internal fun Modifier.searchMorph(drawer: SearchMorphDrawer, turn: () -> Float): Modifier = this
    .onGloballyPositioned { drawer.origin = it.positionInWindow() }
    .drawBehind { with(drawer) { draw(turn) } }
