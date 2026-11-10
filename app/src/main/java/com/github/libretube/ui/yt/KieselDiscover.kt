package com.github.libretube.ui.yt

import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.models.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.ContentScale
import kotlin.math.hypot
import kotlin.random.Random

/*
 * YT: "Kiesel", the discover tab (in place of trends), after the inspiration image: on black, a
 * headline that types itself in outline and then fills, a peach diamond, and a pile of pebbles:
 * pills with the music, gaming, podcast and trailer charts and your most frequent channels,
 * among lavender and peach shapes. The pebbles are bodies: they fall into a pile, can be
 * grabbed, thrown and stacked, and follow the tilt of the phone. A tap on a chart opens its
 * videos in a sheet, a tap on a channel opens the channel.
 */

/*
 * At night (YtDesignDark) the black panels turn into a lighter warm surface and the cream
 * screen into dark warm grey: [KieselInk] is the type on the screen and on cards, [KieselPanel]
 * and [KieselPanelCard] fill the panels and the cards on them. Lavender and peach stay, with
 * black type on them; cream stays the type on the panels.
 */
internal val KieselInk: Color get() = ytDayNight(Color(0xFF0E0E0E), Color(0xFFE6E0D9))
internal val KieselCream = Color(0xFFEDE8E3)
internal val KieselLavender = Color(0xFFB4A7F5)
internal val KieselPeach = Color(0xFFF6C6A2)
internal val KieselPanel: Color get() = ytDayNight(Color(0xFF0E0E0E), Color(0xFF3A3632))
internal val KieselPanelCard: Color get() = ytDayNight(Color(0xFFEDE8E3), Color(0xFF2B2826))

/** Black in both modes: the discover screen stays on black, type and chips on the pastels. */
internal val KieselBlack = Color(0xFF0E0E0E)
private val KieselOval = Color(0xFFE0DBD5)

/** Type on [fill]: black on the light pastels, else the ink of the mode. */
internal fun kieselInkOn(fill: Color): Color = if (fill.luminance() > 0.3f) KieselBlack else KieselInk

/** A channel pebble. */
private class KieselChannel(val url: String, val name: String)

object YtKiesel {
    /** @return true if the Kiesel discover screen replaces the classic trends layout. */
    fun attachDiscover(fragment: Fragment, root: ViewGroup): Boolean {
        val design = YtDesign.current()
        if (design != YtDesign.KIESEL) return false
        val palette = design.palette ?: return false
        val viewModel = ViewModelProvider(fragment.requireActivity())[SubscriptionsViewModel::class.java]
        YtShorts.ensureFeed(fragment, viewModel)

        val sheet = mutableStateOf<TrendingCategory?>(null)
        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                sheet.value = null
            }
        }
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, back)

        root.children.forEach { it.isGone = true }
        val composeView = ComposeView(fragment.requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // hidden videos and paused channels stay off here too (rememberYtVisible)
                val feed = rememberYtVisible(viewModel.videoFeed.observeAsState().value)
                SideEffect { back.isEnabled = sheet.value != null }
                YtTheme(palette) {
                    KieselDiscoverScreen(
                        imageLoader = ImageHelper.loader(),
                        feed = feed,
                        sheet = sheet,
                        onOpenVideo = { video -> YtQueue.playKeepingQueue(context, video) },
                        onOpenChannel = { url -> NavigationHelper.navigateChannel(context, url) },
                    )
                }
            }
        }
        root.addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        return true
    }
}

