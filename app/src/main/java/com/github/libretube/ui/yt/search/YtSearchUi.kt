package com.github.libretube.ui.yt.search

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.ui.yt.BlobShape
import com.github.libretube.ui.yt.CookieShape
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.SynthwaveGlass
import com.github.libretube.ui.yt.YtEaseInOut
import com.github.libretube.ui.yt.YtEaseOut
import com.github.libretube.ui.yt.YtFonts
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.rememberYtHaptics
import com.github.libretube.ui.yt.ytDayNight
import com.github.libretube.ui.yt.ytNight
import com.github.libretube.ui.yt.ytTestRoot
import com.github.libretube.ui.yt.search.YtSearchRules.lerp
import com.github.libretube.ui.yt.search.YtSearchRules.phase
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * YT: how each design's search opens, looks and leaves. One progress (0 closed, 1 open) drives
 * it all: the design's field grows, slides, prints or thaws along it, the entries follow one
 * after another, and on closing the same runs backwards, the entries first. Every per-frame
 * value is read in a graphics layer or draw lambda, so the frames only redraw.
 *
 *   YouTube neu  a calm full-width field slides down, the results' filters under it
 *   Movo         a lime tile drops in, the past searches as coloured tiles
 *   After        letterbox bars close in, the field in the top bar, the lines in condensed caps
 *   Side         a conversation: the app asks, your query is the reply bubble, the past ones answers
 *   Clay         a dough field stretches out of the button and settles with a squash
 *   Editorial    a printed search line: the rule runs across, the past searches numbered in mono
 *   Glas         a liquid glass capsule grows out of where you tapped, glass chips below
 *   Pop          a blob pops out of the button into a speech bubble, blob chips below
 *   Formen       the cookie flies out of the button and morphs into the field
 *   Frost        a frost panel thaws open from the top, the screen behind blurs
 *   Kiesel       a black capsule grows out of the header, the past searches fall in as pebbles
 *   Frequency    Formen's cookie flies out of the button into an aqua field, YouTube neu's
 *                filters under it, the past searches as coral, violet and amber chips
 *   Synthwave    the field switches on like an old screen (a neon line that opens up), a neon
 *                edge from magenta to cyan, the past searches as night chips with neon rims
 */

private enum class EntryLayout { ROWS, CHIPS, TILES }

private class SearchLook(
    val fieldFont: FontFamily,
    val fieldWeight: FontWeight,
    val fieldSize: TextUnit,
    val fieldSpacing: TextUnit,
    val fieldHeight: Dp,
    val rowFont: FontFamily,
    val rowWeight: FontWeight,
    val rowSize: TextUnit,
    val rowCaps: Boolean,
    val sidePad: Dp,
    val layout: EntryLayout,
    val blur: Dp,
    val enter: AnimationSpec<Float>,
    /** Closing by back. */
    val exit: AnimationSpec<Float>,
    /** Closing by a search: quicker, the results are already coming. */
    val leave: AnimationSpec<Float>,
)

private fun soft(damping: Float, stiffness: Float): AnimationSpec<Float> = spring(dampingRatio = damping, stiffness = stiffness)

private fun lookOf(kind: YtSearchKind, palette: YtPalette): SearchLook {
    val quickOut = tween<Float>(170, easing = FastOutLinearInEasing)
    val out = tween<Float>(230, easing = FastOutLinearInEasing)
    return when (kind) {
        YtSearchKind.YOUTUBE -> SearchLook(palette.font, FontWeight.Medium, 17.sp, 0.sp, 48.dp, palette.font, FontWeight.Normal, 16.sp, false,
            12.dp, EntryLayout.ROWS, 0.dp, tween(260, easing = YtEaseOut), out, quickOut)
        YtSearchKind.MOVO -> SearchLook(YtFonts.geist, FontWeight.ExtraBold, 19.sp, 0.sp, 56.dp, YtFonts.geist, FontWeight.Bold, 15.sp, false,
            14.dp, EntryLayout.TILES, 0.dp, soft(0.62f, 420f), out, quickOut)
        YtSearchKind.AFTER -> SearchLook(YtFonts.archivoNarrow, FontWeight.Bold, 24.sp, 1.sp, 56.dp, YtFonts.archivoNarrow, FontWeight.Bold, 22.sp, true,
            22.dp, EntryLayout.ROWS, 0.dp, tween(620, easing = YtEaseInOut), tween(420, easing = YtEaseInOut), tween(320, easing = FastOutLinearInEasing))
        YtSearchKind.SIDE -> SearchLook(palette.font, FontWeight.Medium, 16.sp, 0.sp, 50.dp, palette.font, FontWeight.Normal, 15.sp, false,
            16.dp, EntryLayout.ROWS, 0.dp, soft(0.8f, 260f), out, quickOut)
        YtSearchKind.CLAY -> SearchLook(palette.font, FontWeight.Bold, 17.sp, 0.sp, 56.dp, palette.font, FontWeight.SemiBold, 15.sp, false,
            16.dp, EntryLayout.CHIPS, 0.dp, soft(0.42f, 300f), out, quickOut)
        YtSearchKind.EDITORIAL -> SearchLook(YtFonts.inter, FontWeight.SemiBold, 26.sp, (-0.3).sp, 56.dp, YtFonts.inter, FontWeight.Medium, 18.sp, false,
            20.dp, EntryLayout.ROWS, 0.dp, tween(220, easing = YtEaseOut), tween(160, easing = FastOutLinearInEasing), tween(140))
        YtSearchKind.GLASS -> SearchLook(palette.font, FontWeight.Medium, 17.sp, 0.sp, 52.dp, palette.font, FontWeight.Medium, 15.sp, false,
            16.dp, EntryLayout.CHIPS, 26.dp, soft(0.7f, 230f), out, quickOut)
        YtSearchKind.POP -> SearchLook(palette.font, FontWeight.ExtraBold, 18.sp, 0.sp, 64.dp, palette.font, FontWeight.Bold, 15.sp, false,
            16.dp, EntryLayout.CHIPS, 0.dp, soft(0.45f, 380f), out, quickOut)
        YtSearchKind.SHAPES -> SearchLook(palette.font, FontWeight.Medium, 17.sp, 0.sp, 54.dp, palette.font, FontWeight.Medium, 15.sp, false,
            16.dp, EntryLayout.CHIPS, 0.dp, soft(0.68f, 210f), out, quickOut)
        // Frequency: YouTube neu's type, Formen's chips and spring
        YtSearchKind.FREQUENCY -> SearchLook(YtFonts.geist, FontWeight.Medium, 17.sp, 0.sp, 54.dp, YtFonts.geist, FontWeight.Medium, 15.sp, false,
            16.dp, EntryLayout.CHIPS, 0.dp, soft(0.66f, 230f), out, quickOut)
        // Synthwave: Geist to read, a calm glide, neon chips
        YtSearchKind.SYNTHWAVE -> SearchLook(YtFonts.geist, FontWeight.Medium, 17.sp, 0.sp, 52.dp, YtFonts.geist, FontWeight.Medium, 15.sp, false,
            16.dp, EntryLayout.CHIPS, 0.dp, soft(0.86f, 240f), out, quickOut)
        YtSearchKind.FROST -> SearchLook(palette.font, FontWeight.Medium, 17.sp, 0.sp, 50.dp, palette.font, FontWeight.Normal, 15.sp, false,
            14.dp, EntryLayout.ROWS, 30.dp, tween(520, easing = YtEaseOut), tween(300, easing = FastOutLinearInEasing), quickOut)
        YtSearchKind.KIESEL -> SearchLook(palette.font, FontWeight.SemiBold, 17.sp, 0.sp, 52.dp, palette.font, FontWeight.Bold, 15.sp, false,
            16.dp, EntryLayout.CHIPS, 0.dp, soft(0.6f, 340f), out, quickOut)
    }
}

