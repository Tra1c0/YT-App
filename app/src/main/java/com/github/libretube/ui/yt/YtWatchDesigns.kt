package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Streams
import com.github.libretube.extensions.formatShort
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

/*
 * YT: the pieces of the video page that differ per design beyond colors: the creator row, the
 * actions, the subscribe button, the tabs and the chapters. The page keeps its structure, but
 * each design gives those pieces its own form and motion: YouTube neu pills and an underline
 * that glides; After a strip of film and a wipe; Clay puffy buttons and a slab with a raised
 * blob; Editorial a printed line, numbered sections and a table; Glas a glass bar, a glass drop
 * over the tabs and glass tiles; Pop colored blobs that wobble; Formen material shapes that turn
 * and a wavy underline; Frost frosted circles that thaw; Kiesel a black panel, capsules and a
 * capsule that slides.
 */

/** A backdrop behind the video page of the glass designs, for the glass pieces to look through. */
internal val LocalWatchBackdrop = staticCompositionLocalOf<Backdrop?> { null }

internal class WatchAction(@DrawableRes val icon: Int, val label: String, val onClick: () -> Unit) {
    /**
     * More gestures of the action, applied outside its own click (sharing onto the timeline on
     * the share action; nothing on the others).
     */
    var gesture: Modifier = Modifier
}

/** The gradient behind the shared screens of the glass designs; ends in the background color. */
internal fun ytGlassScreenColors(palette: YtPalette): List<Color> =
    if (palette.signature == YtSignature.FROST) listOf(Color(0xFF6F8A80), Color(0xFF627B71), palette.background)
    else listOf(ytDayNight(Color(0xFFCFE9E2), Color(0xFF0E1F1F)), ytDayNight(Color(0xFFDDF2EE), Color(0xFF12292A)), palette.background)

/** The drifting light behind the shared screens of the glass designs. */
internal fun ytGlassLightColors(palette: YtPalette): List<Color> =
    if (palette.signature == YtSignature.FROST) listOf(Color(0x40FFFFFF), Color(0x38F08A3C), Color(0x33D9EBE1))
    else listOf(ytDayNight(Color(0x99FFFFFF), Color(0x336FB5AA)), ytDayNight(Color(0x5C8FD8CB), Color(0x3D2E7D74)), ytDayNight(Color(0x66FFFFFF), Color(0x26CFEDE7)))

private val ClayShadow: Color get() = ytDayNight(Color(0x80B07A66), Color(0x80000000))
private val ClayInk: Color get() = ytDayNight(Color(0xFF7A5560), Color(0xFFE8C6C2))
private val ClayLabel: Color get() = ytDayNight(Color(0xFF8C6563), Color(0xFFC9AEA2))
private val ClayMauve: Color get() = ytDayNight(Color(0xFF9D6A6C), Color(0xFFD9A5A6))
private val ClayLight: Color get() = ytDayNight(Color(0xFFFFF3E9), Color(0xFF4A3830))
private val ClayDeep: Color get() = ytDayNight(Color(0xFFF2D9C6), Color(0xFF3A2C26))
private val ClaySlab: Color get() = ytDayNight(Color(0xFFEBD7C8), Color(0xFF3F302A))
private val PopInk = Color(0xFF3B2054)
private val PopPinkStrong = Color(0xFFE0558F)
private val PopLilac = Color(0xFFDFD6F7)
private val PopYellow = Color(0xFFFDE682)
private val PopBlobColors = listOf(PopYellow, Color(0xFFF7B3CE), PopLilac, Color(0xFFBFE8D2), Color(0xFFFFD8B5))
private val ShapesYellow = Color(0xFFF6D365)
private val ShapesSalmon = Color(0xFFF28B82)
private val ShapesInk = Color(0xFF12122A)
private val FrostDark = Color(0xFF1F2A26)
private val GlassShadow: Color get() = ytDayNight(Color(0x2E5C8C85), Color(0x66000000))
private val KieselBeige: Color get() = ytDayNight(Color(0xFFE2DCD5), Color(0xFF4A4541))
private val KieselCard: Color get() = ytDayNight(Color(0xFFF7F4F1), Color(0xFF2B2826))
private val KieselGrey: Color get() = ytDayNight(Color(0xFF7A7672), Color(0xFFABA59F))

/**
 * Places its content under tab [position] of [count] equal tabs, [fraction] of a tab wide and
 * [heightPx] tall, and moves it as [position] changes (read in the layout phase).
 */
private fun Modifier.glideTo(position: () -> Float, count: Int, fraction: Float, heightPx: Int): Modifier =
    layout { measurable, constraints ->
        val tab = constraints.maxWidth / count.coerceAtLeast(1)
        val w = (tab * fraction).roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(w, heightPx))
        layout(constraints.maxWidth, heightPx) {
            placeable.place((position() * tab + (tab - w) / 2f).roundToInt(), 0)
        }
    }

// ---------------------------------------------------------------------------------------------
// Creator

/** The creator row with the subscribe button; Kiesel puts it on its black panel. */
@Composable
internal fun WatchCreator(
    palette: YtPalette,
    imageLoader: ImageLoader,
    streams: Streams,
    hasChannel: Boolean,
    subscribed: Boolean,
    onToggle: () -> Unit,
    onOpenChannel: () -> Unit,
) {
    val kiesel = palette.signature == YtSignature.KIESEL
    val ink = if (kiesel) KieselCream else palette.text
    val dim = if (kiesel) KieselCream.copy(alpha = 0.7f) else palette.textSecondary
    val font = if (kiesel) YtFonts.jakarta else palette.font
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (kiesel) {
            Modifier
                .padding(start = 13.dp, end = 13.dp, top = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(KieselPanel)
                .padding(12.dp)
        } else {
            Modifier.padding(start = 13.dp, end = 12.dp, top = 14.dp)
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenChannel),
        ) {
            AsyncImage(
                model = streams.uploaderAvatar,
                contentDescription = streams.uploader,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = if (palette.isSynthwave) {
                    // Synthwave: the avatar in a cyan neon ring with a soft glow
                    Modifier
                        .size(46.dp)
                        .synthwaveGlow(CircleShape, palette.lime, 4.dp)
                        .border(1.5.dp, palette.lime, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(palette.surface)
                } else if (palette.isFrequency) {
                    // Frequency: the avatar in an aqua cookie frame that breathes with the bass
                    Modifier
                        .size(46.dp)
                        .frequencyCookie(palette.lime, lobes = 9, rest = 0.07f, index = 0)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(palette.surface)
                } else {
                    Modifier
                        .size(if (kiesel) 44.dp else 42.dp)
                        .clip(CircleShape)
                        .background(if (kiesel) KieselPanelCard else palette.surface)
                },
            )
            Spacer(Modifier.width(if (kiesel) 12.dp else 15.dp))
            Column {
                Text(
                    text = streams.uploader,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = font, fontWeight = if (kiesel) FontWeight.ExtraBold else FontWeight.Medium, fontSize = if (kiesel) 15.sp else 14.5.sp),
                )
                if (streams.uploaderSubscriberCount >= 0) {
                    Text(
                        text = stringResource(R.string.subscribers, streams.uploaderSubscriberCount.formatShort()),
                        color = dim,
                        style = TextStyle(fontFamily = font, fontSize = if (kiesel) 11.5.sp else 12.5.sp),
                    )
                }
            }
        }
        if (hasChannel) WatchSubscribe(palette, subscribed, onToggle)
    }
}

// ---------------------------------------------------------------------------------------------
// Subscribe

@Composable
internal fun WatchSubscribe(palette: YtPalette, subscribed: Boolean, onToggle: () -> Unit) {
    val haptics = rememberYtHaptics()
    val toggle: () -> Unit = {
        if (!subscribed) haptics.confirm() else haptics.tick()
        onToggle()
    }
    val label = stringResource(if (subscribed) R.string.yt_watch_subscribed else R.string.yt_watch_subscribe)
    when (palette.signature) {
        YtSignature.AFTER -> AfterSubscribe(palette, subscribed, label, toggle)
        YtSignature.CLAY -> ClaySubscribe(palette, subscribed, label, toggle)
        YtSignature.EDITORIAL -> EditorialSubscribe(palette, subscribed, label, toggle)
        YtSignature.GLASS -> GlassSubscribe(palette, subscribed, label, toggle)
        YtSignature.POP -> PopSubscribe(subscribed, label, toggle)
        YtSignature.SHAPES -> ShapesSubscribe(palette, subscribed, label, toggle)
        YtSignature.FREQUENCY -> FrequencySubscribe(palette, subscribed, label, toggle)
        YtSignature.SYNTHWAVE -> SynthwaveSubscribe(palette, subscribed, label, toggle)
        YtSignature.FROST -> FrostSubscribe(subscribed, label, toggle)
        YtSignature.KIESEL -> KieselSubscribe(subscribed, label, toggle)
        else -> PillSubscribe(palette, subscribed, label, toggle)
    }
}