@Composable
private fun KieselDiscoverScreen(
    imageLoader: ImageLoader,
    feed: List<StreamItem>?,
    sheet: MutableState<TrendingCategory?>,
    onOpenVideo: (StreamItem) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    val categories = remember { MediaServiceRepository.instance.getTrendingCategories().filter { it != TrendingCategory.LIVE } }
    val channels = remember(feed) {
        feed.orEmpty()
            .filter { it.uploaderUrl != null && !it.uploaderName.isNullOrBlank() }
            .groupBy { it.uploaderUrl!! }
            .entries
            .sortedByDescending { it.value.size }
            .take(4)
            .map { KieselChannel(it.key, it.value.first().uploaderName!!) }
    }
    val labels = categories.map { stringResource(it.titleRes) to (it as Any) } +
        channels.map { it.name to (it as Any) }
    // the last category stays while its sheet slides away (a plain holder, no state write here)
    val last = remember { arrayOfNulls<TrendingCategory>(1) }
    sheet.value?.let { last[0] = it }
    val shown = last[0]

    Box(
        Modifier
            .fillMaxSize()
            .background(KieselBlack),
    ) {
        Column(Modifier.fillMaxSize()) {
            KieselTypedHeadline(
                stringResource(R.string.yt_kiesel_headline),
                Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 26.dp, end = 24.dp, top = 18.dp),
            ) {
                KieselDiamond()
                Text(
                    text = stringResource(R.string.yt_kiesel_hint),
                    color = Color(0xFF8E8A86),
                    style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 12.5.sp, lineHeight = 17.sp),
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            KieselPebbleField(
                labels = labels,
                onTap = { tag ->
                    when (tag) {
                        is TrendingCategory -> sheet.value = tag
                        is KieselChannel -> onOpenChannel(tag.url)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = ytNavPadding()),
            )
        }

        AnimatedVisibility(
            visible = sheet.value != null,
            enter = ytEnterTransition(fadeIn(tween(200))),
            exit = ytExitTransition(fadeOut(tween(200))),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(interactionSource = null, indication = null) { sheet.value = null },
            )
        }
        AnimatedVisibility(
            visible = sheet.value != null,
            enter = ytEnterTransition(slideInVertically(tween(320, easing = YtEaseOut)) { it } + fadeIn()),
            exit = ytExitTransition(slideOutVertically(tween(240, easing = YtEaseOut)) { it } + fadeOut()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            shown?.let { category ->
                KieselCategorySheet(imageLoader, category, onClose = { sheet.value = null }, onOpenVideo = onOpenVideo)
            }
        }
    }
}

/** The headline types itself: each letter appears as an outline and fills a moment later. */
@Composable
internal fun KieselTypedHeadline(text: String, modifier: Modifier = Modifier, color: Color = KieselCream, size: Float = 40f) {
    val motion = LocalYtMotion.current
    val lag = 4f
    val progress = remember(text) { Animatable(if (motion) 0f else text.length + lag) }
    LaunchedEffect(text) {
        if (motion) progress.animateTo(text.length + lag, tween(text.length * 55 + 320, easing = LinearEasing))
    }
    val typed = progress.value.toInt()
    val filled = (progress.value - lag).toInt()
    val stroke = with(LocalDensity.current) { 1.5.dp.toPx() }
    Text(
        text = buildAnnotatedString {
            text.forEachIndexed { i, c ->
                val style = when {
                    i < filled -> SpanStyle(color = color)
                    i < typed -> SpanStyle(color = color, drawStyle = Stroke(width = stroke))
                    else -> SpanStyle(color = Color.Transparent)
                }
                withStyle(style) { append(c) }
            }
        },
        style = TextStyle(
            fontFamily = YtFonts.jakarta,
            fontWeight = FontWeight.ExtraBold,
            fontSize = size.sp,
            lineHeight = (size * 1.1f).sp,
            letterSpacing = (-size * 0.02f).sp,
        ),
        modifier = modifier,
    )
}

/** The peach diamond of the reference, bobbing gently to invite a throw. */
@Composable
private fun KieselDiamond() {
    val bob by rememberBreath(amount = 1f, durationMillis = 1400)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .graphicsLayer {
                translationY = (bob - 1f) * 5.dp.toPx()
                rotationZ = 45f
            }
            .clip(RoundedCornerShape(10.dp))
            .background(KieselPeach),
    ) {
        Icon(
            painterResource(R.drawable.ic_arrow_downward),
            contentDescription = null,
            tint = KieselBlack,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = -45f },
        )
    }
}