/** Where the field is laid out, in window px, for the transforms that start at the button. */
private class FieldBox {
    var rect: Rect = Rect.Zero
}

private class SearchActions(
    val submit: (String) -> Unit,
    val pick: (YtSearchItem) -> Unit,
    val fill: (YtSearchItem) -> Unit,
    val delete: (YtSearchItem) -> Unit,
    val clear: () -> Unit,
    val back: () -> Unit,
)

@Composable
internal fun YtSearchLayer(session: YtSearchSession) {
    val palette = session.palette
    val kind = remember(palette) { YtSearchKind.valueOf(palette.signature.name) }
    val inks = remember(kind) { YtSearchPalette.inks(kind, ytNight) }
    val look = remember(kind, palette) { lookOf(kind, palette) }
    val motion = LocalYtMotion.current
    val haptics = rememberYtHaptics()
    val density = LocalDensity.current
    var value by remember { mutableStateOf(TextFieldValue(session.initial, TextRange(session.initial.length))) }
    var filter by remember { mutableStateOf("all") }
    val data = rememberYtSearchData { value.text }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val k = session.progress
    val box = remember { FieldBox() }

    LaunchedEffect(Unit) {
        if (motion) k.animateTo(1f, look.enter) else k.snapTo(1f)
        // the field has landed
        if (!session.closing) haptics.tick()
    }
    LaunchedEffect(Unit) {
        // the keyboard comes with the field; focus needs the field laid out first
        withFrameNanos { }
        try {
            focus.requestFocus()
        } catch (_: IllegalStateException) {
            // not attached yet; the field can still be tapped
        }
        keyboard?.show()
    }
    LaunchedEffect(Unit) {
        snapshotFlow { session.closing }.first { it }
        keyboard?.hide()
        if (motion) k.animateTo(0f, if (session.submitted) look.leave else look.exit) else k.snapTo(0f)
        YtSearchEntry.finish(session)
    }
    val blurPx = with(density) { look.blur.toPx() }
    if (blurPx > 0f) {
        LaunchedEffect(Unit) {
            // in steps of 2 px: a new blur for every frame would cost more than it shows
            snapshotFlow { (k.value.coerceIn(0f, 1f) * blurPx / 2f).roundToInt() * 2f }
                .distinctUntilChanged()
                .collect { YtSearchEntry.blurBehind(it) }
        }
    }

    val actions = remember(session) {
        SearchActions(
            submit = { text ->
                if (YtSearchRules.query(text) != null) {
                    haptics.confirm()
                    YtSearchEntry.submit(session, text, filter)
                } else {
                    haptics.reject()
                }
            },
            pick = { item ->
                value = TextFieldValue(item.text, TextRange(item.text.length))
                haptics.confirm()
                YtSearchEntry.submit(session, item.text, filter)
            },
            fill = { item ->
                haptics.tick()
                value = TextFieldValue(item.text, TextRange(item.text.length))
                try {
                    focus.requestFocus()
                } catch (_: IllegalStateException) {
                }
            },
            delete = { item ->
                haptics.land(0.25f)
                data.delete(item.text)
            },
            clear = {
                haptics.tick()
                value = TextFieldValue("")
                try {
                    focus.requestFocus()
                } catch (_: IllegalStateException) {
                }
            },
            back = {
                haptics.tick()
                YtSearchEntry.close()
            },
        )
    }

    val topDp = with(density) { session.topInset.toDp() }
    val bottomDp = with(density) { session.bottomInset.toDp() }
    val startPx = with(density) { 44.dp.toPx() }
    val items = data.items(value.text)

    Box(Modifier.fillMaxSize().ytTestRoot().testTag("yt_search_layer")) {
        Scrim(kind, inks, k::value, actions.back)
        if (kind == YtSearchKind.AFTER) Letterbox(k::value, topDp, bottomDp)

        val column: Modifier = when (kind) {
            YtSearchKind.FROST -> Modifier
                .fillMaxWidth()
                .padding(bottom = bottomDp + 96.dp)
                .frostPanel(inks, k::value)
                .padding(top = topDp + 10.dp, bottom = 14.dp)
            YtSearchKind.AFTER -> Modifier.fillMaxSize().padding(top = topDp + 6.dp, bottom = bottomDp + 64.dp)
            else -> Modifier.fillMaxSize().padding(top = topDp + 8.dp, bottom = bottomDp)
        }
        Column(column) {
            if (kind == YtSearchKind.SIDE) SidePrompt(look, inks, palette, k::value)
            if (kind == YtSearchKind.EDITORIAL) EditorialMasthead(inks, k::value)
            Field(
                kind = kind,
                look = look,
                inks = inks,
                value = value,
                onValue = { value = it },
                focus = focus,
                progress = k::value,
                origin = session.origin,
                box = box,
                startPx = startPx,
                actions = actions,
            )
            if (kind == YtSearchKind.YOUTUBE || kind == YtSearchKind.FREQUENCY || kind == YtSearchKind.SYNTHWAVE) {
                // Frequency: YouTube neu's filters, the chosen one on a coral pill; Synthwave: on
                // a magenta pill
                val marked = kind == YtSearchKind.FREQUENCY || kind == YtSearchKind.SYNTHWAVE
                val on = if (marked) inks.mark else inks.fieldText
                val onText = if (marked) YtSearchPalette.onFill(inks.mark) else inks.scrim
                Filters(inks, look, filter, k::value, Color(on), Color(onText).copy(alpha = 1f)) {
                    if (it != filter) haptics.tick()
                    filter = it
                }
            }
            Entries(
                kind = kind,
                look = look,
                inks = inks,
                items = items,
                typed = value.text,
                progress = k::value,
                motion = motion,
                actions = actions,
                modifier = Modifier.weight(1f, fill = kind != YtSearchKind.FROST),
            )
        }
    }
}

