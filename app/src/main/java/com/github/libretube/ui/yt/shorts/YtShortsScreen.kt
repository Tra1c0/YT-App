package com.github.libretube.ui.yt.shorts

import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtEaseOut
import com.github.libretube.ui.yt.YtLargeThumbnail
import com.github.libretube.ui.yt.YtTestHooks
import com.github.libretube.ui.yt.rememberYtHaptics
import com.github.libretube.ui.yt.ytTestRoot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the screen asks of its host. */
class YtShortsActions(
    val onClose: () -> Unit,
    val onClosed: () -> Unit,
    val onChannel: (StreamItem) -> Unit,
    val onShare: (StreamItem) -> Unit,
    val onOpenInPlayer: (StreamItem) -> Unit,
)

/**
 * YT: the Shorts view on screen: the pager, the overlay of each short, the bar at the top
 * (close, autoscroll toggle), the countdown before an autoscroll and the hidden timeline.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun YtShortsViewScreen(session: YtShortsSession, style: YtShortsStyle, closing: Boolean, actions: YtShortsActions) {
    val motion = LocalYtMotion.current
    val haptics = rememberYtHaptics()
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = session.startPage) { session.items.size }
    val player = session.player
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var countdown by remember { mutableStateOf<Long?>(null) }

    // in and out: a short fade and a slight rise
    val shown = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(closing) {
        if (closing) {
            if (motion) shown.animateTo(0f, tween(160, easing = YtEaseOut))
            actions.onClosed()
        } else {
            shown.animateTo(1f, if (motion) tween(220, easing = YtEaseOut) else snap())
        }
    }

    // the short that has settled plays; its neighbours are fetched ahead
    LaunchedEffect(pager) {
        var first = true
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            val id = session.idOf(session.items.getOrNull(page)) ?: return@collect
            if (!first) haptics.play(style.look.page)
            first = false
            if (session.stayOn != id) session.stayOn = null
            player.play(id)
            for (near in listOf(page + 1, page - 1, page + 2)) {
                session.idOf(session.items.getOrNull(near))?.let(player::prefetch)
            }
            session.maybeLoadMore(page)
        }
    }
    // once the information is there, its related shorts can extend the list
    LaunchedEffect(player.media) { session.maybeLoadMore(pager.settledPage) }

    // autoscroll (8.2): after the set number of plays, with a countdown; never while a finger
    // is down, the timeline is open, the user paused or chose to stay
    LaunchedEffect(session) {
        while (true) {
            delay(100)
            val page = pager.settledPage
            val id = session.idOf(session.items.getOrNull(page))
            if (id == null || id != player.currentId || pager.isScrollInProgress) {
                countdown = null
                continue
            }
            val blocked = session.fingerDown || session.timelineOpen || player.userPaused ||
                session.stayOn == id || player.media == null || player.failed
            val decision = YtShortsAutoscroll.decide(
                plays = session.autoscroll,
                completed = player.completed,
                positionMs = player.player.currentPosition,
                durationMs = player.player.duration,
                ended = player.ended,
                blocked = blocked,
                hasNext = page < session.items.size - 1,
            )
            player.setLoop(decision.loop)
            countdown = decision.countdownMs
            when {
                decision.scroll -> {
                    YtTestHooks.log(YtShortsPlayer.TAG) { "autoscroll from=$id plays=${player.completed + 1}" }
                    countdown = null
                    if (motion) pager.animateScrollToPage(page + 1) else pager.scrollToPage(page + 1)
                }
                // the end came while autoscroll had to wait: the short simply starts again
                player.ended -> player.replay()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .ytTestRoot()
            .testTag("yt_shorts_view")
            .graphicsLayer {
                alpha = shown.value
                translationY = (1f - shown.value) * 24.dp.toPx()
            }
            .background(Color.Black)
            // every finger on the screen holds autoscroll back; nothing is taken from the pager
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        session.fingerDown = event.changes.any { it.pressed }
                    }
                }
            },
    ) {
        VerticalPager(
            state = pager,
            beyondViewportPageCount = 1,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pager,
                snapAnimationSpec = if (motion) pageSpring(style) else snap(),
            ),
            key = { index -> session.items.getOrNull(index)?.url ?: index },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = session.items.getOrNull(page) ?: return@VerticalPager
            ShortPage(
                session = session,
                style = style,
                pager = pager,
                page = page,
                item = item,
                bottomSpace = YtShortsScrub.ZONE_DP.dp + navInset,
                actions = actions,
            )
        }

        TopBar(session, style, actions, onToggle = {
            val on = session.toggleAutoscroll() > 0
            haptics.threshold(on)
        })

        countdown?.let { left ->
            Countdown(
                style = style,
                leftMs = left,
                // under the autoscroll toggle, clear of the title and the buttons
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(end = 12.dp, top = 68.dp),
                onStay = {
                    session.stayOn = player.currentId
                    haptics.reject()
                },
            )
        }

        val media = player.media
        // the stills for the timeline; a download's are decoded off the main thread
        val frame: (suspend (Long) -> ImageBitmap?)? = remember(media) {
            val receiver = media?.let { YtShortsSource.frames(context, it) } ?: return@remember null
            val read: suspend (Long) -> ImageBitmap? = { ms ->
                withContext(Dispatchers.IO) { receiver.getFrameAtTime(ms) }?.asImageBitmap()
            }
            read
        }
        YtShortsTimeline(
            style = style,
            position = { player.player.currentPosition },
            duration = { player.player.duration.coerceAtLeast(0L) },
            playing = player.isPlaying,
            showLine = YtShortsPrefs.line,
            bottomInset = navInset,
            frame = frame,
            events = YtShortsTimelineEvents(
                onOpen = { open ->
                    session.timelineOpen = open
                    YtTestHooks.log(YtShortsPlayer.TAG) { "timeline open=$open" }
                },
                onScrubStart = {
                    runCatching { player.player.setScrubbingModeEnabled(true) }
                },
                onScrub = { ms -> player.player.seekTo(ms) },
                onSeek = { ms ->
                    player.player.seekTo(ms)
                    runCatching { player.player.setScrubbingModeEnabled(false) }
                    YtTestHooks.log(YtShortsPlayer.TAG) { "seek to=$ms" }
                },
                onGrab = { haptics.grab() },
                onSecond = { haptics.play(style.look.second) },
                onRelease = { haptics.release() },
            ),
            description = stringResource(R.string.yt_r12_shorts_timeline),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// the snap of a page after a swipe, in the design's motion
private fun pageSpring(style: YtShortsStyle) = when (style.look.motion) {
    YtShortsMotion.CRISP -> tween<Float>(180, easing = LinearEasing)
    YtShortsMotion.CINEMATIC -> tween(420, easing = YtEaseOut)
    YtShortsMotion.SPRINGY -> spring(dampingRatio = 0.72f, stiffness = 380f)
    YtShortsMotion.FLUID -> spring(dampingRatio = 0.85f, stiffness = 260f)
    YtShortsMotion.CALM -> spring(dampingRatio = 1f, stiffness = 420f)
}

@Composable
private fun ShortPage(
    session: YtShortsSession,
    style: YtShortsStyle,
    pager: PagerState,
    page: Int,
    item: StreamItem,
    bottomSpace: Dp,
    actions: YtShortsActions,
) {
    val motion = LocalYtMotion.current
    val haptics = rememberYtHaptics()
    val player = session.player
    val id = session.idOf(item).orEmpty()
    val active = player.currentId == id && pager.settledPage == page
    val stillAlpha by animateFloatAsState(
        if (active && player.firstFrame) 0f else 1f,
        if (motion) tween(180) else snap(),
        label = "shortsStill",
    )
    // a double tap pops a heart where the finger was
    var heartAt by remember { mutableStateOf<Offset?>(null) }
    var hearts by remember { mutableIntStateOf(0) }
    var flash by remember { mutableIntStateOf(0) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (!motion) return@graphicsLayer
                // the page's way in and out, in the design's material
                val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                val d = offset.absoluteValue
                when (style.look.motion) {
                    YtShortsMotion.CINEMATIC -> {
                        // the picture stays behind at half speed and darkens, as a camera move
                        translationY = size.height * offset * 0.5f
                        alpha = 1f - 0.5f * d
                    }
                    YtShortsMotion.SPRINGY -> {
                        val s = 1f - 0.06f * d
                        scaleX = s
                        scaleY = s
                    }
                    YtShortsMotion.FLUID -> {
                        val s = 1f - 0.03f * d
                        scaleX = s
                        scaleY = s
                        alpha = 1f - 0.25f * d
                    }
                    else -> Unit
                }
            }
            .clipToBounds()
            .background(Color.Black)
            .pointerInput(id) {
                detectTapGestures(
                    onTap = {
                        if (player.currentId == id) {
                            player.toggle()
                            flash++
                            haptics.tick()
                        }
                    },
                    onDoubleTap = { at ->
                        session.heart(id)
                        heartAt = at
                        hearts++
                        haptics.confirm()
                    },
                )
            },
    ) {
        if (active) ShortVideo(session)
        YtLargeThumbnail(
            item,
            ImageHelper.loader(),
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = stillAlpha },
        )

        // keeps title and buttons readable over a bright picture
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.42f)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x66000000), 1f to Color(0xC7000000))),
        )

        if (active) PageState(session, style, flash)

        heartAt?.let { at -> HeartPop(style, at, hearts) }

        Overlay(
            session = session,
            style = style,
            item = item,
            id = id,
            bottomSpace = bottomSpace,
            hidden = session.timelineOpen,
            actions = actions,
        )
    }
}

/** The picture: a TextureView, so it moves, fades and scales with the page. */
@Composable
private fun ShortVideo(session: YtShortsSession) {
    val player = session.player
    Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val aspect = player.aspect
        AndroidView(
            factory = { context -> TextureView(context).also { player.player.setVideoTextureView(it) } },
            onRelease = { player.player.clearVideoTextureView(it) },
            modifier = Modifier.layout { measurable, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                // tall videos fill the screen (cropped at the sides), wide ones fit
                val a = if (aspect > 0f) aspect else w.toFloat() / h.coerceAtLeast(1)
                val cover = a <= 1.05f
                val scale = if (cover) max(w / a / h, 1f) else min(w / a / h, 1f)
                val height = (h * scale).roundToInt().coerceAtLeast(1)
                val width = (height * a).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(w, h) {
                    placeable.place((w - width) / 2, (h - height) / 2)
                }
            },
        )
    }
}