private enum class PebbleKind { PILL, OVAL, BALL, TRIANGLE, DROP, OCTAGON }

private class PebbleLook(val kind: PebbleKind, val color: Color, val label: TextLayoutResult?, val tag: Any?)

/** The field of pebbles: physics, drawing, grabbing and throwing, tilt. */
@Composable
private fun KieselPebbleField(labels: List<Pair<String, Any>>, onTap: (Any?) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val motion = LocalYtMotion.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontFamily = YtFonts.jakarta,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        letterSpacing = 0.6.sp,
        color = KieselBlack,
    )
    val world = remember { YtPebbleWorld() }
    var frame by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var awake by remember { mutableStateOf(true) }
    val gravity = rememberYtGravity(motion, fast = true)
    val haptics = rememberYtHaptics()

    LaunchedEffect(size, labels) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        world.pebbles.clear()
        world.width = size.width.toFloat()
        world.height = size.height.toFloat()
        world.gravity = 950f * density.density
        with(density) {
            val random = Random(7)
            fun spawn(pebble: YtPebble, index: Int) {
                // drop in from the upper part of the field, a little apart, slightly turned
                val x = pebble.length / 2 + pebble.radius + random.nextFloat() * (world.width - pebble.length - pebble.radius * 2)
                val y = pebble.radius + (index % 5) * world.height * 0.09f
                world.add(pebble, x, y, random.nextFloat() * 70f - 35f, velocityY = 200f * density.density)
            }
            var index = 0
            labels.forEachIndexed { i, (text, tag) ->
                val layout = measurer.measure(text.uppercase().take(18), labelStyle)
                val oval = i == 2
                val radius = if (oval) 34.dp.toPx() else 22.dp.toPx()
                val width = layout.size.width + if (oval) 48.dp.toPx() else 40.dp.toPx()
                val pebble = YtPebble(radius, (width - radius * 2).coerceAtLeast(0f), PebbleLook(if (oval) PebbleKind.OVAL else PebbleKind.PILL, if (oval) KieselOval else KieselCream, layout, tag))
                spawn(pebble, index++)
            }
            listOf(
                YtPebble(30.dp.toPx(), 0f, PebbleLook(PebbleKind.BALL, KieselLavender, null, null)),
                YtPebble(19.dp.toPx(), 0f, PebbleLook(PebbleKind.BALL, KieselLavender, null, null)),
                YtPebble(9.dp.toPx(), 24.dp.toPx(), PebbleLook(PebbleKind.PILL, KieselLavender, null, null)),
                YtPebble(24.dp.toPx(), 14.dp.toPx(), PebbleLook(PebbleKind.TRIANGLE, KieselLavender, null, null)),
                YtPebble(24.dp.toPx(), 18.dp.toPx(), PebbleLook(PebbleKind.DROP, KieselLavender, null, null)),
                YtPebble(18.dp.toPx(), 0f, PebbleLook(PebbleKind.BALL, KieselPeach, null, null)),
                YtPebble(15.dp.toPx(), 0f, PebbleLook(PebbleKind.BALL, KieselPeach, null, null)),
                YtPebble(20.dp.toPx(), 8.dp.toPx(), PebbleLook(PebbleKind.OCTAGON, KieselPeach, null, null)),
            ).forEach { spawn(it, index++) }
        }
        if (!motion) {
            // no motion: the pile is already lying there
            repeat(900) { world.step(1f / 60f) }
        }
        awake = true
        frame++
    }

    if (motion) {
        LaunchedEffect(Unit) {
            var last = 0L
            var lastBump = 0L
            var still = 0
            var lastGravity = gravity.value
            while (isActive) {
                val g = gravity.value
                if (!awake) {
                    // resting: look at the tilt now and then, wake up when it changes
                    if (hypot(g.x - lastGravity.x, g.y - lastGravity.y) > 0.06f) awake = true else {
                        delay(120)
                        last = 0L
                        continue
                    }
                }
                withFrameNanos { now ->
                    val dt = if (last == 0L) 1f / 60f else (now - last) / 1_000_000_000f
                    last = now
                    val length = hypot(g.x, g.y)
                    if (length < 0.3f) {
                        world.gravityX = 0f
                        world.gravityY = 1f
                    } else {
                        world.gravityX = g.x / length * length.coerceAtMost(1f)
                        world.gravityY = g.y / length * length.coerceAtMost(1f)
                    }
                    lastGravity = g
                    world.step(dt)
                    frame++
                    // a hard landing can be felt
                    if (world.impact > 1100f * density.density && now - lastBump > 90_000_000L) {
                        haptics.tick()
                        lastBump = now
                    }
                    if (world.motion < 0.04f * density.density) {
                        still++
                        if (still > 90) {
                            awake = false
                            still = 0
                        }
                    } else {
                        still = 0
                    }
                }
            }
        }
    }

    val slop = with(density) { 8.dp.toPx() }
    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(motion) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pebble = world.hit(down.position.x, down.position.y, slop) ?: return@awaitEachGesture
                    down.consume()
                    if (motion) {
                        world.grab(pebble, down.position.x, down.position.y)
                        awake = true
                        haptics.tick()
                    }
                    var moved = 0f
                    var up = down
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            up = change
                            break
                        }
                        moved += change.positionChange().getDistance()
                        if (motion) world.moveGrab(change.position.x, change.position.y)
                        change.consume()
                    }
                    world.release()
                    if (moved < viewConfiguration.touchSlop && up.uptimeMillis - down.uptimeMillis < 400) {
                        (pebble.tag as? PebbleLook)?.tag?.let(onTap)
                    }
                }
            },
    ) {
        // read, so the field is drawn again after every step
        frame
        for (pebble in world.pebbles) drawPebble(pebble)
    }
}