/** The screen behind: dimmed (or covered) along the opening; a tap on it closes. */
@Composable
private fun Scrim(kind: YtSearchKind, inks: YtSearchInks, progress: () -> Float, onTap: () -> Unit) {
    val until = when (kind) {
        YtSearchKind.EDITORIAL -> 0.45f
        YtSearchKind.AFTER -> 0.8f
        else -> 0.6f
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = phase(progress(), 0f, until) }
            .background(Color(inks.scrim))
            .clearAndSetSemantics { }
            .pointerInput(Unit) { detectTapGestures { onTap() } },
    )
}

/** After: two black bars close in from the top and the bottom, as in a cinema. */
@Composable
private fun Letterbox(progress: () -> Float, topDp: Dp, bottomDp: Dp) {
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(topDp + 80.dp)
                .graphicsLayer { translationY = -(1f - phase(progress(), 0f, 0.7f)) * size.height }
                .background(Color.Black),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomDp + 56.dp)
                .graphicsLayer { translationY = (1f - phase(progress(), 0f, 0.7f)) * size.height }
                .background(Color.Black),
        )
    }
}

/** Frost: the panel thaws open from the top; its lower edge is a band of melting frost. */
private fun Modifier.frostPanel(inks: YtSearchInks, progress: () -> Float): Modifier = this
    .graphicsLayer {
        val t = progress().coerceIn(0f, 1f)
        shape = RevealShape(t, 28.dp.toPx())
        clip = true
    }
    .drawBehind {
        val t = progress().coerceIn(0f, 1f)
        drawRect(Color(inks.panel))
        val band = 36.dp.toPx()
        val edge = size.height * t
        drawRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.22f * (1f - t * 0.6f))), startY = edge - band, endY = edge),
            topLeft = Offset(0f, edge - band),
            size = Size(size.width, band),
        )
    }

/** The top part of a box, [t] of its height, with rounded lower corners. */
private class RevealShape(private val t: Float, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val h = (size.height * t).coerceAtLeast(0f)
        val r = minOf(radius, h / 2f)
        return Outline.Rounded(
            RoundRect(
                left = 0f, top = 0f, right = size.width, bottom = h,
                topLeftCornerRadius = CornerRadius.Zero, topRightCornerRadius = CornerRadius.Zero,
                bottomRightCornerRadius = CornerRadius(r), bottomLeftCornerRadius = CornerRadius(r),
            ),
        )
    }
}

