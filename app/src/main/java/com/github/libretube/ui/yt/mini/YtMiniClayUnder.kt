package com.github.libretube.ui.yt.mini

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtDock
import com.github.libretube.ui.yt.YtDrag
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtMotionPrefs
import com.github.libretube.ui.yt.ytDayNight
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * YT: what the Clay player ("Knetkugel") draws behind its picture: the ball of dough on the bar
 * (sheen, warm shadow, the crease where it is pressed onto the blobs, the dent the picture sits
 * in), title and channel, the mauve strand of progress, the breathing play blob; the stretched
 * dough of the long press (head, neck, the small ball of the next video, the play pill); the
 * dent of a dragged video, its ripple and the label of a drop. Every moving value (minimize
 * progress, preview, hover, the playback position) is read while drawing or placing, so the
 * player draws again but does not compose again while it moves.
 */

/** The colours and type of the Clay player (the Clay home: cream dough, mauve, the bar's blue). */
internal object ClayStyle {
    val CREAM: Color get() = ytDayNight(Color(0xFFF3E1D2), Color(0xFF4A3830))
    val CREAM_TOP: Color get() = ytDayNight(Color(0xFFFBEDE2), Color(0xFF574239))
    val CREAM_FOOT: Color get() = ytDayNight(Color(0xFFEAD2C0), Color(0xFF3A2C26))
    val BLUE: Color get() = ytDayNight(Color(0xFFB7CBE4), Color(0xFF3C4A5C))
    val BLUE_TOP: Color get() = ytDayNight(Color(0xFFD3E0F0), Color(0xFF4A5A6E))
    val BLUE_FOOT: Color get() = ytDayNight(Color(0xFFA6BCD9), Color(0xFF313D4D))
    val MAUVE = Color(0xFFA8696F)
    val MAUVE_TOP = Color(0xFFC4888C)
    val MAUVE_DEEP: Color get() = ytDayNight(Color(0xFF8E5560), Color(0xFFD9A5A6))
    val INK: Color get() = ytDayNight(Color(0xFF5C5260), Color(0xFFEEDFD3))
    val MUTED: Color get() = ytDayNight(Color(0xFF8C7880), Color(0xFFC9B3AA))
    val SHADOW: Color get() = ytDayNight(Color(0xFFB07A66), Color(0xFF000000))
    val TRACK: Color get() = ytDayNight(Color(0x1F7A5560), Color(0x26EEDFD3))
    val DENT: Color get() = ytDayNight(Color(0x2E7A5560), Color(0x40000000))
    val SHEEN = Color.White