/** A check that slides in when subscribed. */
@Composable
private fun SubscribedCheck(subscribed: Boolean, tint: Color, size: Int = 15) {
    AnimatedVisibility(
        visible = subscribed,
        enter = ytEnterTransition(expandHorizontally(tween(220, easing = YtEaseOut)) + fadeIn()),
        exit = ytExitTransition(shrinkHorizontally(tween(160, easing = YtEaseOut)) + fadeOut()),
    ) {
        Icon(
            painterResource(R.drawable.ic_yt_check),
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(end = 6.dp)
                .size(size.dp),
        )
    }
}

/** YouTube neu: the pill changes color, wobbles, a ring opens around it and a check slides in. */
@Composable
private fun PillSubscribe(palette: YtPalette, subscribed: Boolean, label: String, toggle: () -> Unit) {
    val fill by animateColorAsState(if (subscribed) palette.raised else palette.text, ytStateSpec(), label = "subscribeFill")
    val ink by animateColorAsState(if (subscribed) palette.text else palette.background, ytStateSpec(), label = "subscribeInk")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .ytBurst(subscribed, palette.accent)
            .ytJelly(subscribed, amount = 0.08f)
            .clip(ytPillShape(palette))
            .drawBehind { drawRect(fill) }
            .clickable(onClick = toggle)
            .height(32.dp)
            .padding(horizontal = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubscribedCheck(subscribed, ink)
            Text(
                text = label,
                color = ink,
                style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 13.sp),
            )
        }
    }
}

/** After: an outlined frame in condensed capitals; subscribing wipes the accent in from the left. */
@Composable
private fun AfterSubscribe(palette: YtPalette, subscribed: Boolean, label: String, toggle: () -> Unit) {
    val wipe by animateFloatAsState(if (subscribed) 1f else 0f, ytSpec(tween(320, easing = YtEaseInOut)), label = "afterWipe")
    val ink by animateColorAsState(if (subscribed) palette.onAccent else palette.text, ytSpec(tween(320)), label = "afterInk")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, palette.text.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .drawBehind { drawRect(palette.accent, size = Size(size.width * wipe, size.height)) }
            .clickable(onClick = toggle)
            .height(32.dp)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = label.uppercase(),
            color = ink,
            style = TextStyle(fontFamily = YtFonts.robotoCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp),
        )
    }
}

/** Clay: a puffy pill that squishes under the finger and turns mauve when subscribed. */
@Composable
private fun ClaySubscribe(palette: YtPalette, subscribed: Boolean, label: String, toggle: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (subscribed) ClayMauve else ClayLight, ytStateSpec(), label = "clayFill")
    val ink by animateColorAsState(if (subscribed) ClayLight else ClayInk, ytStateSpec(), label = "clayInk")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .ytJelly(subscribed, amount = 0.1f)
            .ytSquish(press)
            .shadow(8.dp, RoundedCornerShape(50), ambientColor = ClayShadow, spotColor = ClayShadow)
            .clip(RoundedCornerShape(50))
            .drawBehind { drawRect(fill) }
            .border(1.dp, ytDayNight(Color(0x99FFFFFF), Color(0x26FFF0E4)), RoundedCornerShape(50))
            .clickable(interactionSource = press, indication = LocalIndication.current, onClick = toggle)
            .height(38.dp)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubscribedCheck(subscribed, ink)
            Text(label, color = ink, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp))
        }
    }
}

/** Editorial: a word in mono capitals, underlined; subscribed it is set in reverse, black on white. */
@Composable
private fun EditorialSubscribe(palette: YtPalette, subscribed: Boolean, label: String, toggle: () -> Unit) {
    val fill by animateColorAsState(if (subscribed) palette.text else Color.Transparent, ytSpec(tween(120)), label = "editorialFill")
    val ink by animateColorAsState(if (subscribed) palette.background else palette.text, ytSpec(tween(120)), label = "editorialInk")
    Text(
        text = label.uppercase(),
        color = ink,
        style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 2.sp),
        modifier = Modifier
            .drawBehind {
                drawRect(fill)
                if (!subscribed) {
                    drawLine(palette.text, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx())
                }
            }
            .clickable(onClick = toggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** Glas: a glass pill that swells under the finger and takes the accent when subscribed. */
@Composable
private fun GlassSubscribe(palette: YtPalette, subscribed: Boolean, label: String, toggle: () -> Unit) {
    val backdrop = LocalWatchBackdrop.current
    val press = remember { MutableInteractionSource() }
    val swell by rememberSwell(press)
    val tint by animateColorAsState(if (subscribed) palette.accent.copy(alpha = if (ytNight) 0.22f else 0.45f) else ytDayNight(Color(0xB3FFFFFF), Color(0x4D6FB5AA)), ytStateSpec(), label = "glassTint")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(36.dp)
            .then(
                if (backdrop != null) Modifier.liquidGlass(backdrop, RoundedCornerShape(50), tint, GlassShadow, swell = { swell })
                else Modifier.ytGlass(RoundedCornerShape(50), strong = true),
            )
            .clickable(interactionSource = press, indication = LocalIndication.current, onClick = toggle)
            .padding(horizontal = 18.dp),
    ) {
        SubscribedCheck(subscribed, palette.text)
        Text(label, color = palette.text, style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 13.sp))
    }
}

/** Pop: a strong pink blob that bursts and wobbles, lilac once subscribed. */
@Composable
private fun PopSubscribe(subscribed: Boolean, label: String, toggle: () -> Unit) {
    val fill by animateColorAsState(if (subscribed) PopLilac else PopPinkStrong, ytStateSpec(), label = "popFill")
    val ink by animateColorAsState(if (subscribed) PopInk else Color.White, ytStateSpec(), label = "popInk")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .ytBurst(subscribed, PopPinkStrong)
            .ytJelly(subscribed, amount = 0.14f)
            .clip(BlobShape(0.6f, 0.5f, 0.56f, 0.64f))
            .drawBehind { drawRect(fill) }
            .clickable(onClick = toggle)
            .height(40.dp)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubscribedCheck(subscribed, ink)
            Text(label, color = ink, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 14.sp))
        }
    }
}

/** Formen: a yellow pill that bounces and turns lime when subscribed. */
@Composable
private fun ShapesSubscribe(palette: YtPalette, subscribed: Boolean, label: String, toggle: () -> Unit) {
    val fill by animateColorAsState(if (subscribed) palette.lime else ShapesYellow, ytStateSpec(), label = "shapesFill")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .ytSelectBounce(subscribed)
            .clip(RoundedCornerShape(50))
            .drawBehind { drawRect(fill) }
            .clickable(onClick = toggle)
            .height(36.dp)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubscribedCheck(subscribed, ShapesInk)
            Text(label, color = ShapesInk, style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 13.sp))
        }
    }
}

/**
 * Frequency: YouTube neu's pill in coral with night ink, glowing softly with the level; it bounces
 * as Formen's does and turns violet when subscribed. On it, under the label, an equalizer in the
 * style of Deezer's logo dances with the spectrum (FrequencyPillBars), in the ink at an alpha
 * that keeps the label at 4.5:1 on both colours and every colour between.
 */
@Composable
private fun FrequencySubscribe(palette: YtPalette, subscribed: Boolean, label: String, toggle: () -> Unit) {
    val fill by animateColorAsState(if (subscribed) palette.lavender else palette.accent, ytStateSpec(), label = "frequencyFill")
    val bars = remember(palette) { palette.ink.copy(alpha = frequencyPillBarAlpha(palette)) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .ytSelectBounce(subscribed)
            .ytBurst(subscribed, palette.accent)
            .then(if (subscribed) Modifier else Modifier.frequencyGlow(palette.accent, reach = 1.05f, rest = 0.12f, peak = 0.35f))
            .clip(RoundedCornerShape(50))
            .drawBehind { drawRect(fill) }
            .frequencyPillBars(bars)
            .clickable(onClick = toggle)
            .height(36.dp)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubscribedCheck(subscribed, palette.ink)
            Text(label, color = palette.ink, style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
        }
    }
}