/** Formen: the cookie on its way to the field ([YtSearchRules.morphPoints]). */
private class CookieMorphShape(private val t: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val pts = YtSearchRules.morphPoints(size.width, size.height, 9, 0.07f, t)
        val path = Path()
        for (i in pts.indices step 2) {
            if (i == 0) path.moveTo(pts[i], pts[i + 1]) else path.lineTo(pts[i], pts[i + 1])
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** Side: the app opens the conversation with a question. */
@Composable
private fun SidePrompt(look: SearchLook, inks: YtSearchInks, palette: YtPalette, progress: () -> Float) {
    Box(
        Modifier
            .padding(start = look.sidePad, end = 64.dp, bottom = 10.dp)
            .graphicsLayer {
                val t = phase(progress(), 0f, 0.55f)
                transformOrigin = TransformOrigin(0f, 1f)
                scaleX = lerp(0.7f, 1f, t)
                scaleY = lerp(0.7f, 1f, t)
                alpha = t
            }
            .background(Color(inks.chip), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            stringResource(R.string.yt_r12_search_side_prompt),
            color = Color(inks.chipText),
            style = TextStyle(fontFamily = palette.font, fontSize = 15.sp),
        )
    }
}

/** Editorial: the line above the field, set in mono like a section mark. */
@Composable
private fun EditorialMasthead(inks: YtSearchInks, progress: () -> Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 2.dp)
            .graphicsLayer { alpha = phase(progress(), 0.2f, 0.7f) },
    ) {
        Box(Modifier.size(8.dp).background(Color(inks.mark)))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.yt_r12_search_run).uppercase(),
            color = Color(inks.rowText),
            style = TextStyle(fontFamily = YtFonts.geistMono, fontSize = 12.sp, letterSpacing = 2.sp),
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun Field(
    kind: YtSearchKind,
    look: SearchLook,
    inks: YtSearchInks,
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    focus: FocusRequester,
    progress: () -> Float,
    origin: Offset?,
    box: FieldBox,
    startPx: Float,
    actions: SearchActions,
) {
    val hint = stringResource(R.string.yt_r12_search_hint)
    val tail = if (kind == YtSearchKind.POP) 10.dp else 0.dp
    val outer = when (kind) {
        YtSearchKind.SIDE -> Modifier.padding(start = 48.dp, end = look.sidePad)
        YtSearchKind.EDITORIAL, YtSearchKind.AFTER -> Modifier.padding(horizontal = look.sidePad - 8.dp)
        else -> Modifier.padding(horizontal = look.sidePad)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = outer
            .fillMaxWidth()
            .onGloballyPositioned { box.rect = it.boundsInWindow() }
            .fieldMotion(kind, progress, origin, box, startPx)
            .fieldSurface(kind, inks, progress, tail)
            .heightIn(min = look.fieldHeight + tail)
            .padding(top = tail)
            .padding(horizontal = 4.dp),
    ) {
        val content = Modifier.graphicsLayer {
            alpha = when (kind) {
                YtSearchKind.SHAPES, YtSearchKind.FREQUENCY -> phase(progress(), 0.55f, 0.95f)
                YtSearchKind.SYNTHWAVE -> phase(progress(), 0.4f, 0.85f)
                YtSearchKind.GLASS, YtSearchKind.CLAY, YtSearchKind.POP, YtSearchKind.KIESEL -> phase(progress(), 0.35f, 0.85f)
                else -> 1f
            }
        }
        IconSpot(R.drawable.ic_yt_chevron_left, stringResource(R.string.yt_r12_search_back), Color(inks.fieldText), content.testTag("yt_search_back"), actions.back)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = look.fieldFont,
                fontWeight = look.fieldWeight,
                fontSize = look.fieldSize,
                letterSpacing = look.fieldSpacing,
                color = Color(inks.fieldText),
            ),
            cursorBrush = SolidColor(Color(if (kind == YtSearchKind.MOVO || kind == YtSearchKind.SHAPES || kind == YtSearchKind.FREQUENCY || kind == YtSearchKind.SIDE || kind == YtSearchKind.POP || kind == YtSearchKind.SYNTHWAVE) inks.fieldText else inks.mark)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.submit(value.text) }),
            modifier = content
                .weight(1f)
                .padding(horizontal = 4.dp)
                .focusRequester(focus)
                .testTag("yt_search_field")
                .semantics { contentDescription = hint },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text(
                            hint,
                            color = Color(inks.fieldHint),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontFamily = look.fieldFont,
                                fontWeight = if (kind == YtSearchKind.EDITORIAL) FontWeight.Normal else look.fieldWeight,
                                fontSize = look.fieldSize,
                                letterSpacing = look.fieldSpacing,
                            ),
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                    inner()
                }
            },
        )
        if (value.text.isNotEmpty()) {
            IconSpot(R.drawable.ic_close, stringResource(R.string.yt_r12_search_clear), Color(inks.fieldText), content.testTag("yt_search_clear"), actions.clear)
        }
    }
}