/** Loading, pause and error on the playing short. */
@Composable
private fun PageState(session: YtShortsSession, style: YtShortsStyle, flash: Int) {
    val player = session.player
    val motion = LocalYtMotion.current
    // the play mark after a tap: only for taps from now on, and it survives the spinner
    val fade = remember { Animatable(0f) }
    val seen = remember { intArrayOf(flash) }
    LaunchedEffect(flash) {
        if (flash == seen[0] || !motion) return@LaunchedEffect
        seen[0] = flash
        fade.snapTo(1f)
        fade.animateTo(0f, tween(500, delayMillis = 150))
    }
    // a spinner only if loading takes a moment
    var waited by remember(player.currentId) { mutableStateOf(false) }
    LaunchedEffect(player.currentId) {
        delay(600)
        waited = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            player.failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(32.dp)
                    .clip(style.chipShape)
                    .background(style.chip)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.yt_r12_shorts_failed),
                    color = style.chipInk,
                    style = TextStyle(fontFamily = style.font, fontSize = 15.sp, lineHeight = 20.sp),
                )
                Text(
                    text = style.label(stringResource(R.string.yt_r12_shorts_retry)),
                    color = style.chipInk,
                    style = TextStyle(fontFamily = style.font, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clip(style.chipShape)
                        .clickable { player.retry() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            player.userPaused -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(style.knobShape)
                    .background(style.chip),
            ) {
                Icon(
                    painterResource(R.drawable.ic_play_filled),
                    contentDescription = stringResource(R.string.yt_r12_shorts_play),
                    tint = style.chipInk,
                    modifier = Modifier.size(36.dp),
                )
            }
            // not while scrubbing: the player holds playback then
            !player.isPlaying && waited && !player.ended && !session.timelineOpen ->
                CircularProgressIndicator(color = style.accentOn(Color.Black))
            else -> PlayFlash(style) { fade.value }
        }
    }
}