    val title: TextStyle get() = TextStyle(color = INK, fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    val channel: TextStyle get() = TextStyle(color = MUTED, fontFamily = YtFonts.nunito, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
    val peekTitle: TextStyle get() = TextStyle(color = INK, fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp, lineHeight = 16.5.sp)
    val nextLabel: TextStyle get() = TextStyle(color = MAUVE_DEEP, fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 9.5.sp, letterSpacing = 0.06.em)
    val nextTitle: TextStyle get() = TextStyle(color = INK, fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp)
    val onMauve: TextStyle get() = TextStyle(color = Color.White, fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
}

/** The geometry for the box's current width, computed again only when the width changes. */
private class ClaySpace(private val design: YtMiniDesign, private val density: Float) {
    private var restWidth = -1f
    private var restValue: YtMiniGeometry? = null
    private var peekWidth = -1f
    private var peekNext = false
    private var peekValue: YtMiniClayPeek? = null

    /** Where the last dragged video pressed its dent (px of the box), for the drop's ripple. */
    var dentX = Float.NaN

    /** The next thumbnail grows with its ball from the neck's foot: that point, as shares of it. */
    var thumbPivotX = 0.5f
    var thumbPivotY = 1f

    // the paths drawn every frame, reused
    val base = Path()
    val neck = Path()
    val head = Path()
    val nextNeck = Path()
    val nextBall = Path()
    val blob = Path()

    fun rest(width: Float): YtMiniGeometry {
        val cached = restValue
        if (cached != null && width == restWidth) return cached
        return design.geometry(width, density).also {
            restValue = it
            restWidth = width
        }
    }

    fun peek(width: Float, hasNext: Boolean): YtMiniClayPeek {
        val cached = peekValue
        if (cached != null && width == peekWidth && hasNext == peekNext) return cached
        return YtMiniClayLayout.peek(design.spec, width, density, hasNext).also {
            peekValue = it
            peekWidth = width
            peekNext = hasNext
        }
    }
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/** The handles of a puffy corner: a little fuller than a circle's quarter. */
private const val PUFF = 0.4f

/**
 * A ball of dough from [left] to [bottom]: round corners of [top] at its top and [foot] at its
 * foot, the top edge moved down by [edge] (a dent, a ripple; null for a straight edge), sampled
 * every [step] px and faded out towards the corners so they stay round.
 */
private fun Path.dough(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    topRadius: Float,
    footRadius: Float,
    edge: ((Float) -> Float)? = null,
    step: Float = 6f,
) {
    reset()
    val w = right - left
    val h = bottom - top
    if (w <= 1f || h <= 1f) return
    val rt = min(topRadius, min(w / 2f, h / 2f))
    val rb = min(footRadius, min(w / 2f, h - rt))
    moveTo(left, top + rt)
    cubicTo(left, top + rt * PUFF, left + rt * PUFF, top, left + rt, top)
    if (edge != null && right - rt > left + rt) {
        val from = left + rt
        val to = right - rt
        val fade = min(18f * step / 6f, (to - from) / 2f)
        var x = from
        while (x < to) {
            val taper = YtMiniClayLayout.smooth(x, from, from + fade) * YtMiniClayLayout.smooth(to - x, 0f, fade)
            lineTo(x, top + edge(x) * taper)
            x += step
        }
    }
    lineTo(right - rt, top)
    cubicTo(right - rt * PUFF, top, right, top + rt * PUFF, right, top + rt)
    lineTo(right, bottom - rb)
    cubicTo(right, bottom - rb * PUFF, right - rb * PUFF, bottom, right - rb, bottom)
    lineTo(left + rb, bottom)
    cubicTo(left + rb * PUFF, bottom, left, bottom - rb * PUFF, left, bottom - rb)
    close()
}

/**
 * A soft blob in [left]..[bottom] whose four corners round by the given shares of its smaller
 * side (as the blobs of the Clay bar); all 0.5 is a stadium.
 */
internal fun Path.clayBlob(left: Float, top: Float, right: Float, bottom: Float, ts: Float, te: Float, be: Float, bs: Float) {
    reset()
    val w = right - left
    val h = bottom - top
    if (w <= 0.5f || h <= 0.5f) return
    val r = min(w, h)
    val c1 = r * ts
    val c2 = r * te
    val c3 = r * be
    val c4 = r * bs
    moveTo(left + c1, top)
    lineTo(right - c2, top)
    cubicTo(right - c2 * 0.35f, top, right, top + c2 * 0.35f, right, top + c2)
    lineTo(right, bottom - c3)
    cubicTo(right, bottom - c3 * 0.35f, right - c3 * 0.35f, bottom, right - c3, bottom)
    lineTo(left + c4, bottom)
    cubicTo(left + c4 * 0.35f, bottom, left, bottom - c4 * 0.35f, left, bottom - c4)
    lineTo(left, top + c1)
    cubicTo(left, top + c1 * 0.35f, left + c1 * 0.35f, top, left + c1, top)
    close()
}

/**
 * A neck of dough from [top] (inside the ball above, [topHalf] wide on either side of [cx]) down
 * to [bottom] (inside the body, [footHalf]), pulled in to [waistHalf] between them.
 */
private fun Path.neck(cx: Float, top: Float, bottom: Float, topHalf: Float, waistHalf: Float, footHalf: Float) {
    reset()
    val l = bottom - top
    if (l <= 0.5f) return
    moveTo(cx - topHalf, top)
    cubicTo(cx - waistHalf, top + l * 0.5f, cx - waistHalf, bottom - l * 0.5f, cx - footHalf, bottom)
    lineTo(cx + footHalf, bottom)
    cubicTo(cx + waistHalf, bottom - l * 0.5f, cx + waistHalf, top + l * 0.5f, cx + topHalf, top)
    close()
}

/** ▶ while it plays, ❚❚ while paused, drawn around [center] with [radius]. */
internal fun DrawScope.clayGlyph(playing: Boolean, color: Color, center: Offset, radius: Float) {
    if (playing) {
        val path = Path().apply {
            moveTo(center.x - radius * 0.7f, center.y - radius)
            lineTo(center.x + radius, center.y)
            lineTo(center.x - radius * 0.7f, center.y + radius)
            close()
        }
        drawPath(path, color)
    } else {
        val bar = radius * 0.62f
        val corner = androidx.compose.ui.geometry.CornerRadius(bar / 2f)
        drawRoundRect(color, Offset(center.x - radius * 0.85f, center.y - radius), androidx.compose.ui.geometry.Size(bar, radius * 2f), corner)
        drawRoundRect(color, Offset(center.x + radius * 0.85f - bar, center.y - radius), androidx.compose.ui.geometry.Size(bar, radius * 2f), corner)
    }
}

/** The shadow of the dough: two soft offset copies in the warm shadow colour. */
private fun DrawScope.doughShadow(path: Path, strength: Float = 1f) {
    val d = density
    translate(0f, 2.5f * d) { drawPath(path, ClayStyle.SHADOW.copy(alpha = 0.2f * strength)) }
    translate(0f, 6f * d) { drawPath(path, ClayStyle.SHADOW.copy(alpha = 0.09f * strength)) }
}

/** The light on the dough: a rim that fades out down the ball from its top, [from] to [to]. */
private fun DrawScope.doughRim(path: Path, from: Float, to: Float, alpha: Float = 0.8f) {
    drawPath(
        path,
        Brush.verticalGradient(listOf(ClayStyle.SHEEN.copy(alpha = alpha), Color.Transparent), startY = from, endY = to),
        style = Stroke(1.5f * density),
    )
}

/** A soft round sheen on a ball, up and to the left of its middle. */
private fun DrawScope.doughSheen(box: YtMiniBox, alpha: Float) {
    val c = Offset(box.left + box.width * 0.3f, box.top + box.height * 0.26f)
    val r = min(box.width, box.height) * 0.42f
    if (r <= 0f) return
    drawCircle(Brush.radialGradient(listOf(ClayStyle.SHEEN.copy(alpha = alpha), Color.Transparent), center = c, radius = r), r, c)
}

private fun TextMeasurer.line(text: String, style: TextStyle, width: Float, lines: Int = 1): TextLayoutResult =
    measure(
        text = text,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = lines,
        constraints = Constraints(maxWidth = width.roundToInt().coerceAtLeast(1)),
    )

/** Where a line of [layout] starts in [box]: at its left, or right to left at its right. */
private fun startX(box: YtMiniBox, layout: TextLayoutResult, rtl: Boolean): Float =
    if (rtl) box.right - layout.size.width else box.left

@Composable
internal fun ClayUnder(scene: YtMiniScene, design: YtMiniDesign) {
    if (scene.palette == null) return
    val density = LocalDensity.current.density
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spec = design.spec
    val space = remember(design, density) { ClaySpace(design, density) }
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val motion = LocalYtMotion.current
    // the docked box's height as the constraints give it (whole px); the box grows from its foot
    val restBox = ((spec.height + spec.overlap) * density).roundToInt().toFloat()

    // the play blob breathes only while it can be seen: docked and playing
    val info = scene.info
    val live = motion && YtMotionPrefs.enabled && info.playing && YtDock.docked
    val breath: State<Float>? = if (live) {
        rememberInfiniteTransition(label = "clayBreath").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "clayBreathValue",
        )
    } else {
        null
    }

    // after the landing the play blob pops back up out of the squashed dough
    val pop = remember { Animatable(1f) }
    val landings = scene.landings
    LaunchedEffect(landings) {
        if (landings > 0 && motion) {
            pop.snapTo(0f)
            delay(90)
            pop.animateTo(1f, tween(460, easing = LinearEasing))
        }
    }

    // a taken drop: 0 to 1 over the whole gesture, 1 when nothing shows
    val drop = remember { Animatable(1f) }
    val accepted = YtDock.accepted
    var seenAccepted by remember { mutableIntStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted == seenAccepted) return@LaunchedEffect
        seenAccepted = accepted
        if (motion) {
            drop.snapTo(0f)
            drop.animateTo(1f, tween(YtMiniClayLayout.DROP_MS, easing = LinearEasing))
        } else {
            drop.snapTo(YtMiniClayLayout.DROP_STILL)
            delay(1400)
            drop.snapTo(1f)
        }
    }
    // a refused drop: the dent springs back out (motion only)
    val spit = remember { Animatable(1f) }
    val rejects = scene.rejects
    var seenRejects by remember { mutableIntStateOf(rejects) }
    LaunchedEffect(rejects) {
        if (rejects == seenRejects) return@LaunchedEffect
        seenRejects = rejects
        if (motion) {
            spit.snapTo(0f)
            spit.animateTo(1f, tween(460, easing = LinearEasing))
        }
    }

    val last = YtDock.lastDrop
    val dropLabel = when {
        last == null -> null
        last.next -> stringResource(R.string.yt_mini_drop_next)
        else -> stringResource(R.string.yt_mini_drop_end, last.position)
    }
    val playingLabel = stringResource(R.string.yt_mini_peek_playing)
    val pausedLabel = stringResource(R.string.yt_mini_peek_paused)
    val nextLabel = stringResource(R.string.yt_mini_peek_next).uppercase()
    val next = scene.next
    val hasPeek = scene.peekLayout != null
    val target = scene.target

    fun mirror(box: YtMiniBox, width: Float) = if (rtl) box.mirror(width) else box

    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {},
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val d = density
            val w = size.width
            val h = size.height
            val g = space.rest(w)
            val peek = scene.peek()
            val card = if (hasPeek) space.peek(w, next != null) else null
            val grown = card?.geometry?.boxHeight ?: restBox
            // how far the box has grown, from its own height: the views follow the same
            // interpolation, so the dough stays around the picture whatever the curve
            val s = if (card != null && grown > restBox + 1f) ((h - restBox) / (grown - restBox)).coerceIn(0f, 1f) else 0f
            fun restY(y: Float) = h - (restBox - y)
            fun peekY(y: Float) = h - (grown - y)
            // while minimizing the dough fills the box; in the preview the body stays at the foot
            val baseTop = if (card != null) h - restBox else 0f
            val joint = h - spec.overlap * d

            // the dent of a dragged video, the gulp and ripple of a drop, the spit of a refusal
            val near = if (card == null) scene.hover() else 0f
            if (near > 0f) {
                val pointer = YtDrag.session?.pointer
                val bounds = YtDrag.miniBounds
                if (pointer != null && bounds != null) space.dentX = pointer.x - bounds.left
            }
            val dt = drop.value
            val dropping = dt > 0f && dt < 1f && card == null
            val st = spit.value
            val spitting = st > 0f && st < 1f && card == null
            val dentX = (if (space.dentX.isNaN()) w / 2f else space.dentX).coerceIn(YtMiniClayLayout.BODY_TOP * d, max(w - YtMiniClayLayout.BODY_TOP * d, YtMiniClayLayout.BODY_TOP * d))
            var depth = YtMiniClayLayout.DENT * d * max(near, if (dropping) YtMiniClayLayout.gulp(dt) else 0f)
            if (spitting) depth -= 0.8f * YtMiniClayLayout.DENT * d * YtMiniDesign.wobble(st, 1.6f, 4.5f)
            val rippleT = if (dropping && motion) YtMiniClayLayout.rippleTime(dt) else 0f
            val dentWidth = YtMiniClayLayout.DENT_WIDTH * d
            val edge: ((Float) -> Float)? = if (depth != 0f || (rippleT > 0f && rippleT < 1f)) {
                { x ->
                    depth * YtMiniClayLayout.dent(x - dentX, dentWidth) +
                        4f * d * YtMiniClayLayout.ripple(x - dentX, rippleT, w, 46f * d)
                }
            } else {
                null
            }

            // 1: the body, pressed onto the bar's blobs
            val base = space.base
            base.dough(0f, baseTop, w, h, YtMiniClayLayout.BODY_TOP * d, YtMiniClayLayout.BODY_FOOT * d, edge, 3f * d)
            doughShadow(base)
            drawPath(
                base,
                Brush.verticalGradient(listOf(ClayStyle.CREAM_TOP, ClayStyle.CREAM, ClayStyle.CREAM_FOOT), startY = baseTop, endY = h),
            )
            doughRim(base, baseTop, baseTop + (h - baseTop) * 0.5f)
            // a long soft sheen along the top of the body
            drawRoundRect(
                Brush.verticalGradient(listOf(ClayStyle.SHEEN.copy(alpha = 0.32f), Color.Transparent), startY = baseTop + 4f * d, endY = baseTop + 16f * d),
                topLeft = Offset(YtMiniClayLayout.BODY_TOP * d, baseTop + 4f * d),
                size = androidx.compose.ui.geometry.Size(max(w - 2f * YtMiniClayLayout.BODY_TOP * d, 0f), 12f * d),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f * d),
            )
            // the crease where the dough is pressed onto the blobs, and the lip under it
            if (joint > baseTop + 20f * d) {
                val inset = YtMiniClayLayout.BODY_FOOT * d * 0.6f
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, ClayStyle.SHADOW.copy(alpha = 0.2f)), startY = joint - 7f * d, endY = joint),
                    topLeft = Offset(inset, joint - 7f * d),
                    size = androidx.compose.ui.geometry.Size(max(w - 2f * inset, 0f), 7f * d),
                )
                drawLine(ClayStyle.SHEEN.copy(alpha = 0.35f), Offset(inset + 4f * d, joint + 1.5f * d), Offset(w - inset - 4f * d, joint + 1.5f * d), d, StrokeCap.Round)
            }
            // a dragged video coming near: a mauve glow on the dough's rim
            if (near > 0f) drawPath(base, ClayStyle.MAUVE.copy(alpha = 0.55f * near), style = Stroke(2f * d))

            // the picture, pressed into the dough: where it sits now (the views move it)
            val restPicture = mirror(g.player, w)
            val peekPicture = card?.let { mirror(it.geometry.player, w) }
            val pictureLeft = lerpF(restPicture.left, peekPicture?.left ?: restPicture.left, s)
            val pictureRight = lerpF(restPicture.right, peekPicture?.right ?: restPicture.right, s)
            val pictureTop = h - lerpF(restBox - restPicture.top, grown - (peekPicture?.top ?: 0f), s)
            val pictureBottom = h - lerpF(restBox - restPicture.bottom, grown - (peekPicture?.bottom ?: 0f), s)
            val restTop = if (card != null) pictureTop else restY(restPicture.top)
            val restBottom = if (card != null) pictureBottom else restY(restPicture.bottom)

            // 2: the stretched dough: the head around the picture on its neck out of the body
            if (card != null && s > 0f) {
                val puff = YtMiniClayLayout.puff(peek)
                val pad = YtMiniClayLayout.HEAD_PAD * d
                val head = YtMiniBox(
                    pictureLeft - pad - 4f * d * puff,
                    pictureTop - pad - 6f * d * puff,
                    pictureRight + pad + 4f * d * puff,
                    pictureBottom + pad,
                )
                val corner = min(YtMiniClayLayout.HEAD_CORNER * d, min(head.width, head.height) / 2f)
                val neckTop = head.bottom - corner * 0.5f
                val neckBottom = baseTop + YtMiniClayLayout.BODY_TOP * d * 0.5f
                val half = head.width / 2f
                space.neck.neck(
                    head.centerX, neckTop, neckBottom,
                    topHalf = half - corner * 0.3f,
                    waistHalf = half * YtMiniClayLayout.waist(s),
                    footHalf = half * 1.08f,
                )
                space.head.dough(head.left, head.top, head.right, head.bottom, corner, corner)
                // its light and shadow come as it rises out of the body, not at once
                val risen = YtMiniClayLayout.smooth(s, 0f, 0.35f)
                doughShadow(space.head, 0.8f * risen)
                drawPath(
                    space.neck,
                    Brush.verticalGradient(listOf(ClayStyle.CREAM, ClayStyle.CREAM_TOP), startY = neckTop, endY = neckBottom),
                )
                drawPath(
                    space.head,
                    Brush.verticalGradient(listOf(ClayStyle.CREAM_TOP, ClayStyle.CREAM), startY = head.top, endY = head.bottom),
                )
                doughRim(space.head, head.top, head.top + head.height * 0.55f, 0.8f * risen)
                doughSheen(head, 0.38f * risen)
            }

            // the dent the picture sits in: a darker rim around it and a light lip under it; it
            // comes as the picture arrives on the bar
            val settled = YtMiniClayLayout.smooth(scene.progress(), 0.85f, 1f)
            if (settled > 0f) {
                val ring = 2.5f * d
                val radius = androidx.compose.ui.geometry.CornerRadius((spec.thumbRadius + 2.5f) * d * scene.progress().coerceIn(0f, 1f))
                drawRoundRect(
                    ClayStyle.DENT,
                    topLeft = Offset(pictureLeft - ring, restTop - ring),
                    size = androidx.compose.ui.geometry.Size(pictureRight - pictureLeft + 2f * ring, restBottom - restTop + 2f * ring),
                    cornerRadius = radius,
                    alpha = settled,
                )
                drawRoundRect(
                    ClayStyle.SHEEN.copy(alpha = 0.55f),
                    topLeft = Offset(pictureLeft - ring, restTop - ring + 1.5f * d),
                    size = androidx.compose.ui.geometry.Size(pictureRight - pictureLeft + 2f * ring, restBottom - restTop + 2f * ring),
                    cornerRadius = radius,
                    style = Stroke(1.2f * d),
                    alpha = 0.6f * settled,
                )
            }

            // 3: the docked row: title, channel and the mauve strand; it gives way as the dough
            // stretches and under the label of a drop
            val labelShown = if (dropping || dt == YtMiniClayLayout.DROP_STILL) YtMiniClayLayout.label(dt) else 0f
            val restAlpha = (if (card != null) YtMiniParts.restFade(s) else 1f) * (1f - labelShown.coerceIn(0f, 1f)) *
                YtMiniClayLayout.smooth(scene.progress(), 0.6f, 1f)
            if (restAlpha > 0f && g.text.width > 1f) {
                val box = mirror(g.text, w)
                val title = measurer.line(info.title, ClayStyle.title, box.width)
                val channel = if (info.channel.isNotBlank()) measurer.line(info.channel, ClayStyle.channel, box.width) else null
                val strand = 4f * d
                val gap = 6f * d
                val total = title.size.height + (channel?.size?.height ?: 0) + gap + strand
                var y = restY((g.content - total) / 2f)
                drawText(title, topLeft = Offset(startX(box, title, rtl), y), alpha = restAlpha)
                y += title.size.height
                channel?.let {
                    drawText(it, topLeft = Offset(startX(box, it, rtl), y), alpha = restAlpha)
                    y += it.size.height
                }
                y += gap + strand / 2f
                // the strand of dough: the track, the played part with a light top and a knob
                val from = if (rtl) box.right else box.left
                val to = if (rtl) box.left else box.right
                val at = lerpF(from, to, info.fraction)
                drawLine(ClayStyle.TRACK, Offset(from, y), Offset(to, y), strand, StrokeCap.Round, alpha = restAlpha)
                if (info.fraction > 0f) {
                    drawLine(ClayStyle.MAUVE, Offset(from, y), Offset(at, y), strand, StrokeCap.Round, alpha = restAlpha)
                    drawLine(ClayStyle.MAUVE_TOP, Offset(from, y - strand * 0.22f), Offset(at, y - strand * 0.22f), strand * 0.3f, StrokeCap.Round, alpha = restAlpha)
                }
                drawCircle(ClayStyle.MAUVE_TOP, strand * 0.85f, Offset(at, y), alpha = restAlpha)
                drawCircle(ClayStyle.SHEEN, strand * 0.3f, Offset(at - strand * 0.25f, y - strand * 0.3f), alpha = 0.7f * restAlpha)
            }

            // 4: the mauve blob behind play; in the preview it stretches into the play pill
            g.play?.let { play ->
                val restPlay = mirror(play, w)
                val b = breath?.value ?: 0f
                val popped = YtMiniClayLayout.backOut(pop.value, 2.2f)
                val size0 = YtMiniClayLayout.BLOB * d * (0.7f + 0.3f * popped)
                val cx0 = restPlay.centerX
                val cy0 = restY(restPlay.centerY)
                var left = cx0 - size0 / 2f * (1f + 0.05f * b)
                var right = cx0 + size0 / 2f * (1f + 0.05f * b)
                var top = cy0 - size0 / 2f * (1f - 0.03f * b)
                var bottom = cy0 + size0 / 2f * (1f - 0.03f * b)
                var round = 0f
                if (card != null) {
                    val pill = mirror(card.geometry.pill, w)
                    val held = if (target == YtMiniTarget.PILL) 1.06f else 1f
                    val ph = pill.height * held
                    val pw = pill.width * held
                    val pcx = pill.centerX
                    val pcy = peekY(pill.centerY)
                    left = lerpF(left, pcx - pw / 2f, s)
                    right = lerpF(right, pcx + pw / 2f, s)
                    top = lerpF(top, pcy - ph / 2f, s)
                    bottom = lerpF(bottom, pcy + ph / 2f, s)
                    round = s
                }
                val blob = space.blob
                blob.clayBlob(
                    left, top, right, bottom,
                    lerpF(0.55f + 0.05f * b, 0.5f, round),
                    lerpF(0.45f - 0.04f * b, 0.5f, round),
                    lerpF(0.5f + 0.05f * b, 0.5f, round),
                    lerpF(0.6f - 0.05f * b, 0.5f, round),
                )
                doughShadow(blob, 0.7f)
                val deep = if (target == YtMiniTarget.PILL) ClayStyle.MAUVE_DEEP else ClayStyle.MAUVE
                drawPath(blob, Brush.verticalGradient(listOf(ClayStyle.MAUVE_TOP, deep), startY = top, endY = bottom))
                doughRim(blob, top, top + (bottom - top) * 0.6f, 0.55f)
                doughSheen(YtMiniBox(left, top, right, bottom), 0.3f)

                // the pill's glyph and its word
                if (card != null) {
                    val shown = YtMiniParts.peekFade(s)
                    if (shown > 0f) {
                        val playing = info.playing
                        val glyph = 6f * d
                        val text = measurer.line(if (playing) playingLabel else pausedLabel, ClayStyle.onMauve, max(right - left - 48f * d, 1f))
                        val group = 2f * glyph + 7f * d + text.size.width
                        val gx = (left + right) / 2f - group / 2f
                        val cy = (top + bottom) / 2f
                        val glyphX = if (rtl) gx + group - glyph else gx + glyph
                        val textX = if (rtl) gx else gx + 2f * glyph + 7f * d
                        clayGlyph(playing, Color.White.copy(alpha = shown), Offset(glyphX, cy), glyph)
                        drawText(text, topLeft = Offset(textX, cy - text.size.height / 2f), alpha = shown)
                    }
                }
            }

            // 5: the preview's title in the body, where the docked row was
            if (card != null) {
                val shown = YtMiniParts.peekFade(s)
                if (shown > 0f) {
                    val box = mirror(card.geometry.title, w)
                    val lift = 6f * d * (1f - shown)
                    val channel = if (info.channel.isNotBlank()) measurer.line(info.channel, ClayStyle.channel, box.width) else null
                    val title = measurer.line(info.title, ClayStyle.peekTitle, box.width, lines = 2)
                    val total = title.size.height + (channel?.size?.height ?: 0) + 1f * d
                    var y = peekY(box.top) + (spec.height * d - total) / 2f + lift
                    channel?.let {
                        drawText(it, topLeft = Offset(startX(box, it, rtl), y), alpha = shown)
                        y += it.size.height + 1f * d
                    }
                    drawText(title, topLeft = Offset(startX(box, title, rtl), y), alpha = shown)
                }
            }

            // 6: the next video, a small ball of the bar's blue dough squeezed out beside the head
            val ball = card?.geometry?.next
            if (card != null && ball != null && next != null) {
                val q = YtMiniClayLayout.squeeze(s)
                if (q > 0f) {
                    val box = mirror(ball, w)
                    val held = target == YtMiniTarget.NEXT
                    val top = peekY(box.top)
                    val bottom = peekY(box.bottom)
                    val pivot = Offset(box.centerX, baseTop)
                    val grow = q * if (held) 1.05f else 1f
                    scale(grow, grow, pivot) {
                        val corner = min(YtMiniClayLayout.NEXT_CORNER * d, min(box.width, bottom - top) / 2f)
                        val half = box.width / 2f
                        space.nextNeck.neck(
                            box.centerX, bottom - corner * 0.5f, baseTop + YtMiniClayLayout.BODY_TOP * d * 0.5f,
                            topHalf = half - corner * 0.4f,
                            waistHalf = half * YtMiniClayLayout.waist(s) * 0.8f,
                            footHalf = half * 0.95f,
                        )
                        space.nextBall.dough(box.left, top, box.right, bottom, corner, corner)
                        doughShadow(space.nextBall, 0.7f)
                        drawPath(space.nextNeck, Brush.verticalGradient(listOf(ClayStyle.BLUE_FOOT, ClayStyle.CREAM_TOP), startY = bottom - corner, endY = baseTop + 8f * d))
                        val top0 = if (held) lerp(ClayStyle.BLUE_TOP, ClayStyle.MAUVE_TOP, 0.35f) else ClayStyle.BLUE_TOP
                        drawPath(space.nextBall, Brush.verticalGradient(listOf(top0, ClayStyle.BLUE, ClayStyle.BLUE_FOOT), startY = top, endY = bottom))
                        doughRim(space.nextBall, top, top + (bottom - top) * 0.55f)
                        if (held) drawPath(space.nextBall, ClayStyle.MAUVE, style = Stroke(2f * d))
                        val textBox = card.nextText?.let { mirror(it, w) }
                        val shown = YtMiniClayLayout.smooth(q, 0.6f, 1f)
                        if (textBox != null && shown > 0f) {
                            val label = measurer.line(nextLabel, ClayStyle.nextLabel, textBox.width)
                            val title = measurer.line(next.title, ClayStyle.nextTitle, textBox.width)
                            val y = peekY(textBox.top)
                            drawText(label, topLeft = Offset(startX(textBox, label, rtl), y), alpha = shown)
                            drawText(title, topLeft = Offset(startX(textBox, title, rtl), y + label.size.height), alpha = shown)
                        }
                    }
                }
            }

            // 7: the label of a drop: a mauve blob that pops up over the title
            if (dropLabel != null && labelShown > 0f && card == null && g.text.width > 1f) {
                val box = mirror(g.text, w)
                val text = measurer.line(dropLabel, ClayStyle.onMauve, max(box.width - 24f * d, 1f))
                val bw = text.size.width + 24f * d
                val bh = text.size.height + 12f * d
                val cx = box.centerX
                val cy = restY(g.content / 2f)
                val k = labelShown
                scale(k, k, Offset(cx, cy)) {
                    val blob = space.blob
                    blob.clayBlob(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f, 0.55f, 0.45f, 0.5f, 0.6f)
                    doughShadow(blob, 0.7f)
                    drawPath(blob, Brush.verticalGradient(listOf(ClayStyle.MAUVE_TOP, ClayStyle.MAUVE), startY = cy - bh / 2f, endY = cy + bh / 2f))
                    doughRim(blob, cy - bh / 2f, cy + bh * 0.1f, 0.55f)
                    drawText(text, topLeft = Offset(cx - text.size.width / 2f, cy - text.size.height / 2f))
                }
            }
        }

        // the next video's thumbnail in its ball, squeezed out with it from the ball's foot
        if (hasPeek && next != null) {
            val thumbShape = remember { RoundedCornerShape(14.dp) }
            val held = target == YtMiniTarget.NEXT
            AsyncImage(
                model = next.thumbnail,
                contentDescription = null,
                imageLoader = ImageHelper.loader(),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val width = constraints.maxWidth
                        val height = constraints.maxHeight
                        val card = space.peek(width.toFloat(), true)
                        val thumb = card.nextThumb?.let { mirror(it, width.toFloat()) }
                        val ball = card.geometry.next?.let { mirror(it, width.toFloat()) }
                        if (thumb == null || ball == null) {
                            val none = measurable.measure(Constraints.fixed(0, 0))
                            return@layout layout(width, height) { none.place(-10000, 0) }
                        }
                        val placeable = measurable.measure(
                            Constraints.fixed(thumb.width.roundToInt().coerceAtLeast(1), thumb.height.roundToInt().coerceAtLeast(1)),
                        )
                        // the ball grows from its neck's foot on the body: the thumbnail with it
                        space.thumbPivotX = (ball.centerX - thumb.left) / thumb.width.coerceAtLeast(1f)
                        space.thumbPivotY = (card.base.top - thumb.top) / thumb.height.coerceAtLeast(1f)
                        layout(width, height) {
                            placeable.place(thumb.left.roundToInt(), (height - (card.geometry.boxHeight - thumb.top)).roundToInt())
                        }
                    }
                    .graphicsLayer {
                        val q = YtMiniClayLayout.squeeze(scene.peek().coerceIn(0f, 1f)) * if (held) 1.05f else 1f
                        alpha = if (q <= 0.01f) 0f else YtMiniClayLayout.smooth(q, 0.3f, 0.8f)
                        scaleX = q
                        scaleY = q
                        transformOrigin = TransformOrigin(space.thumbPivotX, space.thumbPivotY)
                    }
                    .clip(thumbShape)
                    .background(ClayStyle.BLUE_FOOT),
            )
        }
    }
}