/** An icon with a 44 dp touch target. */
@Composable
private fun IconSpot(icon: Int, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** How the field gets from the button (or the top) to its place along the opening. */
private fun Modifier.fieldMotion(kind: YtSearchKind, progress: () -> Float, origin: Offset?, box: FieldBox, startPx: Float): Modifier =
    graphicsLayer {
        val t = progress()
        val w = size.width
        val h = size.height
        val r = box.rect
        fun fromOrigin() {
            transformOrigin = if (origin != null && r.width > 0f && r.height > 0f) {
                TransformOrigin(YtSearchRules.pivot(origin.x, r.left, r.width), YtSearchRules.pivot(origin.y, r.top, r.height))
            } else {
                // the header's search button sits at the top right
                TransformOrigin(0.94f, -0.2f)
            }
        }
        when (kind) {
            YtSearchKind.YOUTUBE -> {
                translationY = -(1f - t) * (h + 16.dp.toPx())
                alpha = phase(t, 0f, 0.6f)
            }
            YtSearchKind.MOVO -> {
                transformOrigin = TransformOrigin(0f, 0f)
                translationY = -(1f - t) * (h * 1.6f)
                rotationZ = (1f - t) * -5f
                alpha = phase(t, 0f, 0.3f)
            }
            YtSearchKind.AFTER -> {
                val p = phase(t, 0.45f, 1f)
                alpha = p
                translationY = (1f - p) * 10.dp.toPx()
            }
            YtSearchKind.SIDE -> {
                transformOrigin = TransformOrigin(1f, 1f)
                val p = t.coerceAtLeast(0f)
                scaleX = lerp(0.6f, 1f, p)
                scaleY = lerp(0.6f, 1f, p)
                translationX = (1f - p) * w * 0.2f
                alpha = phase(t, 0.1f, 0.5f)
            }
            YtSearchKind.EDITORIAL -> alpha = phase(t, 0f, 0.25f)
            YtSearchKind.FROST -> alpha = phase(t, 0.15f, 0.6f)
            YtSearchKind.GLASS -> {
                fromOrigin()
                scaleX = lerp(YtSearchRules.startScale(startPx, w), 1f, t)
                // a drop that swells on its way out
                scaleY = lerp(YtSearchRules.startScale(startPx, h), 1f, t) * (1f + 0.08f * sin(PI.toFloat() * t.coerceIn(0f, 1f)))
                alpha = phase(t, 0f, 0.12f)
            }
            YtSearchKind.CLAY -> {
                fromOrigin()
                // the dough stretches sideways first, then rises, and squashes when it overshoots
                scaleX = lerp(YtSearchRules.startScale(startPx, w), 1f, t)
                val rise = phase(t, 0.12f, 1f)
                val squash = if (t > 1f) 1f - (t - 1f) * 1.4f else 1f
                scaleY = lerp(YtSearchRules.startScale(startPx, h), 1f, rise) * squash
                alpha = phase(t, 0f, 0.12f)
                shape = RoundedCornerShape(28.dp)
                shadowElevation = 10.dp.toPx() * t.coerceIn(0f, 1f)
                ambientShadowColor = Color(0xFF9D6A6C)
                spotShadowColor = Color(0xFF9D6A6C)
            }
            YtSearchKind.POP -> {
                fromOrigin()
                val s = lerp(YtSearchRules.startScale(startPx, w), 1f, t)
                scaleX = s
                scaleY = lerp(YtSearchRules.startScale(startPx, h), 1f, t)
                rotationZ = (1f - t) * -8f
                alpha = phase(t, 0f, 0.12f)
            }
            YtSearchKind.SHAPES, YtSearchKind.FREQUENCY -> {
                // the cookie flies from the button to the field's middle while it opens up
                val o = origin ?: Offset(r.right - 24.dp.toPx(), r.top - 24.dp.toPx())
                if (r.width > 0f) {
                    translationX = (o.x - r.center.x) * (1f - t)
                    translationY = (o.y - r.center.y) * (1f - t)
                }
                rotationZ = (1f - t) * -150f
                val s = lerp(0.8f, 1f, t)
                scaleX = s
                scaleY = s
                alpha = phase(t, 0f, 0.1f)
            }
            YtSearchKind.KIESEL -> {
                fromOrigin()
                scaleX = lerp(YtSearchRules.startScale(h, w), 1f, t)
                alpha = phase(t, 0f, 0.1f)
            }
            YtSearchKind.SYNTHWAVE -> {
                // an old screen switching on: a thin neon line opens sideways, then up and down
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                scaleX = lerp(0.15f, 1f, phase(t, 0f, 0.55f))
                scaleY = lerp(0.06f, 1f, phase(t, 0.4f, 1f))
                alpha = phase(t, 0f, 0.1f)
            }
        }
    }

/** The lit top and the shaded foot of Clay's dough (darker dough at night). */
private val ClayDoughTop: Color get() = ytDayNight(Color(0xFFFFF6EE), Color(0xFF574239))
private val ClayDoughFoot: Color get() = ytDayNight(Color(0xFFF2D7C4), Color(0xFF3A2C26))

/** The field's own surface, drawn under its text. */
private fun Modifier.fieldSurface(kind: YtSearchKind, inks: YtSearchInks, progress: () -> Float, tail: Dp): Modifier = drawBehind {
    val t = progress()
    val fill = Color(inks.field)
    when (kind) {
        YtSearchKind.YOUTUBE, YtSearchKind.FROST, YtSearchKind.KIESEL -> drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2f))
        YtSearchKind.MOVO -> drawRoundRect(fill, cornerRadius = CornerRadius(16.dp.toPx()))
        YtSearchKind.AFTER -> {
            // a thin tangerine rule that opens from the middle
            val p = phase(t, 0.5f, 1f)
            val w = size.width * p
            drawRect(Color(inks.mark), topLeft = Offset((size.width - w) / 2f, size.height - 2.dp.toPx()), size = Size(w, 1.5.dp.toPx()))
        }
        YtSearchKind.EDITORIAL -> {
            // the printed line: a heavy rule runs across under the type
            val p = phase(t, 0f, 0.8f)
            drawRect(Color(inks.fieldText), topLeft = Offset(0f, size.height - 3.dp.toPx()), size = Size(size.width * p, 3.dp.toPx()))
        }
        YtSearchKind.SIDE -> {
            val outline = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp)
                .createOutline(size, layoutDirection, this)
            drawOutline(outline, fill)
        }
        YtSearchKind.CLAY -> {
            val r = CornerRadius(28.dp.toPx())
            drawRoundRect(Brush.verticalGradient(listOf(ClayDoughTop, fill, ClayDoughFoot)), cornerRadius = r)
            // the light from above on the dough's rim
            drawRoundRect(Color.White.copy(alpha = if (ytNight) 0.12f else 0.7f), cornerRadius = r, style = Stroke(1.5.dp.toPx()))
        }
        YtSearchKind.GLASS -> {
            val r = CornerRadius(size.height / 2f)
            // at night the glass is dark teal: its light and rim are fainter mint
            val light = ytDayNight(Color.White.copy(alpha = 0.78f), Color(0xFFCFEDE7).copy(alpha = 0.18f))
            drawRoundRect(Brush.verticalGradient(listOf(light, fill)), cornerRadius = r)
            drawRoundRect(ytDayNight(Color.White.copy(alpha = 0.95f), Color(0xFFCFEDE7).copy(alpha = 0.35f)), cornerRadius = r, style = Stroke(1.5.dp.toPx()))
            // the bright line along the top, as light caught in the glass
            val inset = size.height / 2f
            drawLine(
                Color.White,
                Offset(inset, 2.5.dp.toPx()),
                Offset(size.width - inset, 2.5.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
                alpha = if (ytNight) 0.4f else 0.9f,
            )
        }
        YtSearchKind.POP -> drawBubble(fill, tail.toPx())
        YtSearchKind.SHAPES, YtSearchKind.FREQUENCY -> {
            val outline = CookieMorphShape(t.coerceIn(0f, 1f)).createOutline(size, layoutDirection, this)
            drawOutline(outline, fill)
        }
        YtSearchKind.SYNTHWAVE -> {
            // night glass with a neon edge from magenta to cyan and a soft glow around it
            val r = CornerRadius(size.height / 2f)
            val edge = Brush.horizontalGradient(listOf(SynthwaveGlass.edgeStart, SynthwaveGlass.edgeEnd))
            drawRoundRect(edge, cornerRadius = r, style = Stroke(9.dp.toPx()), alpha = 0.1f)
            drawRoundRect(edge, cornerRadius = r, style = Stroke(5.dp.toPx()), alpha = 0.16f)
            drawRoundRect(fill, cornerRadius = r)
            drawRoundRect(edge, cornerRadius = r, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** Pop: the speech bubble, its tail pointing up at the button it came from. */
private fun DrawScope.drawBubble(fill: Color, tail: Float) {
    val top = tail
    val h = size.height - top
    drawRoundRect(fill, topLeft = Offset(0f, top), size = Size(size.width, h), cornerRadius = CornerRadius(minOf(h / 2f, 28.dp.toPx())))
    val x = size.width - 40.dp.toPx()
    val path = Path().apply {
        moveTo(x - 10.dp.toPx(), top + 1f)
        lineTo(x + 6.dp.toPx(), 0f)
        lineTo(x + 8.dp.toPx(), top + 1f)
        close()
    }
    drawPath(path, fill)
}

/** YouTube neu: the filters of the results screen, picked before the search. */
@Composable
private fun Filters(
    inks: YtSearchInks,
    look: SearchLook,
    selected: String,
    progress: () -> Float,
    onFill: Color,
    onText: Color,
    onPick: (String) -> Unit,
) {
    val labels = listOf(
        "all" to R.string.yt_r12_search_filter_all,
        "videos" to R.string.yt_r12_search_filter_videos,
        "channels" to R.string.yt_r12_search_filter_channels,
        "playlists" to R.string.yt_r12_search_filter_playlists,
        "music_songs" to R.string.yt_r12_search_filter_songs,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = look.sidePad)
            .padding(top = 12.dp, bottom = 4.dp),
    ) {
        labels.forEachIndexed { i, (key, label) ->
            val on = key == selected
            val shape = RoundedCornerShape(10.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .graphicsLayer {
                        val p = phase(progress(), 0.3f + i * 0.06f, 0.75f + i * 0.06f)
                        alpha = p
                        translationY = (1f - p) * -8.dp.toPx()
                    }
                    .heightIn(min = 36.dp)
                    .background(if (on) onFill else Color(inks.chip), shape)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(key) })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(label),
                    color = if (on) onText else Color(inks.chipText),
                    style = TextStyle(fontFamily = look.rowFont, fontWeight = FontWeight.Medium, fontSize = 14.sp),
                )
            }
        }
    }
}