/** Frost: a solid white pill; subscribed it turns to frosted glass that thaws under the finger. */
@Composable
private fun FrostSubscribe(subscribed: Boolean, label: String, toggle: () -> Unit) {
    val backdrop = LocalWatchBackdrop.current
    val press = remember { MutableInteractionSource() }
    val thaw by rememberThaw(press)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .ytJelly(subscribed, amount = 0.08f)
            .height(36.dp)
            .then(
                if (subscribed) Modifier.frostGlass(backdrop, RoundedCornerShape(50)) { thaw }
                else Modifier.clip(RoundedCornerShape(50)).background(Color.White),
            )
            .clickable(interactionSource = press, indication = LocalIndication.current, onClick = toggle)
            .padding(horizontal = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubscribedCheck(subscribed, Color.White)
            Text(label, color = if (subscribed) Color.White else FrostDark, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.sp))
        }
    }
}

/** Kiesel: a cream capsule on the black panel, lavender once subscribed. */
@Composable
private fun KieselSubscribe(subscribed: Boolean, label: String, toggle: () -> Unit) {
    val fill by animateColorAsState(if (subscribed) KieselLavender else KieselPanelCard, ytStateSpec(), label = "kieselFill")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .ytSelectBounce(subscribed)
            .ytPressable(RoundedCornerShape(50), squish = true, onClick = toggle)
            .drawBehind { drawRect(fill) }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        SubscribedCheck(subscribed, kieselInkOn(fill), size = 14)
        Text(label, color = kieselInkOn(fill), style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp))
    }
}

// ---------------------------------------------------------------------------------------------
// Actions

@Composable
internal fun WatchActionRow(palette: YtPalette, actions: List<WatchAction>, modifier: Modifier = Modifier) {
    when (palette.signature) {
        YtSignature.AFTER -> AfterActions(palette, actions, modifier)
        YtSignature.CLAY -> ClayActions(palette, actions, modifier)
        YtSignature.EDITORIAL -> EditorialActions(palette, actions, modifier)
        YtSignature.GLASS -> GlassActions(palette, actions, modifier)
        YtSignature.POP -> PopActions(palette, actions, modifier)
        YtSignature.SHAPES -> ShapesActions(palette, actions, modifier)
        YtSignature.FREQUENCY -> FrequencyActions(palette, actions, modifier)
        YtSignature.SYNTHWAVE -> SynthwaveActions(palette, actions, modifier)
        YtSignature.FROST -> FrostActions(palette, actions, modifier)
        YtSignature.KIESEL -> KieselActions(actions, modifier)
        else -> PillActions(palette, actions, modifier)
    }
}

/**
 * Frequency: Formen's material shapes in aqua, violet, coral and amber, a quarter turn on a tap;
 * with the sound each grows with the beat, bounces on hits one after another, morphs on through
 * cookie, blob, squircle and circle, and a light sweeps over it (FrequencyPulse, read while
 * drawing). The icon stays upright, the caption below never moves.
 */