// the play mark that shows briefly after a tap resumes playback
@Composable
private fun PlayFlash(style: YtShortsStyle, fade: () -> Float) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .graphicsLayer {
                val f = fade()
                alpha = f
                scaleX = 0.9f + 0.1f * f
                scaleY = 0.9f + 0.1f * f
            }
            .clip(style.knobShape)
            .background(style.chip),
    ) {
        Icon(painterResource(R.drawable.ic_play_filled), contentDescription = null, tint = style.chipInk, modifier = Modifier.size(36.dp))
    }
}

/** The heart of a double tap: it pops up at the finger, rises and fades. */
@Composable
private fun HeartPop(style: YtShortsStyle, at: Offset, count: Int) {
    val pop = remember { Animatable(0f) }
    val motion = LocalYtMotion.current
    val density = LocalDensity.current
    LaunchedEffect(count) {
        pop.snapTo(0f)
        pop.animateTo(1f, if (motion) tween(700, easing = LinearEasing) else snap())
    }
    val size = 96.dp
    Icon(
        painterResource(R.drawable.ic_yt_heart_fill),
        contentDescription = null,
        tint = style.heart,
        modifier = Modifier
            .graphicsLayer {
                val p = pop.value
                val half = with(density) { size.toPx() / 2f }
                translationX = at.x - half
                translationY = at.y - half - 60.dp.toPx() * p
                // springs in past full size, then settles and fades
                val grow = if (p < 0.25f) p / 0.25f * 1.15f else 1.15f - 0.15f * ((p - 0.25f) / 0.75f)
                scaleX = grow
                scaleY = grow
                alpha = if (p < 0.6f) 1f else 1f - (p - 0.6f) / 0.4f
            }
            .size(size),
    )
}