private fun DrawScope.drawPebble(pebble: YtPebble) {
    val look = pebble.tag as? PebbleLook ?: return
    val cx = pebble.centerX
    val cy = pebble.centerY
    val r = pebble.radius
    val half = pebble.length / 2f
    rotate(pebble.angle, pivot = Offset(cx, cy)) {
        when (look.kind) {
            PebbleKind.PILL -> drawRoundRect(
                look.color,
                topLeft = Offset(cx - half - r, cy - r),
                size = Size(pebble.length + r * 2, r * 2),
                cornerRadius = CornerRadius(r),
            )
            PebbleKind.OVAL -> drawOval(look.color, topLeft = Offset(cx - half - r, cy - r), size = Size(pebble.length + r * 2, r * 2))
            PebbleKind.BALL -> drawCircle(look.color, r, Offset(cx, cy))
            PebbleKind.TRIANGLE -> {
                val path = Path().apply {
                    moveTo(cx, cy - r * 1.1f)
                    lineTo(cx + r * 1.05f, cy + r * 0.75f)
                    lineTo(cx - r * 1.05f, cy + r * 0.75f)
                    close()
                }
                drawPath(path, look.color, style = androidx.compose.ui.graphics.drawscope.Fill)
                drawPath(path, look.color, style = Stroke(width = r * 0.35f, pathEffect = PathEffect.cornerPathEffect(r * 0.3f)))
            }
            PebbleKind.DROP -> {
                // a drop lying on its side: round at one end, pointed at the other
                val path = Path().apply {
                    moveTo(cx + half + r * 0.9f, cy)
                    cubicTo(cx + half * 0.2f, cy - r * 0.35f, cx - half, cy - r * 1.1f, cx - half - r * 0.2f, cy - r * 0.6f)
                    cubicTo(cx - half - r * 1.1f, cy - r * 0.2f, cx - half - r * 1.1f, cy + r * 0.2f, cx - half - r * 0.2f, cy + r * 0.6f)
                    cubicTo(cx - half, cy + r * 1.1f, cx + half * 0.2f, cy + r * 0.35f, cx + half + r * 0.9f, cy)
                    close()
                }
                drawPath(path, look.color)
            }
            PebbleKind.OCTAGON -> {
                val path = Path()
                for (i in 0..8) {
                    val a = Math.PI / 4 * i + Math.PI / 8
                    val x = cx + (r * 1.05f * kotlin.math.cos(a)).toFloat()
                    val y = cy + (r * 1.05f * kotlin.math.sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, look.color)
                drawPath(path, look.color, style = Stroke(width = r * 0.3f, pathEffect = PathEffect.cornerPathEffect(r * 0.4f)))
            }
        }
        look.label?.let { label ->
            drawText(label, topLeft = Offset(cx - label.size.width / 2f, cy - label.size.height / 2f))
        }
    }
}

/** The videos of a chart in a sheet that slides up over the pebbles. */
@Composable
private fun KieselCategorySheet(
    imageLoader: ImageLoader,
    category: TrendingCategory,
    onClose: () -> Unit,
    onOpenVideo: (StreamItem) -> Unit,
) {
    val context = LocalContext.current
    val loaded by produceState<List<StreamItem>?>(null, category) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaServiceRepository.instance.getTrending(PreferenceHelper.getTrendingRegion(context), category)
            }.getOrDefault(emptyList())
        }.filter { it.url != null && !it.isShort }
    }
    val videos = rememberYtVisible(loaded)
    val enter = rememberYtEnterState()
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.74f)
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(ytDayNight(Color(0xFFE9E5E0), Color(0xFF1F1D1B)))
            .clickable(interactionSource = null, indication = null) { },
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp)
                .size(40.dp, 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ytDayNight(Color(0xFFC9C4BE), Color(0xFF4A4541))),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 22.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        ) {
            Text(
                text = stringResource(category.titleRes),
                color = KieselInk,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp),
                modifier = Modifier.weight(1f),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(KieselPanel)
                    .clickable(onClick = onClose),
            ) {
                Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = stringResource(R.string.yt_kiesel_close), tint = KieselCream, modifier = Modifier.size(18.dp))
            }
        }
        val list = videos
        if (list == null) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = KieselInk)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp + ytNavPadding())) {
                itemsIndexed(list, key = { _, v -> v.url!! }) { index, video ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .ytEnter(enter, video.url!!, index)
                            .padding(vertical = 5.dp)
                            .fillMaxWidth()
                            // held: the watched bar grows and shows the time left (before the
                            // squish, so its slop is measured unscaled)
                            .ytHeldMark()
                            .ytPressable(RoundedCornerShape(18.dp), squish = true) { onOpenVideo(video) }
                            .background(ytDayNight(Color(0xFFF7F4F1), Color(0xFF2B2826)))
                            .padding(8.dp),
                    ) {
                        AsyncImage(
                            model = video.thumbnail,
                            contentDescription = null,
                            imageLoader = imageLoader,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(108.dp, 61.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(KieselLavender)
                                .ytSeen(video, YtDesign.KIESEL.palette ?: YtPalettes.Kiesel)
                                .ytSeenTop(video, YtDesign.KIESEL.palette ?: YtPalettes.Kiesel),
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                            Text(
                                text = video.ytTitle,
                                color = KieselInk,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, lineHeight = 17.sp),
                            )
                            Text(
                                text = listOfNotNull(video.uploaderName, ytViews(video)).joinToString(" · "),
                                color = ytDayNight(Color(0xFF7A7672), Color(0xFFABA59F)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 11.5.sp),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