@Composable
internal fun FrequencyActions(palette: YtPalette, actions: List<WatchAction>, modifier: Modifier) {
    val haptics = rememberYtHaptics()
    // each button's own form at rest (FrequencyMorph) and its scallops
    val bases = remember { intArrayOf(FrequencyMorph.COOKIE, FrequencyMorph.SQUIRCLE, FrequencyMorph.COOKIE, FrequencyMorph.BLOB, FrequencyMorph.CIRCLE) }
    val lobes = remember { intArrayOf(9, 8, 6, 8, 7) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        actions.forEachIndexed { i, action ->
            var taps by remember { mutableIntStateOf(0) }
            val spin by animateFloatAsState(taps * 45f, ytSpec(spring(dampingRatio = 0.55f, stiffness = 300f)), label = "frequencySpin")
            val fill = frequencyTag(palette, i)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(66.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .then(action.gesture)
                        .size(54.dp)
                        .frequencyBounce(i, lift = 5.dp, amount = 0f)
                        .graphicsLayer { rotationZ = spin }
                        .frequencyShape(fill, index = i, base = bases[i % bases.size], lobes = lobes[i % lobes.size], amount = 0.18f)
                        // the shape grows and morphs past its box: no ripple clipped to the box
                        .clickable(interactionSource = null, indication = null) {
                            taps++
                            haptics.tick()
                            action.onClick()
                        },
                ) {
                    Icon(
                        painterResource(action.icon),
                        contentDescription = null,
                        tint = palette.ink,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { rotationZ = -spin },
                    )
                }
                Text(
                    text = action.label,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** YouTube neu: compact pills that give way under the finger. */
@Composable
private fun PillActions(palette: YtPalette, actions: List<WatchAction>, modifier: Modifier) {
    val haptics = rememberYtHaptics()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(actions) { action ->
            val press = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .then(action.gesture)
                    .ytPressScale(press)
                    .ytSurface(palette, ytPillShape(palette))
                    .clickable(interactionSource = press, indication = LocalIndication.current) {
                        haptics.tick()
                        action.onClick()
                    }
                    .height(36.dp)
                    .padding(horizontal = 18.dp),
            ) {
                Icon(painterResource(action.icon), contentDescription = null, tint = palette.text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = action.label,
                    color = palette.text,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                )
            }
        }
    }
}

/** After: a strip of film, each action a frame with a condensed caption; a pressed frame lights up. */
@Composable
private fun AfterActions(palette: YtPalette, actions: List<WatchAction>, modifier: Modifier) {
    val haptics = rememberYtHaptics()
    // as wide as the screen, in up to two rows: every cell and label shows in full
    val perRow = if (actions.size <= 4) actions.size.coerceAtLeast(1) else (actions.size + 1) / 2
    Column(
        modifier
            .padding(horizontal = 13.dp)
            .fillMaxWidth()
            .border(1.dp, palette.raised, RoundedCornerShape(4.dp)),
    ) {
        actions.chunked(perRow).forEachIndexed { r, row ->
            if (r > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.raised))
            Row(Modifier.fillMaxWidth().height(58.dp)) {
                row.forEachIndexed { i, action ->
                    if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(palette.raised))
                    val press = remember { MutableInteractionSource() }
                    val pressed by press.collectIsPressedAsState()
                    val glow by animateFloatAsState(if (pressed) 1f else 0f, ytSpec(spring(dampingRatio = 0.8f, stiffness = 500f)), label = "afterGlow")
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .then(action.gesture)
                            .weight(1f)
                            .fillMaxHeight()
                            .drawBehind { drawRect(palette.accent, alpha = 0.18f * glow) }
                            .clickable(interactionSource = press, indication = null) {
                                haptics.tick()
                                action.onClick()
                            },
                    ) {
                        Icon(painterResource(action.icon), contentDescription = null, tint = palette.text, modifier = Modifier.size(20.dp))
                        Text(
                            text = action.label.uppercase(),
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = YtFonts.robotoCondensed, fontWeight = FontWeight.Bold, fontSize = 9.5.sp, letterSpacing = 1.sp),
                            modifier = Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Clay: puffy round buttons, lit from above, that squish under the finger. */
@Composable
private fun ClayActions(palette: YtPalette, actions: List<WatchAction>, modifier: Modifier) {
    val haptics = rememberYtHaptics()
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        actions.forEach { action ->
            val press = remember { MutableInteractionSource() }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(66.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .then(action.gesture)
                        .size(54.dp)
                        .ytSquish(press)
                        .shadow(8.dp, CircleShape, ambientColor = ClayShadow, spotColor = ClayShadow)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(ClayLight, ClayDeep)))
                        .border(1.dp, ytDayNight(Color(0x99FFFFFF), Color(0x26FFF0E4)), CircleShape)
                        .clickable(interactionSource = press, indication = LocalIndication.current) {
                            haptics.tick()
                            action.onClick()
                        },
                ) {
                    Icon(painterResource(action.icon), contentDescription = null, tint = ClayInk, modifier = Modifier.size(22.dp))
                }
                Text(
                    text = action.label,
                    color = ClayLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Editorial: one printed line in mono capitals, the actions set off by slashes; a pressed word inverts. */
@Composable
private fun EditorialActions(palette: YtPalette, actions: List<WatchAction>, modifier: Modifier) {
    val haptics = rememberYtHaptics()
    val mono = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.5.sp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 13.dp),
    ) {
        actions.forEachIndexed { i, action ->
            if (i > 0) Text("/", color = palette.textSecondary, style = mono, modifier = Modifier.padding(horizontal = 4.dp))
            val press = remember { MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            Text(
                text = action.label.uppercase(),
                color = if (pressed) palette.background else palette.text,
                style = mono,
                modifier = Modifier
                    .then(action.gesture)
                    .background(if (pressed) palette.text else Color.Transparent)
                    .clickable(interactionSource = press, indication = null) {
                        haptics.tick()
                        action.onClick()
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

/** Glas: one bar of glass with the icons; it swells while any of them is pressed. */
@Composable
private fun GlassActions(palette: YtPalette, actions: List<WatchAction>, modifier: Modifier) {
    val backdrop = LocalWatchBackdrop.current
    val haptics = rememberYtHaptics()
    // all the icons share one interaction source, so the bar swells whichever is pressed
    val press = remember { MutableInteractionSource() }
    val swell by rememberSwell(press)
    Row(
        modifier
            .padding(horizontal = 13.dp)
            .fillMaxWidth()
            .height(56.dp)
            .then(
                if (backdrop != null) Modifier.liquidGlass(backdrop, RoundedCornerShape(50), ytDayNight(Color(0x80FFFFFF), Color(0x33A8E0D6)), GlassShadow, swell = { swell })
                else Modifier.ytGlass(RoundedCornerShape(50)),
            )
            .padding(horizontal = 6.dp),
    ) {
        actions.forEach { action ->
            val own = remember { MutableInteractionSource() }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .then(action.gesture)
                    .weight(1f)
                    .fillMaxHeight()
                    .ytPressScale(own)
                    .clip(RoundedCornerShape(50))
                    .clickable(interactionSource = press, indication = null) {
                        haptics.tick()
                        action.onClick()
                    },
            ) {
                Icon(painterResource(action.icon), contentDescription = action.label, tint = palette.text, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Pop: each action its own colored blob with a caption; a tap makes it wobble. */
@Composable
private fun PopActions(palette: YtPalette, actions: List<WatchAction>, modifier: Modifier) {
    val haptics = rememberYtHaptics()
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        actions.forEachIndexed { i, action ->
            var taps by remember { mutableIntStateOf(0) }
            val press = remember { MutableInteractionSource() }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(66.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .then(action.gesture)
                        .size(56.dp)
                        .ytJelly(taps % 2 == 0, amount = 0.14f)
                        .ytSquish(press)
                        .clip(BlobShape(0.6f + 0.05f * (i % 3), 0.5f, 0.56f, 0.64f))
                        .background(PopBlobColors[i % PopBlobColors.size])
                        .clickable(interactionSource = press, indication = null) {
                            taps++
                            haptics.tick()
                            action.onClick()
                        },
                ) {
                    Icon(painterResource(action.icon), contentDescription = null, tint = PopInk, modifier = Modifier.size(22.dp))
                }
                Text(
                    text = action.label,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Formen: each action a different material shape in a different color; a tap turns it an eighth. */
@Composable
private fun ShapesActions(palette: YtPalette, actions: List<WatchAction>, modifier: Modifier) {
    val haptics = rememberYtHaptics()
    val shapes = listOf(
        CookieShape(8),
        RoundedCornerShape(topStartPercent = 50, topEndPercent = 20, bottomEndPercent = 50, bottomStartPercent = 20),
        CircleShape,
        RoundedCornerShape(30),
        CookieShape(6, 0.1f),
    )
    val colors = listOf(ShapesYellow, palette.lime, palette.lavender, Color.White, ShapesSalmon)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        actions.forEachIndexed { i, action ->
            var taps by remember { mutableIntStateOf(0) }
            val spin by animateFloatAsState(taps * 45f, ytSpec(spring(dampingRatio = 0.55f, stiffness = 300f)), label = "shapeSpin")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .then(action.gesture)
                        .size(54.dp)
                        .graphicsLayer { rotationZ = spin }
                        .clip(shapes[i % shapes.size])
                        .background(colors[i % colors.size])
                        .clickable {
                            taps++
                            haptics.tick()
                            action.onClick()
                        },
                ) {
                    // the icon stays upright while its shape turns
                    Icon(
                        painterResource(action.icon),
                        contentDescription = null,
                        tint = ShapesInk,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { rotationZ = -spin },
                    )
                }
                Text(
                    text = action.label,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Frost: frosted circles that thaw under a warm finger. */
@Composable
private fun FrostActions(palette: YtPalette, actions: List<WatchAction>, modifier: Modifier) {
    val backdrop = LocalWatchBackdrop.current
    val haptics = rememberYtHaptics()
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        actions.forEach { action ->
            val press = remember { MutableInteractionSource() }
            val thaw by rememberThaw(press)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .then(action.gesture)
                        .size(52.dp)
                        .frostGlass(backdrop, CircleShape) { thaw }
                        .clickable(interactionSource = press, indication = LocalIndication.current) {
                            haptics.tick()
                            action.onClick()
                        },
                ) {
                    Icon(painterResource(action.icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
                }
                Text(
                    text = action.label,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Kiesel: capsules in lavender, peach and beige that squish like pebbles. */
@Composable
private fun KieselActions(actions: List<WatchAction>, modifier: Modifier) {
    val haptics = rememberYtHaptics()
    val colors = listOf(KieselLavender, KieselPeach, KieselBeige, KieselCard, KieselLavender)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 13.dp),
    ) {
        actions.forEachIndexed { i, action ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .then(action.gesture)
                    .ytPressable(RoundedCornerShape(50), squish = true) {
                        haptics.tick()
                        action.onClick()
                    }
                    .background(colors[i % colors.size])
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(painterResource(action.icon), contentDescription = null, tint = kieselInkOn(colors[i % colors.size]), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = action.label,
                    color = kieselInkOn(colors[i % colors.size]),
                    style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tabs

/**
 * The tabs below the actions; the last label is the comments, which open on their own and are
 * never selected. [selected] is the index of the open tab.
 */
@Composable
internal fun WatchTabs(
    palette: YtPalette,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** True if the last label opens something (the comments) instead of being a tab. */
    trailingAction: Boolean = true,
) {
    val haptics = rememberYtHaptics()
    val pick: (Int) -> Unit = { i ->
        haptics.tick()
        onSelect(i)
    }
    when (palette.signature) {
        YtSignature.AFTER -> AfterTabs(palette, labels, selected, pick, modifier)
        YtSignature.CLAY -> ClayTabs(palette, labels, selected, pick, modifier)
        YtSignature.EDITORIAL -> EditorialTabs(palette, labels, selected, pick, modifier)
        YtSignature.GLASS -> GlassTabs(palette, labels, selected, onSelect, modifier, trailingAction)
        YtSignature.POP -> PopTabs(palette, labels, selected, pick, modifier)
        // Frequency: Formen's travelling wave under the tabs, in aqua
        YtSignature.SHAPES, YtSignature.FREQUENCY -> ShapesTabs(palette, labels, selected, pick, modifier)
        // Synthwave: a magenta neon tube glides under the chosen tab, over a faint horizon
        YtSignature.SYNTHWAVE -> SynthwaveTabs(palette, labels, selected, pick, modifier)
        YtSignature.FROST -> FrostTabs(labels, selected, pick, modifier)
        YtSignature.KIESEL -> KieselTabs(labels, selected, pick, modifier)
        else -> UnderlineTabs(palette, labels, selected, pick, modifier)
    }
}

/** Equal tabs with plain labels; [content] draws each label. */
@Composable
private fun TabLabels(
    labels: List<String>,
    selected: Int,
    pick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    indication: Boolean = true,
    content: @Composable (label: String, active: Boolean) -> Unit,
) {
    Row(modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = null,
                        indication = if (indication) LocalIndication.current else null,
                    ) { pick(i) },
            ) {
                content(label, i == selected)
            }
        }
    }
}

/** YouTube neu: the accent underline glides to the chosen tab. */
@Composable
private fun UnderlineTabs(palette: YtPalette, labels: List<String>, selected: Int, pick: (Int) -> Unit, modifier: Modifier) {
    val pos by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), ytSpec(spring(dampingRatio = 0.85f, stiffness = 420f)), label = "tabPos")
    val thickness = with(LocalDensity.current) { 3.dp.roundToPx() }
    Column(modifier.fillMaxWidth()) {
        TabLabels(labels, selected, pick, Modifier.height(42.dp)) { label, active ->
            val ink by animateColorAsState(if (active) palette.text else palette.textSecondary, ytStateSpec(), label = "tabInk")
            Text(label, color = ink, textAlign = TextAlign.Center, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 14.sp))
        }
        Box(
            Modifier
                .glideTo({ pos }, labels.size, 0.81f, thickness)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.accent),
        )
        Box(
            Modifier
                .padding(horizontal = 13.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.raised),
        )
    }
}

/** After: condensed capitals over a rule; a small frame marker moves under the chosen tab. */
@Composable
private fun AfterTabs(palette: YtPalette, labels: List<String>, selected: Int, pick: (Int) -> Unit, modifier: Modifier) {
    val pos by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), ytSpec(tween(260, easing = YtEaseInOut)), label = "afterTab")
    val marker = with(LocalDensity.current) { 6.dp.roundToPx() }
    Column(modifier.fillMaxWidth()) {
        TabLabels(labels, selected, pick, Modifier.height(40.dp), indication = false) { label, active ->
            val ink by animateColorAsState(if (active) palette.text else palette.textSecondary, ytSpec(tween(260)), label = "afterTabInk")
            Text(
                label.uppercase(),
                color = ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = TextStyle(fontFamily = YtFonts.robotoCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp),
            )
        }
        Canvas(Modifier.glideTo({ pos }, labels.size, 0.2f, marker)) {
            val path = Path().apply {
                moveTo(size.width / 2f - 5.dp.toPx(), size.height)
                lineTo(size.width / 2f + 5.dp.toPx(), size.height)
                lineTo(size.width / 2f, 0f)
                close()
            }
            drawPath(path, palette.accent)
        }
        Box(
            Modifier
                .padding(horizontal = 13.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.raised),
        )
    }
}

/** Clay: a slab of clay with a raised, lit blob that springs under the chosen tab. */
@Composable
private fun ClayTabs(palette: YtPalette, labels: List<String>, selected: Int, pick: (Int) -> Unit, modifier: Modifier) {
    val pos by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), ytSpec(spring(dampingRatio = 0.6f, stiffness = 320f)), label = "clayTab")
    val h = with(LocalDensity.current) { 38.dp.roundToPx() }
    Box(
        modifier
            .padding(horizontal = 13.dp)
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(ClaySlab)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .glideTo({ pos }, labels.size, 0.96f, h)
                .shadow(3.dp, RoundedCornerShape(19.dp), ambientColor = ClayShadow, spotColor = ClayShadow)
                .clip(RoundedCornerShape(19.dp))
                .background(Brush.verticalGradient(listOf(ClayLight, ClayDeep)))
                .border(1.dp, ytDayNight(Color(0x99FFFFFF), Color(0x26FFF0E4)), RoundedCornerShape(19.dp)),
        )
        TabLabels(labels, selected, pick, Modifier.fillMaxSize(), indication = false) { label, active ->
            val ink by animateColorAsState(if (active) ClayInk else ClayLabel, ytStateSpec(), label = "clayTabInk")
            Text(label, color = ink, maxLines = 1, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
        }
    }
}

/** Editorial: numbered sections in mono; a thick rule jumps under the chosen one, crisp. */
@Composable
private fun EditorialTabs(palette: YtPalette, labels: List<String>, selected: Int, pick: (Int) -> Unit, modifier: Modifier) {
    val pos by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), ytSpec(tween(160, easing = YtEaseOut)), label = "editorialTab")
    val rule = with(LocalDensity.current) { 2.dp.roundToPx() }
    Column(modifier.padding(horizontal = 13.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(38.dp)) {
            labels.forEachIndexed { i, label ->
                val active = i == selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = null, indication = null) { pick(i) },
                ) {
                    Text(
                        "%02d".format(i + 1),
                        color = palette.textSecondary,
                        style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 9.sp),
                    )
                    Text(
                        label.uppercase(),
                        color = if (active) palette.text else palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.sp),
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        }
        Box(
            Modifier
                .glideTo({ pos }, labels.size, 1f, rule)
                .background(palette.text),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.text.copy(alpha = 0.25f)),
        )
    }
}

/** Glas: a drop of glass over the tabs, draggable; the comments are a glass button beside it. */
@Composable
private fun GlassTabs(palette: YtPalette, labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier, trailingAction: Boolean) {
    val backdrop = LocalWatchBackdrop.current ?: LocalScreenBackdrop.current
    val haptics = rememberYtHaptics()
    val tabs = if (trailingAction) labels.dropLast(1) else labels
    val comments = labels.lastIndex
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 13.dp)
            .fillMaxWidth(),
    ) {
        if (backdrop != null && tabs.isNotEmpty()) {
            LiquidSegments(
                backdrop = backdrop,
                look = LiquidSegmentsLook(
                    height = 42.dp,
                    gap = 4.dp,
                    tile = ytDayNight(Color(0x40FFFFFF), Color(0x2E6FB5AA)),
                    pill = ytDayNight(Color(0xE6FFFFFF), Color(0xE61F3D3A)),
                    shadow = GlassShadow,
                    text = palette.text,
                    lensText = palette.text,
                    font = YtFonts.googleSans,
                    size = 12.5.sp,
                    activeSize = 13.sp,
                ),
                keys = tabs.indices.map { it.toString() },
                labels = tabs,
                weights = tabs.map { 1f },
                selected = selected.coerceAtLeast(0).toString(),
                onSelect = { onSelect(it.toInt()) },
                modifier = Modifier.weight(1f),
            )
        } else {
            UnderlineTabs(palette, tabs, selected, { haptics.tick(); onSelect(it) }, Modifier.weight(1f))
        }
        if (!trailingAction) return@Row
        Spacer(Modifier.width(10.dp))
        val press = remember { MutableInteractionSource() }
        val swell by rememberSwell(press)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .then(
                    if (backdrop != null) Modifier.liquidGlass(backdrop, CircleShape, ytDayNight(Color(0x99FFFFFF), Color(0x336FB5AA)), GlassShadow, swell = { swell })
                    else Modifier.ytGlass(CircleShape),
                )
                .clickable(interactionSource = press, indication = LocalIndication.current) {
                    haptics.tick()
                    onSelect(comments)
                },
        ) {
            Icon(painterResource(R.drawable.ic_comment), contentDescription = labels.last(), tint = palette.text, modifier = Modifier.size(20.dp))
        }
    }
}

/** Pop: chips in blob shapes; the chosen one takes the accent and bounces. */
@Composable
private fun PopTabs(palette: YtPalette, labels: List<String>, selected: Int, pick: (Int) -> Unit, modifier: Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .padding(horizontal = 13.dp)
            .fillMaxWidth(),
    ) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            val fill by animateColorAsState(if (active) palette.accent else ytDayNight(PopLilac, palette.raised), ytStateSpec(), label = "popTabFill")
            val ink by animateColorAsState(if (active) palette.onAccent else palette.text, ytStateSpec(), label = "popTabInk")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .ytSelectBounce(active)
                    .clip(BlobShape(0.62f, 0.5f, 0.56f, 0.64f))
                    .drawBehind { drawRect(fill) }
                    .clickable { pick(i) },
            ) {
                Text(label, color = ink, maxLines = 1, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 13.sp))
            }
        }
    }
}

/** Formen: a lime wave travels under the chosen tab and springs over when another is chosen. */
@Composable
private fun ShapesTabs(palette: YtPalette, labels: List<String>, selected: Int, pick: (Int) -> Unit, modifier: Modifier) {
    val pos by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), ytSpec(spring(dampingRatio = 0.6f, stiffness = 300f)), label = "shapesTab")
    val phase = rememberWavePhase()
    val wave = with(LocalDensity.current) { 12.dp.roundToPx() }
    Column(modifier.fillMaxWidth()) {
        TabLabels(labels, selected, pick, Modifier.height(40.dp), indication = false) { label, active ->
            val ink by animateColorAsState(if (active) palette.text else palette.textSecondary, ytStateSpec(), label = "shapesTabInk")
            Text(label, color = ink, textAlign = TextAlign.Center, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 14.sp))
        }
        WavyProgress(
            progress = 1f,
            played = palette.lime,
            rest = Color.Transparent,
            modifier = Modifier.glideTo({ pos }, labels.size, 0.6f, wave),
            phase = { phase.value },
        )
    }
}