/** Channel and title at the bottom, the buttons on the right. */
@Composable
private fun Overlay(
    session: YtShortsSession,
    style: YtShortsStyle,
    item: StreamItem,
    id: String,
    bottomSpace: Dp,
    hidden: Boolean,
    actions: YtShortsActions,
) {
    val motion = LocalYtMotion.current
    val haptics = rememberYtHaptics()
    val fade by animateFloatAsState(if (hidden) 0.2f else 1f, if (motion) tween(160) else snap(), label = "shortsOverlay")
    val hearted = id in session.hearts
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade }
            .padding(bottom = bottomSpace + 12.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, end = 86.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(style.chipShape)
                    .background(style.chip)
                    .clickable(enabled = item.uploaderUrl != null) { actions.onChannel(item) }
                    .semantics { contentDescription = item.uploaderName.orEmpty() }
                    .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                AsyncImage(
                    model = item.uploaderAvatar,
                    contentDescription = null,
                    imageLoader = ImageHelper.loader(),
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(style.accent),
                )
                Text(
                    text = style.label(item.uploaderName.orEmpty()),
                    color = style.chipInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = style.font, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .widthIn(max = 220.dp),
                )
            }
            Text(
                text = item.title.orEmpty(),
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = style.font,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    shadow = Shadow(Color(0x99000000), Offset(0f, 1f), 6f),
                ),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 44.dp),
        ) {
            RoundButton(
                style = style,
                icon = if (hearted) R.drawable.ic_yt_heart_fill else R.drawable.ic_yt_heart,
                tint = if (hearted) style.heart else style.chipInk,
                label = stringResource(R.string.yt_r12_shorts_heart),
            ) {
                session.toggleHeart(id)
                if (id in session.hearts) haptics.confirm() else haptics.tick()
            }
            RoundButton(style, R.drawable.ic_share, style.chipInk, stringResource(R.string.share)) { actions.onShare(item) }
            RoundButton(style, R.drawable.ic_fullscreen, style.chipInk, stringResource(R.string.yt_r12_shorts_open_in_player)) {
                actions.onOpenInPlayer(item)
            }
        }
    }
}

@Composable
private fun RoundButton(
    style: YtShortsStyle,
    icon: Int,
    tint: Color,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(if (style.look.radiusDp == 0) style.chipShape else CircleShape)
            .background(style.chip)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Close at the left, the autoscroll toggle at the right. */
@Composable
private fun TopBar(session: YtShortsSession, style: YtShortsStyle, actions: YtShortsActions, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        RoundButton(style, R.drawable.ic_close, style.chipInk, stringResource(R.string.yt_r12_shorts_close), actions.onClose)
        Box(Modifier.weight(1f))
        val plays = session.autoscroll
        val text = if (plays > 0) stringResource(R.string.yt_r12_shorts_auto_on, plays) else stringResource(R.string.yt_r12_shorts_auto_off)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(48.dp)
                .clip(style.chipShape)
                .background(style.chip)
                .clickable(onClick = onToggle)
                .testTag("yt_shorts_autoscroll")
                .semantics { contentDescription = text }
                .padding(horizontal = 14.dp),
        ) {
            Icon(
                painterResource(if (plays > 0) R.drawable.ic_next else R.drawable.ic_repeat_one),
                contentDescription = null,
                tint = if (plays > 0) style.accentOn(style.chip) else style.chipInk,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = style.label(text),
                color = style.chipInk,
                style = TextStyle(fontFamily = style.font, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** The countdown before an autoscroll: a ring that runs out; a tap stays on this short. */
@Composable
private fun Countdown(style: YtShortsStyle, leftMs: Long, modifier: Modifier, onStay: () -> Unit) {
    val window = YtShortsAutoscroll.COUNTDOWN_MS.toFloat()
    val target = (leftMs / window).coerceIn(0f, 1f)
    val motion = LocalYtMotion.current
    val fraction by animateFloatAsState(target, if (motion) tween(100, easing = LinearEasing) else snap(), label = "shortsCountdown")
    val ring = style.accentOn(style.chip)
    val text = stringResource(R.string.yt_r12_shorts_next_in, YtShortsAutoscroll.seconds(leftMs))
    val stay = stringResource(R.string.yt_r12_shorts_stay)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .testTag("yt_shorts_countdown")
            .clip(style.chipShape)
            .background(style.chip)
            .clickable(onClick = onStay)
            .semantics { contentDescription = "$text. $stay" }
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .drawBehind {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2f
                    drawArc(
                        color = style.chipInk.copy(alpha = 0.25f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke),
                    )
                    drawArc(
                        color = ring,
                        startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke),
                    )
                },
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                text = style.label(text),
                color = style.chipInk,
                style = TextStyle(fontFamily = style.font, fontWeight = FontWeight.Bold, fontSize = 14.sp),
            )
            Text(
                text = stay,
                color = style.chipInk,
                style = TextStyle(fontFamily = style.font, fontSize = 12.sp),
            )
        }
    }
}