/** The entries come in one after another along the opening; the design decides how. */
private fun Modifier.entryMotion(kind: YtSearchKind, progress: () -> Float, index: Int): Modifier = graphicsLayer {
    val start = 0.3f + minOf(index, 10) * 0.045f
    val p = phase(progress(), start, minOf(start + 0.4f, 1f))
    alpha = p
    when (kind) {
        YtSearchKind.KIESEL -> {
            // pebbles fall in and bounce
            translationY = -(1f - YtSearchRules.bounce(p)) * 56.dp.toPx()
            rotationZ = (1f - p) * (if (index % 2 == 0) 14f else -14f)
        }
        YtSearchKind.MOVO, YtSearchKind.POP, YtSearchKind.SHAPES, YtSearchKind.CLAY, YtSearchKind.FREQUENCY -> {
            val s = lerp(0.7f, 1f, p)
            scaleX = s
            scaleY = s
        }
        // Synthwave: the entries glide in from the left, as along a neon road
        YtSearchKind.SYNTHWAVE -> translationX = (1f - p) * -18.dp.toPx()
        YtSearchKind.SIDE -> {
            transformOrigin = TransformOrigin(0f, 1f)
            val s = lerp(0.8f, 1f, p)
            scaleX = s
            scaleY = s
            translationY = (1f - p) * 8.dp.toPx()
        }
        YtSearchKind.AFTER -> translationY = (1f - p) * 22.dp.toPx()
        YtSearchKind.EDITORIAL -> translationX = (1f - p) * -6.dp.toPx()
        else -> translationY = (1f - p) * 10.dp.toPx()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Entries(
    kind: YtSearchKind,
    look: SearchLook,
    inks: YtSearchInks,
    items: List<YtSearchItem>,
    typed: String,
    progress: () -> Float,
    motion: Boolean,
    actions: SearchActions,
    modifier: Modifier,
) {
    val history = items.filter { it.history }
    val suggestions = items.filterNot { it.history }
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("yt_search_list"),
        contentPadding = PaddingValues(start = look.sidePad, end = look.sidePad, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (kind == YtSearchKind.EDITORIAL || kind == YtSearchKind.AFTER) 0.dp else 8.dp),
    ) {
        if (items.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(if (YtSearchRules.query(typed) == null) R.string.yt_r12_search_empty else R.string.yt_r12_search_no_suggestions),
                    color = Color(inks.rowSecondary),
                    style = TextStyle(fontFamily = look.rowFont, fontSize = 14.sp),
                    modifier = Modifier.padding(vertical = 8.dp).entryMotion(kind, progress, 0),
                )
            }
            return@LazyColumn
        }
        when (look.layout) {
            EntryLayout.CHIPS -> {
                if (history.isNotEmpty()) {
                    item(key = "history_label") { SectionLabel(R.string.yt_r12_search_history, look, inks, progress) }
                    item(key = "history_chips") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            history.forEachIndexed { i, item -> Chip(kind, look, inks, item, i, progress, actions) }
                        }
                    }
                }
                itemsIndexed(suggestions, key = { _, it -> "s:" + it.text }) { i, item ->
                    EntryRow(kind, look, inks, item, history.size + i, progress, actions, if (motion) Modifier.animateItem() else Modifier)
                }
            }
            EntryLayout.TILES -> {
                val rows = items.chunked(2)
                itemsIndexed(rows, key = { _, row -> "t:" + row.joinToString("\u0000") { it.text } }) { i, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEachIndexed { j, item -> Tile(kind, look, inks, item, 2 * i + j, progress, actions, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            EntryLayout.ROWS -> {
                itemsIndexed(items, key = { _, it -> (if (it.history) "h:" else "s:") + it.text }) { i, item ->
                    EntryRow(kind, look, inks, item, i, progress, actions, if (motion) Modifier.animateItem() else Modifier)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: Int, look: SearchLook, inks: YtSearchInks, progress: () -> Float) {
    Text(
        stringResource(label),
        color = Color(inks.rowSecondary),
        style = TextStyle(fontFamily = look.rowFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
        modifier = Modifier
            .padding(bottom = 2.dp)
            .graphicsLayer { alpha = phase(progress(), 0.3f, 0.7f) }
            .semantics { heading() },
    )
}

/** Tap searches, long press deletes a past search; TalkBack gets both, and "into the field". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.entryClicks(item: YtSearchItem, actions: SearchActions): Modifier {
    val run = stringResource(R.string.yt_r12_search_run)
    val delete = stringResource(R.string.yt_r12_search_delete)
    val fill = stringResource(R.string.yt_r12_search_fill)
    return this
        .combinedClickable(
            onClickLabel = run,
            onLongClickLabel = if (item.history) delete else fill,
            onLongClick = { if (item.history) actions.delete(item) else actions.fill(item) },
            onClick = { actions.pick(item) },
        )
        .semantics {
            customActions = buildList {
                add(CustomAccessibilityAction(fill) { actions.fill(item); true })
                if (item.history) add(CustomAccessibilityAction(delete) { actions.delete(item); true })
            }
        }
}

@Composable
private fun EntryRow(
    kind: YtSearchKind,
    look: SearchLook,
    inks: YtSearchInks,
    item: YtSearchItem,
    index: Int,
    progress: () -> Float,
    actions: SearchActions,
    placement: Modifier,
) {
    val text = Color(inks.rowText)
    val secondary = Color(inks.rowSecondary)
    val bubble = kind == YtSearchKind.SIDE
    val shape = when (kind) {
        YtSearchKind.SIDE -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
        YtSearchKind.EDITORIAL, YtSearchKind.AFTER -> RoundedCornerShape(0.dp)
        else -> RoundedCornerShape(14.dp)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = placement
            .then(if (bubble) Modifier.padding(end = 48.dp) else Modifier.fillMaxWidth())
            .entryMotion(kind, progress, index)
            .then(if (bubble) Modifier.background(Color(inks.chip), shape) else Modifier)
            .then(
                when (kind) {
                    YtSearchKind.EDITORIAL -> Modifier.drawBehind {
                        drawRect(Color(inks.rowText), topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
                    }
                    YtSearchKind.AFTER -> Modifier.drawBehind {
                        drawRect(secondary.copy(alpha = 0.25f), topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
                    }
                    YtSearchKind.FROST -> Modifier.drawBehind {
                        drawRect(Color.White.copy(alpha = 0.12f), topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
                    }
                    else -> Modifier
                },
            )
            .entryClicks(item, actions)
            .testTag("yt_search_item")
            .heightIn(min = if (kind == YtSearchKind.AFTER) 60.dp else 48.dp)
            .padding(start = if (bubble) 14.dp else 2.dp, end = if (bubble) 6.dp else 0.dp, top = 4.dp, bottom = 4.dp),
    ) {
        when (kind) {
            YtSearchKind.EDITORIAL -> Text(
                YtSearchRules.number(index),
                color = Color(inks.mark),
                style = TextStyle(fontFamily = YtFonts.geistMono, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.width(36.dp).clearAndSetSemantics { },
            )
            YtSearchKind.AFTER -> Box(
                Modifier
                    .padding(end = 14.dp)
                    .size(6.dp)
                    .background(if (item.history) Color(inks.mark) else secondary.copy(alpha = 0.5f), CircleShape),
            )
            YtSearchKind.SIDE -> Unit
            else -> Icon(
                painterResource(if (item.history) R.drawable.ic_history else R.drawable.ic_yt_search),
                contentDescription = null,
                tint = secondary,
                modifier = Modifier.padding(start = 6.dp, end = 14.dp).size(20.dp),
            )
        }
        Text(
            if (look.rowCaps) item.text.uppercase() else item.text,
            color = text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = look.rowFont,
                fontWeight = look.rowWeight,
                fontSize = look.rowSize,
                letterSpacing = if (look.rowCaps) 1.sp else 0.sp,
            ),
            modifier = if (bubble) Modifier.weight(1f, fill = false) else Modifier.weight(1f),
        )
        // as in the old list: the arrow takes the line into the field to go on typing
        IconSpot(
            R.drawable.ic_arrow_up_left,
            stringResource(R.string.yt_r12_search_fill),
            secondary,
            Modifier,
        ) { actions.fill(item) }
    }
}

/** Kiesel's pebbles, Pop's blobs: a few outlines that take turns. */
private val pebbles: List<Shape> = listOf(
    BlobShape(0.5f, 0.42f, 0.55f, 0.47f),
    BlobShape(0.38f, 0.55f, 0.44f, 0.58f),
    BlobShape(0.56f, 0.46f, 0.4f, 0.52f),
)

@Composable
private fun Chip(kind: YtSearchKind, look: SearchLook, inks: YtSearchInks, item: YtSearchItem, index: Int, progress: () -> Float, actions: SearchActions) {
    val shape: Shape = when (kind) {
        YtSearchKind.KIESEL, YtSearchKind.POP -> pebbles[index % pebbles.size]
        else -> RoundedCornerShape(50)
    }
    val fillInt = when (kind) {
        YtSearchKind.KIESEL, YtSearchKind.POP, YtSearchKind.SHAPES, YtSearchKind.FREQUENCY -> YtSearchPalette.tile(kind, index, ytNight)
        else -> inks.chip
    }
    val textColor = Color(if (fillInt == inks.chip) inks.chipText else YtSearchPalette.onFill(fillInt))
    val surface: Modifier = when (kind) {
        YtSearchKind.GLASS -> Modifier
            .background(Color(fillInt), shape)
            .border(1.dp, Color.White.copy(alpha = if (ytNight) 0.3f else 0.9f), shape)
        YtSearchKind.CLAY -> Modifier
            .shadow(4.dp, shape, clip = false, ambientColor = Color(0xFF9D6A6C), spotColor = Color(0xFF9D6A6C))
            .background(Brush.verticalGradient(listOf(ClayDoughTop, Color(fillInt), ClayDoughFoot)), shape)
        YtSearchKind.POP -> Modifier.shadow(3.dp, shape, clip = false).background(Color(fillInt), shape)
        // Synthwave: night chips, each with a neon rim of its own (magenta, cyan, orange, violet)
        YtSearchKind.SYNTHWAVE -> Modifier
            .background(Color(fillInt), shape)
            .border(1.dp, Color(YtSearchPalette.tile(kind, index)), shape)
        else -> Modifier.background(Color(fillInt), shape)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .entryMotion(kind, progress, index)
            .then(surface)
            .entryClicks(item, actions)
            .testTag("yt_search_item")
            .heightIn(min = 44.dp)
            .widthIn(max = 280.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (kind == YtSearchKind.SHAPES || kind == YtSearchKind.FREQUENCY) {
            Box(Modifier.padding(end = 8.dp).size(12.dp).background(textColor, CookieShape(6, 0.12f)))
        }
        Text(
            item.text,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = look.rowFont, fontWeight = look.rowWeight, fontSize = look.rowSize),
        )
    }
}

/** Movo: a coloured tile per line, two to a row. */
@Composable
private fun Tile(kind: YtSearchKind, look: SearchLook, inks: YtSearchInks, item: YtSearchItem, index: Int, progress: () -> Float, actions: SearchActions, modifier: Modifier) {
    val fillInt = YtSearchPalette.tile(kind, index, ytNight)
    val textColor = Color(YtSearchPalette.onFill(fillInt))
    val shape = RoundedCornerShape(16.dp)
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .entryMotion(kind, progress, index)
            .background(Color(fillInt), shape)
            .entryClicks(item, actions)
            .testTag("yt_search_item")
            .heightIn(min = 76.dp)
            .padding(12.dp),
    ) {
        Icon(
            painterResource(if (item.history) R.drawable.ic_history else R.drawable.ic_yt_search),
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            item.text,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = look.rowFont, fontWeight = look.rowWeight, fontSize = look.rowSize),
        )
    }
}