/** Frost: a frosted track; a solid white segment glides to the chosen tab. */
@Composable
private fun FrostTabs(labels: List<String>, selected: Int, pick: (Int) -> Unit, modifier: Modifier) {
    val backdrop = LocalWatchBackdrop.current
    val pos by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), ytSpec(spring(dampingRatio = 0.75f, stiffness = 260f)), label = "frostTab")
    val h = with(LocalDensity.current) { 36.dp.roundToPx() }
    Box(
        modifier
            .padding(horizontal = 13.dp)
            .fillMaxWidth()
            .height(44.dp)
            .frostGlass(backdrop, RoundedCornerShape(22.dp))
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .glideTo({ pos }, labels.size, 0.97f, h)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White),
        )
        TabLabels(labels, selected, pick, Modifier.fillMaxSize(), indication = false) { label, active ->
            val ink by animateColorAsState(if (active) FrostDark else Color.White, ytStateSpec(), label = "frostTabInk")
            Text(label, color = ink, maxLines = 1, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.sp))
        }
    }
}

/** Kiesel: a beige track; a black capsule springs to the chosen tab. */
@Composable
private fun KieselTabs(labels: List<String>, selected: Int, pick: (Int) -> Unit, modifier: Modifier) {
    val pos by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), ytSpec(spring(dampingRatio = 0.6f, stiffness = 380f)), label = "kieselTab")
    val h = with(LocalDensity.current) { 36.dp.roundToPx() }
    Box(
        modifier
            .padding(horizontal = 13.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(KieselBeige)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .glideTo({ pos }, labels.size, 0.97f, h)
                .clip(RoundedCornerShape(18.dp))
                .background(KieselPanel),
        )
        TabLabels(labels, selected, pick, Modifier.fillMaxSize(), indication = false) { label, active ->
            val ink by animateColorAsState(if (active) KieselCream else KieselInk, ytStateSpec(), label = "kieselTabInk")
            Text(label, color = ink, maxLines = 1, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Chapters

@Composable
internal fun WatchChapters(palette: YtPalette, imageLoader: ImageLoader, streams: Streams, activeIndex: Int, onSeek: (Long) -> Unit) {
    val chapters = streams.chapters
    val lengths = chapters.mapIndexed { i, c -> (chapters.getOrNull(i + 1)?.start ?: streams.duration) - c.start }
    val fallback = streams.thumbnailUrl
    val seek: (ChapterSegment) -> Unit = { onSeek(it.start * 1000) }
    when (palette.signature) {
        YtSignature.AFTER -> AfterChapters(palette, imageLoader, chapters, lengths, fallback, activeIndex, seek)
        YtSignature.CLAY -> ClayChapters(palette, imageLoader, chapters, fallback, activeIndex, seek)
        YtSignature.EDITORIAL -> EditorialChapters(palette, imageLoader, chapters, lengths, fallback, activeIndex, seek)
        YtSignature.GLASS -> GlassChapters(palette, imageLoader, chapters, fallback, activeIndex, seek)
        YtSignature.POP -> PopChapters(palette, imageLoader, chapters, fallback, activeIndex, seek)
        // Frequency: Formen's cookie pictures, the current one turning in an aqua ring
        YtSignature.SHAPES, YtSignature.FREQUENCY -> ShapesChapters(palette, imageLoader, chapters, lengths, fallback, activeIndex, seek)
        // Synthwave: a neon road of chapters, the current one lit in magenta
        YtSignature.SYNTHWAVE -> SynthwaveChapters(palette, imageLoader, chapters, lengths, fallback, activeIndex, seek)
        YtSignature.FROST -> FrostChapters(palette, imageLoader, chapters, lengths, fallback, activeIndex, seek)
        YtSignature.KIESEL -> KieselChapters(imageLoader, chapters, lengths, fallback, activeIndex, seek)
        else -> ListChapters(palette, imageLoader, chapters, lengths, fallback, activeIndex, seek)
    }
}

@Composable
private fun ChapterImage(imageLoader: ImageLoader, chapter: ChapterSegment, fallback: String?, modifier: Modifier) {
    AsyncImage(
        model = chapter.image.ifBlank { fallback },
        contentDescription = null,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

private fun time(seconds: Long): String = DateUtils.formatElapsedTime(seconds)

/** YouTube neu: rows with the current chapter framed in the accent, its picture narrowed. */
@Composable
private fun ListChapters(palette: YtPalette, imageLoader: ImageLoader, chapters: List<ChapterSegment>, lengths: List<Long>, fallback: String?, activeIndex: Int, seek: (ChapterSegment) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 3.dp, end = 3.dp, top = 8.dp, bottom = 4.dp),
    ) {
        chapters.forEachIndexed { index, chapter ->
            val active = index == activeIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (active) Modifier.border(1.5.dp, palette.accent, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable { seek(chapter) }
                    .padding(start = if (active) 16.dp else 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            ) {
                if (active) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(9.dp))
                }
                ChapterImage(
                    imageLoader, chapter, fallback,
                    Modifier
                        .width(if (active) 76.dp else 111.dp)
                        .height(62.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(19.dp))
                Column(Modifier.weight(1f)) {
                    Text(time(chapter.start), color = palette.textSecondary, style = TextStyle(fontFamily = palette.font, fontSize = 12.5.sp))
                    Text(
                        chapter.title,
                        color = palette.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                    )
                }
                if (lengths[index] > 0) {
                    Text(time(lengths[index]), color = palette.textSecondary, style = TextStyle(fontFamily = palette.font, fontSize = 13.sp), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** After: scenes with a timecode, a letterboxed frame and an accent bar that grows beside the current one. */
@Composable
private fun AfterChapters(palette: YtPalette, imageLoader: ImageLoader, chapters: List<ChapterSegment>, lengths: List<Long>, fallback: String?, activeIndex: Int, seek: (ChapterSegment) -> Unit) {
    Column(Modifier.padding(start = 13.dp, end = 13.dp, top = 8.dp)) {
        chapters.forEachIndexed { i, chapter ->
            val active = i == activeIndex
            val mark by animateFloatAsState(if (active) 1f else 0f, ytSpec(tween(260, easing = YtEaseInOut)), label = "scene")
            val ink by animateColorAsState(if (active) palette.text else palette.textSecondary, ytSpec(tween(260)), label = "sceneInk")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { seek(chapter) }
                    .padding(vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(54.dp)
                        .graphicsLayer {
                            scaleY = mark
                            alpha = mark
                        }
                        .background(palette.accent),
                )
                Text(
                    time(chapter.start),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = YtFonts.robotoCondensed, fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .width(46.dp),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(96.dp)
                        .height(54.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black),
                ) {
                    ChapterImage(
                        imageLoader, chapter, fallback,
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .graphicsLayer { alpha = 0.7f + 0.3f * mark },
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        chapter.title,
                        color = ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                    )
                    if (lengths[i] > 0) {
                        Text(time(lengths[i]), color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.robotoCondensed, fontSize = 11.5.sp))
                    }
                }
            }
        }
    }
}

/** Clay: chips of clay in a row; the current one is bigger and ringed in mauve, all squish. */
@Composable
private fun ClayChapters(palette: YtPalette, imageLoader: ImageLoader, chapters: List<ChapterSegment>, fallback: String?, activeIndex: Int, seek: (ChapterSegment) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(chapters) { i, chapter ->
            val active = i == activeIndex
            val press = remember { MutableInteractionSource() }
            val grow by animateFloatAsState(if (active) 1.05f else 1f, ytSpec(spring(dampingRatio = 0.55f, stiffness = 300f)), label = "clayChapter")
            val shape = RoundedCornerShape(22.dp)
            Column(
                Modifier
                    .width(138.dp)
                    .graphicsLayer {
                        scaleX = grow
                        scaleY = grow
                    }
                    .ytSquish(press)
                    .shadow(6.dp, shape, ambientColor = ClayShadow, spotColor = ClayShadow)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(ClayLight, palette.surface)))
                    .border(if (active) 2.dp else 1.dp, if (active) ClayMauve else ytDayNight(Color(0x99FFFFFF), Color(0x26FFF0E4)), shape)
                    .clickable(interactionSource = press, indication = LocalIndication.current) { seek(chapter) }
                    .padding(6.dp),
            ) {
                ChapterImage(
                    imageLoader, chapter, fallback,
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Text(
                    chapter.title,
                    color = ClayInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, lineHeight = 16.sp),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                )
                Text(
                    time(chapter.start),
                    color = ClayLabel,
                    style = TextStyle(fontFamily = palette.font, fontSize = 11.sp),
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/** Editorial: a table with hairlines, pictures in black and white; the current row is set in reverse and in color. */
@Composable
private fun EditorialChapters(palette: YtPalette, imageLoader: ImageLoader, chapters: List<ChapterSegment>, lengths: List<Long>, fallback: String?, activeIndex: Int, seek: (ChapterSegment) -> Unit) {
    Column(Modifier.padding(horizontal = 13.dp)) {
        chapters.forEachIndexed { i, chapter ->
            val active = i == activeIndex
            val fill by animateColorAsState(if (active) palette.text else Color.Transparent, ytSpec(tween(120)), label = "rowFill")
            val ink by animateColorAsState(if (active) palette.background else palette.text, ytSpec(tween(120)), label = "rowInk")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(fill) }
                    .clickable { seek(chapter) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            ) {
                AsyncImage(
                    model = chapter.image.ifBlank { fallback },
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (active) null else ytGrayscale,
                    modifier = Modifier
                        .width(56.dp)
                        .height(32.dp),
                )
                Text(
                    time(chapter.start),
                    color = ink,
                    style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .width(52.dp),
                )
                Text(
                    chapter.title,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                    modifier = Modifier.weight(1f),
                )
                if (lengths[i] > 0) {
                    Text(
                        time(lengths[i]),
                        color = ink.copy(alpha = 0.7f),
                        style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.text.copy(alpha = 0.25f)),
            )
        }
    }
}

/** Glas: tiles of glass in a row that swell under the finger; the current one is ringed in white. */
@Composable
private fun GlassChapters(palette: YtPalette, imageLoader: ImageLoader, chapters: List<ChapterSegment>, fallback: String?, activeIndex: Int, seek: (ChapterSegment) -> Unit) {
    val backdrop = LocalWatchBackdrop.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(chapters) { i, chapter ->
            val active = i == activeIndex
            val press = remember { MutableInteractionSource() }
            val swell by rememberSwell(press)
            val grow by animateFloatAsState(if (active) 1.04f else 1f, ytSpec(spring(dampingRatio = 0.6f, stiffness = 320f)), label = "glassChapter")
            val shape = RoundedCornerShape(18.dp)
            Column(
                Modifier
                    .width(150.dp)
                    .graphicsLayer {
                        scaleX = grow
                        scaleY = grow
                    }
                    .then(
                        if (backdrop != null) Modifier.liquidGlass(backdrop, shape, ytDayNight(Color(0x80FFFFFF), Color(0x33A8E0D6)), GlassShadow, swell = { swell })
                        else Modifier.ytGlass(shape),
                    )
                    .then(if (active) Modifier.border(2.dp, Color.White, shape) else Modifier)
                    .clickable(interactionSource = press, indication = LocalIndication.current) { seek(chapter) }
                    .padding(6.dp),
            ) {
                ChapterImage(
                    imageLoader, chapter, fallback,
                    Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .clip(RoundedCornerShape(13.dp)),
                )
                Text(
                    chapter.title,
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 15.sp),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                )
                Text(
                    time(chapter.start),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 11.sp),
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** Pop: blobs in a row, each a little askew; the current one straightens, grows and wobbles. */
@Composable
private fun PopChapters(palette: YtPalette, imageLoader: ImageLoader, chapters: List<ChapterSegment>, fallback: String?, activeIndex: Int, seek: (ChapterSegment) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(chapters) { i, chapter ->
            val active = i == activeIndex
            val tilt by animateFloatAsState(if (active) 0f else if (i % 2 == 0) -2.5f else 2.5f, ytSpec(spring(dampingRatio = 0.5f, stiffness = 260f)), label = "popTilt")
            val grow by animateFloatAsState(if (active) 1.06f else 1f, ytSpec(spring(dampingRatio = 0.5f, stiffness = 260f)), label = "popGrow")
            val blob = BlobShape(0.6f, 0.5f, 0.56f, 0.64f)
            Column(
                Modifier
                    .width(140.dp)
                    .graphicsLayer {
                        rotationZ = tilt
                        scaleX = grow
                        scaleY = grow
                    }
                    .ytJelly(active, amount = 0.1f)
                    .ytPressable(RoundedCornerShape(20.dp)) { seek(chapter) },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .clip(blob)
                        .background(PopBlobColors[i % PopBlobColors.size])
                        .then(if (active) Modifier.border(3.dp, PopPinkStrong, blob) else Modifier),
                ) {
                    ChapterImage(
                        imageLoader, chapter, fallback,
                        Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                            .clip(blob),
                    )
                    Text(
                        time(chapter.start),
                        color = PopInk,
                        style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 10.5.sp),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(PopYellow)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Text(
                    chapter.title,
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp, lineHeight = 16.sp),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

/** Formen: rows with a cookie-shaped picture; the current cookie turns slowly in a lime ring. */
@Composable
private fun ShapesChapters(palette: YtPalette, imageLoader: ImageLoader, chapters: List<ChapterSegment>, lengths: List<Long>, fallback: String?, activeIndex: Int, seek: (ChapterSegment) -> Unit) {
    val spin = rememberSpin(14000)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        chapters.forEachIndexed { i, chapter ->
            val active = i == activeIndex
            val ring by animateColorAsState(if (active) palette.lime else Color.Transparent, ytStateSpec(), label = "cookieRing")
            val cookie = CookieShape(8, 0.06f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { seek(chapter) }
                    .padding(6.dp),
            ) {
                Box(
                    Modifier
                        .size(62.dp)
                        .graphicsLayer { if (active) rotationZ = spin.value }
                        .clip(cookie)
                        .drawBehind { drawRect(ring) }
                        .padding(3.dp)
                        .clip(cookie),
                ) {
                    ChapterImage(
                        imageLoader, chapter, fallback,
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // the picture stays upright and fills the turning cookie
                                if (active) rotationZ = -spin.value
                                scaleX = 1.4f
                                scaleY = 1.4f
                            },
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        chapter.title,
                        color = palette.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 14.sp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            time(chapter.start),
                            color = ShapesInk,
                            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 10.5.sp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(palette.lime)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                        if (lengths[i] > 0) {
                            Text(time(lengths[i]), color = palette.textSecondary, style = TextStyle(fontFamily = palette.font, fontSize = 11.5.sp), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Frost: frosted rows that thaw under the finger; a white bar grows beside the current one. */
@Composable
private fun FrostChapters(palette: YtPalette, imageLoader: ImageLoader, chapters: List<ChapterSegment>, lengths: List<Long>, fallback: String?, activeIndex: Int, seek: (ChapterSegment) -> Unit) {
    val backdrop = LocalWatchBackdrop.current
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        chapters.forEachIndexed { i, chapter ->
            val active = i == activeIndex
            val press = remember { MutableInteractionSource() }
            val thaw by rememberThaw(press)
            val bar by animateFloatAsState(if (active) 1f else 0f, ytSpec(spring(dampingRatio = 0.7f, stiffness = 300f)), label = "frostBar")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .frostGlass(backdrop, RoundedCornerShape(18.dp)) { thaw }
                    .clickable(interactionSource = press, indication = LocalIndication.current) { seek(chapter) }
                    .padding(8.dp),
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(50.dp)
                        .graphicsLayer {
                            scaleY = bar
                            alpha = bar
                        }
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White),
                )
                ChapterImage(
                    imageLoader, chapter, fallback,
                    Modifier
                        .padding(start = 8.dp)
                        .width(100.dp)
                        .height(58.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (active) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        chapter.title,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                    )
                    Text(
                        if (lengths[i] > 0) time(chapter.start) + " · " + time(lengths[i]) else time(chapter.start),
                        color = palette.textSecondary,
                        style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 11.5.sp),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

/** Kiesel: rows on cream; a peach bar grows beside the current one, the time sits in a lavender capsule. */
@Composable
private fun KieselChapters(imageLoader: ImageLoader, chapters: List<ChapterSegment>, lengths: List<Long>, fallback: String?, activeIndex: Int, seek: (ChapterSegment) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        chapters.forEachIndexed { i, chapter ->
            val active = i == activeIndex
            val bar by animateFloatAsState(if (active) 1f else 0f, ytSpec(spring(dampingRatio = 0.6f, stiffness = 380f)), label = "kieselBar")
            val fill by animateColorAsState(if (active) KieselCard else Color.Transparent, ytStateSpec(), label = "kieselRow")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .ytPressable(RoundedCornerShape(16.dp), squish = true) { seek(chapter) }
                    .drawBehind { drawRect(fill) }
                    .padding(8.dp),
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(54.dp)
                        .graphicsLayer {
                            scaleY = bar
                            alpha = bar
                        }
                        .clip(RoundedCornerShape(2.dp))
                        .background(KieselPeach),
                )
                ChapterImage(
                    imageLoader, chapter, fallback,
                    Modifier
                        .padding(start = 8.dp)
                        .width(96.dp)
                        .height(54.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        chapter.title,
                        color = KieselInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            time(chapter.start),
                            color = KieselBlack,
                            style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 10.5.sp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(KieselLavender)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                        if (lengths[i] > 0) {
                            Text(time(lengths[i]), color = KieselGrey, style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 11.5.sp), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Up next

/** The heading of the classic "up next" list below, in the type of the design. */
@Composable
internal fun WatchUpNextTitle(palette: YtPalette) {
    val text = stringResource(R.string.yt_watch_up_next)
    val padding = Modifier.padding(start = 13.dp, end = 13.dp, top = 18.dp, bottom = 2.dp)
    when (palette.signature) {
        YtSignature.EDITORIAL -> Column(Modifier.padding(start = 13.dp, end = 13.dp, top = 20.dp, bottom = 4.dp)) {
            Text(
                text.uppercase(),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 2.sp),
            )
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(palette.text),
            )
        }
        YtSignature.AFTER -> Text(
            text.uppercase(),
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.robotoCondensed, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp),
            modifier = padding,
        )
        YtSignature.KIESEL -> Text(
            text,
            color = KieselInk,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp),
            modifier = padding,
        )
        // Synthwave: the retro face with a magenta glow
        YtSignature.SYNTHWAVE -> Text(
            text.uppercase(),
            style = synthwaveDisplay(15.sp, palette.text, spacing = 1.2.sp),
            modifier = padding,
        )
        else -> Text(
            text,
            color = palette.text,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 16.sp),
            modifier = padding,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Synthwave: neon chrome on the night

/**
 * Synthwave: a magenta pill with dark ink and a soft neon glow that lifts a little with the
 * level (the pulse's guarded light, ≤ 3 swings a second); subscribed it turns into night glass
 * with a cyan neon edge and cyan words.
 */
@Composable
private fun SynthwaveSubscribe(palette: YtPalette, subscribed: Boolean, label: String, toggle: () -> Unit) {
    val fill by animateColorAsState(if (subscribed) palette.surface else palette.accent, ytStateSpec(), label = "synthwaveFill")
    val ink by animateColorAsState(if (subscribed) palette.lime else palette.onAccent, ytStateSpec(), label = "synthwaveInk")
    val shape = RoundedCornerShape(50)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .ytSelectBounce(subscribed)
            .drawWithCache {
                val r = size.height / 2f
                val wide = Stroke(10.dp.toPx())
                val near = Stroke(5.dp.toPx())
                onDrawBehind {
                    if (subscribed) return@onDrawBehind
                    val light = 0.8f + 0.5f * FrequencyPulse.glow.floatValue
                    drawRoundRect(palette.accent, cornerRadius = CornerRadius(r), style = wide, alpha = 0.07f * light)
                    drawRoundRect(palette.accent, cornerRadius = CornerRadius(r), style = near, alpha = 0.13f * light)
                }
            }
            .clip(shape)
            .drawBehind { drawRect(fill) }
            .then(if (subscribed) Modifier.border(1.dp, palette.lime, shape) else Modifier)
            .clickable(onClick = toggle)
            .height(36.dp)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubscribedCheck(subscribed, ink)
            Text(label, color = ink, style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
        }
    }
}

/**
 * Synthwave: the actions as neon tiles in one even row (nothing cut at the edge): night glass
 * with an edge in the tile's neon (magenta, cyan, orange, violet) and its glow, the icon in the
 * neon; pressed, the glow grows. The captions stay still under them.
 */
@Composable
private fun SynthwaveActions(palette: YtPalette, actions: List<WatchAction>, modifier: Modifier) {
    val haptics = rememberYtHaptics()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp),
    ) {
        actions.forEachIndexed { i, action ->
            val press = remember { MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            val glow by animateFloatAsState(if (pressed) 1.6f else 0.8f, ytSpec(spring(dampingRatio = 0.8f, stiffness = 500f)), label = "synthwaveGlow")
            val neon = synthwaveTag(palette, i)
            val shape = RoundedCornerShape(12.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .then(action.gesture)
                        .fillMaxWidth()
                        .height(48.dp)
                        .ytPressScale(press)
                        .drawWithCache {
                            val r = 12.dp.toPx()
                            val wide = Stroke(9.dp.toPx())
                            val near = Stroke(4.dp.toPx())
                            onDrawBehind {
                                drawRoundRect(neon, cornerRadius = CornerRadius(r), style = wide, alpha = 0.05f * glow)
                                drawRoundRect(neon, cornerRadius = CornerRadius(r), style = near, alpha = 0.1f * glow)
                            }
                        }
                        .clip(shape)
                        .background(palette.raised)
                        .border(1.dp, neon.copy(alpha = 0.85f), shape)
                        .clickable(interactionSource = press, indication = null) {
                            haptics.tick()
                            action.onClick()
                        },
                ) {
                    Icon(painterResource(action.icon), contentDescription = null, tint = neon, modifier = Modifier.size(21.dp))
                }
                Text(
                    text = action.label,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 11.sp),
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

/** Synthwave: the labels in Geist, a magenta neon tube glides under the chosen one. */
@Composable
private fun SynthwaveTabs(palette: YtPalette, labels: List<String>, selected: Int, pick: (Int) -> Unit, modifier: Modifier) {
    val pos by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), ytSpec(spring(dampingRatio = 0.85f, stiffness = 380f)), label = "synthwaveTab")
    val thickness = with(LocalDensity.current) { 6.dp.roundToPx() }
    val horizon = remember(palette) { Brush.horizontalGradient(listOf(palette.accent.copy(alpha = 0f), palette.accent.copy(alpha = 0.5f), palette.lime.copy(alpha = 0.5f), palette.lime.copy(alpha = 0f))) }
    Column(modifier.fillMaxWidth()) {
        TabLabels(labels, selected, pick, Modifier.height(42.dp)) { label, active ->
            val ink by animateColorAsState(if (active) palette.text else palette.textSecondary, ytStateSpec(), label = "synthwaveTabInk")
            Text(label, color = ink, textAlign = TextAlign.Center, maxLines = 1, style = TextStyle(fontFamily = YtFonts.geist, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, fontSize = 14.sp))
        }
        Box(
            Modifier
                .glideTo({ pos }, labels.size, 0.6f, thickness)
                .drawBehind {
                    val y = size.height / 2f
                    drawLine(palette.accent, Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round, alpha = 0.25f)
                    drawLine(palette.accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                },
        )
        Box(
            Modifier
                .padding(horizontal = 13.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(horizon),
        )
    }
}

/**
 * Synthwave: the chapters along a neon road: a cyan line runs down the left with a light at
 * each chapter; the rows are night glass, the current one edged and lit in magenta. Times in
 * Geist Mono.
 */
@Composable
private fun SynthwaveChapters(palette: YtPalette, imageLoader: ImageLoader, chapters: List<ChapterSegment>, lengths: List<Long>, fallback: String?, activeIndex: Int, seek: (ChapterSegment) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(start = 13.dp, end = 13.dp, top = 8.dp, bottom = 4.dp)
            .drawBehind {
                // the road: a cyan line down the left edge, fading out at both ends
                val x = 4.dp.toPx()
                drawLine(
                    Brush.verticalGradient(listOf(palette.lime.copy(alpha = 0f), palette.lime.copy(alpha = 0.7f), palette.lime.copy(alpha = 0f))),
                    Offset(x, 0f),
                    Offset(x, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            },
    ) {
        chapters.forEachIndexed { index, chapter ->
            val active = index == activeIndex
            val shape = RoundedCornerShape(12.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .then(if (active) Modifier.synthwaveGlow(CircleShape, palette.accent, 4.dp) else Modifier)
                        .clip(CircleShape)
                        .background(if (active) palette.accent else palette.lime),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .weight(1f)
                        .then(if (active) Modifier.synthwaveGlow(shape, palette.accent, 5.dp) else Modifier)
                        .clip(shape)
                        .background(SynthwaveGlass.fill)
                        .border(1.dp, if (active) palette.accent else palette.lime.copy(alpha = 0.3f), shape)
                        .clickable { seek(chapter) }
                        .padding(6.dp),
                ) {
                    ChapterImage(
                        imageLoader, chapter, fallback,
                        Modifier
                            .width(92.dp)
                            .height(52.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(time(chapter.start), color = if (active) palette.accent else palette.lime, style = TextStyle(fontFamily = YtFonts.geistMono, fontWeight = FontWeight.Medium, fontSize = 12.sp))
                        Text(
                            chapter.title,
                            color = palette.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                        )
                    }
                    if (lengths[index] > 0) {
                        Text(time(lengths[index]), color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.geistMono, fontSize = 12.sp), modifier = Modifier.padding(start = 8.dp, end = 4.dp))
                    }
                }
            }
        }
    }
}
