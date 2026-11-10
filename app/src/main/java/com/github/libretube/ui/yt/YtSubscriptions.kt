package com.github.libretube.ui.yt

import android.content.Intent
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.libretube.ui.yt.feed.YtPauseRules
import com.github.libretube.ui.yt.feed.YtSubsOrder
import com.github.libretube.db.DatabaseHelper
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random
import com.github.libretube.helpers.YtChannelPause
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Subscription
import com.github.libretube.constants.IntentData
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.repo.FeedRefresh
import com.github.libretube.ui.dialogs.AddToPlaylistDialog
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

/*
 * YT: the subscriptions tab of the designs, in place of the classic feed: the headline of the
 * design, a strip of your channels, a filter (all, today, live) in the design's tabs, and the
 * feed as the cards of the design. Every card can be pushed to the right to save it (a bookmark
 * grows behind it), a long press opens the quick actions, and pull to refresh fetches the feed
 * again. The classic layout keeps its groups and sorting for the standard design.
 */
object YtSubscriptions {
    /** @return true if a design screen replaces the classic layout. */
    fun attach(fragment: Fragment, root: ViewGroup, viewModel: SubscriptionsViewModel): Boolean {
        val design = YtDesign.current()
        val palette = design.palette ?: return false
        val context = fragment.requireContext()
        if (viewModel.videoFeed.value == null) viewModel.fetchFeed(context, FeedRefresh.Automatically)
        if (viewModel.subscriptions.value == null) viewModel.fetchSubscriptions(context)
        val title = design.navigationLabel(R.id.subscriptionsFragment) ?: R.string.yt_nav_subscriptions

        root.children.forEach { it.isGone = true }
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // hidden videos and paused channels stay off the feed here too (rememberYtVisible)
                val loaded by viewModel.videoFeed.observeAsState()
                val feed = rememberYtVisible(loaded)
                val channels by viewModel.subscriptions.observeAsState()
                // the spinner follows the load itself: keyed on the list it kept spinning when a
                // pull brought the same videos (equal lists are no new state) or failed
                val loading by viewModel.feedLoading.observeAsState(false)
                var pulled by remember { mutableStateOf(false) }
                LaunchedEffect(loading) { if (!loading) pulled = false }
                // a new mix of "All" on every open of the tab and on every pull (YtSubsOrder)
                var seed by remember { mutableLongStateOf(Random.nextLong()) }
                YtTheme(palette) {
                    Box(Modifier.fillMaxSize().ytTestRoot()) {
                    YtSubscriptionsScreen(
                        palette = palette,
                        imageLoader = ImageHelper.loader(),
                        feed = feed,
                        channels = channels,
                        refreshing = pulled && loading,
                        seed = seed,
                        title = stringResource(title),
                        onRefresh = {
                            pulled = true
                            seed = Random.nextLong()
                            viewModel.fetchFeed(context, FeedRefresh.All)
                        },
                        // a tap keeps what the user queued (YtQueue)
                        onOpenVideo = { video -> YtQueue.playKeepingQueue(context, video) },
                        onOpenChannel = { url -> NavigationHelper.navigateChannel(context, url) },
                        onSave = { ytSaveVideo(fragment, it) },
                        onShare = { ytShareVideo(fragment, it) },
                    )
                    // the undo pill of a channel paused from its avatar or the quick sheet
                    YtHomePills(palette)
                    }
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

/** Opens the "add to playlist" dialog for [video]. */
internal fun ytSaveVideo(fragment: Fragment, video: StreamItem) {
    AddToPlaylistDialog().apply {
        arguments = bundleOf(IntentData.videoInfo to video)
    }.show(fragment.childFragmentManager, AddToPlaylistDialog::class.java.name)
}

/** Shares the link of [video]. */
internal fun ytShareVideo(fragment: Fragment, video: StreamItem) {
    val link = "https://youtu.be/" + (video.url ?: return).toID()
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, video.title)
        .putExtra(Intent.EXTRA_TEXT, link)
    fragment.startActivity(Intent.createChooser(intent, video.title))
}

private const val DAY = 24L * 60 * 60 * 1000

/** How far each video of [feed] was watched, by video id (live streams and shorts left out). */
private suspend fun ytSubsSeen(feed: List<StreamItem>): Map<String, YtSubsOrder.Seen> =
    feed.filter { it.url != null && !it.isShort && !it.isLive }.associate { video ->
        val id = video.url!!.toID()
        id to YtSubsOrder.seen(DatabaseHelper.getWatchPosition(id), video.duration)
    }

private val ClayShadow: Color get() = ytDayNight(Color(0x80B07A66), Color(0x80000000))
private val ClayInk: Color get() = ytDayNight(Color(0xFF7A5560), Color(0xFFE8C6C2))
private val ClayLabel: Color get() = ytDayNight(Color(0xFF8C6563), Color(0xFFC9AEA2))
private val ClayLight: Color get() = ytDayNight(Color(0xFFFFF3E9), Color(0xFF4A3830))
private val PopInk = Color(0xFF3B2054)
private val PopFrames = listOf(Color(0xFFFDE682), Color(0xFFF7B3CE), Color(0xFFDFD6F7), Color(0xFFBFE8D2))
private val ShapesTags = listOf(Color(0xFFF6D365), Color(0xFFBFE26B), Color(0xFFD9D4EE), Color(0xFFF28B82))
private val ShapesInk = Color(0xFF12122A)
private val KieselBeige: Color get() = ytDayNight(Color(0xFFE2DCD5), Color(0xFF4A4541))
private val KieselCard: Color get() = ytDayNight(Color(0xFFF7F4F1), Color(0xFF2B2826))
private val KieselGrey: Color get() = ytDayNight(Color(0xFF7A7672), Color(0xFFABA59F))

@Composable
private fun YtSubscriptionsScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    feed: List<StreamItem>?,
    channels: List<Subscription>?,
    refreshing: Boolean,
    seed: Long,
    title: String,
    onRefresh: () -> Unit,
    onOpenVideo: (StreamItem) -> Unit,
    onOpenChannel: (String) -> Unit,
    onSave: (StreamItem) -> Unit,
    onShare: (StreamItem) -> Unit,
) {
    var filter by remember { mutableIntStateOf(0) }
    val labels = listOf(stringResource(R.string.yt_subs_all), stringResource(R.string.yt_subs_today), stringResource(R.string.yt_tab_live))
    // how far each video was watched, read once per list (the order of "All" groups by it);
    // produceState keeps the previous map while a new list is read, so a refresh or a hidden
    // video does not reorder the screen in between
    val seen by produceState<Map<String, YtSubsOrder.Seen>?>(null, feed) {
        val list = feed ?: return@produceState
        value = withContext(Dispatchers.IO) { ytSubsSeen(list) }
    }
    val videos = remember(feed, filter, seen, seed) {
        val now = System.currentTimeMillis()
        val shown = feed.orEmpty().filter { it.url != null && !it.isShort }.filter {
            when (filter) {
                // live and upcoming streams would sit on top (their date is now or later); they
                // have the Live tab of the app and the Live filter here, so the others leave them out
                1 -> !it.isLive && !it.isUpcoming && now - it.uploaded < DAY
                2 -> it.isLive
                else -> !it.isLive && !it.isUpcoming
            }
        }
        if (filter != 0) return@remember shown.sortedByDescending { it.uploaded }
        // the first time, wait for the watch states (a few database reads) instead of showing
        // watched videos on top for a moment
        val states = seen ?: return@remember emptyList()
        YtSubsOrder.shuffle(
            shown, seed,
            id = { it.url?.toID() },
            seen = { states[it.url?.toID()] ?: YtSubsOrder.Seen.UNWATCHED },
        )
    }
    var quick by remember { mutableStateOf<StreamItem?>(null) }
    val enter = rememberYtEnterState()
    val count = channels?.size ?: 0

    YtScreenSurface(palette, Modifier.fillMaxSize()) {
        YtRefreshBox(
            palette = palette,
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp + ytNavPadding()),
            ) {
                item {
                    LibraryHeadline(palette, title, pluralStringResource(R.plurals.yt_channels_count, count, count))
                }
                item { ChannelStrip(palette, imageLoader, channels, onOpenChannel) }
                item {
                    WatchTabs(palette, labels, filter, { filter = it }, Modifier.padding(top = 14.dp, bottom = 6.dp), trailingAction = false)
                }
                when {
                    feed == null || (filter == 0 && seen == null) -> item {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = if (palette.light) palette.text else Color.White)
                        }
                    }
                    videos.isEmpty() -> item {
                        Text(
                            text = stringResource(R.string.yt_subs_empty),
                            color = palette.textSecondary,
                            style = TextStyle(fontFamily = palette.font, fontSize = 15.sp, lineHeight = 21.sp),
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 24.dp),
                        )
                    }
                    else -> itemsIndexed(videos, key = { _, v -> v.url!! }) { index, video ->
                        SubsCard(
                            palette, imageLoader, video, index,
                            onOpen = onOpenVideo,
                            onQuick = { quick = it },
                            onSave = onSave,
                            modifier = Modifier.ytEnter(enter, video.url!!, index),
                        )
                    }
                }
            }
        }
        YtQuickSheet(
            palette, imageLoader, quick,
            onDismiss = { quick = null },
            onSave = onSave,
            onShare = onShare,
            onChannel = { it.uploaderUrl?.let(onOpenChannel) },
        )
    }
}

/** Your channels as a strip of avatars in the shape of the design. */
@Composable
private fun ChannelStrip(palette: YtPalette, imageLoader: ImageLoader, channels: List<Subscription>?, onOpenChannel: (String) -> Unit) {
    if (channels.isNullOrEmpty()) return
    val enter = rememberYtEnterState()
    val shape: Shape = when (palette.signature) {
        YtSignature.POP -> BlobShape(0.6f, 0.5f, 0.56f, 0.64f)
        YtSignature.SHAPES -> CookieShape(8, 0.06f)
        YtSignature.FREQUENCY -> CookieShape(9, 0.07f)
        YtSignature.EDITORIAL -> RectangleShape
        YtSignature.KIESEL, YtSignature.CLAY -> RoundedCornerShape(18.dp)
        YtSignature.AFTER -> RoundedCornerShape(4.dp)
        else -> CircleShape
    }
    val ink = when (palette.signature) {
        YtSignature.SHAPES, YtSignature.FROST -> Color.White
        YtSignature.KIESEL -> KieselInk
        else -> palette.text
    }
    // paused channels stay in the strip, faded, with the days left; a long press pauses or resumes
    val paused by YtChannelPause.paused.collectAsState()
    val now = remember(paused) { System.currentTimeMillis() }
    val pauseLabel = stringResource(R.string.yt_home_pause_week)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        itemsIndexed(channels, key = { _, c -> c.url }) { i, channel ->
            val ring = when (palette.signature) {
                YtSignature.POP -> PopFrames[i % PopFrames.size]
                YtSignature.SHAPES -> ShapesTags[i % ShapesTags.size]
                YtSignature.FREQUENCY -> frequencyTag(palette, i)
                YtSignature.KIESEL -> if (i % 2 == 0) KieselLavender else KieselPeach
                YtSignature.CLAY -> ClayLight
                YtSignature.EDITORIAL -> palette.text
                YtSignature.FROST -> Color(0x73FFFFFF)
                YtSignature.GLASS -> ytDayNight(Color.White, Color(0xFF6FB5AA))
                YtSignature.AFTER -> palette.accent
                else -> palette.raised
            }
            val pause = YtPauseRules.find(paused, YtChannelPause.channelId(channel.url), channel.name, now)
            val days = pause?.let { YtPauseRules.daysLeft(it.untilMillis, now) } ?: 0
            val endLabel = stringResource(R.string.yt_home_pause_end, days)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(66.dp)
                    .ytEnter(enter, channel.url, i)
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(if (pause == null) pauseLabel else endLabel) {
                                ytTogglePause(channel.url, channel.name, channel.avatar)
                                true
                            },
                        )
                    }
                    .ytPressable(
                        RoundedCornerShape(14.dp),
                        squish = palette.signature == YtSignature.CLAY || palette.signature == YtSignature.KIESEL,
                        onLongClick = { ytTogglePause(channel.url, channel.name, channel.avatar) },
                    ) { onOpenChannel(channel.url) }
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        // Frequency: the cookie frames grow with the beat and bounce one after another
                        .then(if (palette.isFrequency) Modifier.frequencyBreath(0.1f, index = i) else Modifier)
                        .then(if (palette.signature == YtSignature.CLAY) Modifier.shadow(6.dp, shape, ambientColor = ClayShadow, spotColor = ClayShadow) else Modifier)
                        .clip(shape)
                        .background(ring)
                        .padding(if (palette.signature == YtSignature.EDITORIAL) 1.5.dp else 3.dp)
                        .clip(shape),
                ) {
                    AsyncImage(
                        model = channel.avatar,
                        contentDescription = if (pause == null) channel.name
                        else pluralStringResource(R.plurals.yt_home_pause_days_spoken, days, channel.name, days),
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        colorFilter = when {
                            palette.signature == YtSignature.EDITORIAL -> ytGrayscale
                            pause != null -> ytSaturation(0.2f)
                            else -> null
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (pause != null) 0.7f else 1f },
                    )
                }
                Text(
                    text = if (pause == null) channel.name else stringResource(R.string.yt_home_pause_days_left, days),
                    color = if (pause == null) ink else ink.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 10.5.sp),
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

/**
 * A feed card that can be pushed to the right to save it: it follows the finger, a bookmark
 * grows behind it, a tick at the threshold, letting go there saves.
 */
@Composable
private fun SubsCard(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    index: Int,
    onOpen: (StreamItem) -> Unit,
    onQuick: (StreamItem) -> Unit,
    onSave: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYtHaptics()
    val swipe = rememberYtSwipe()
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val field = when (palette.signature) {
        YtSignature.POP -> PopFrames[0]
        YtSignature.SHAPES -> palette.lime
        YtSignature.KIESEL -> KieselLavender
        YtSignature.CLAY -> ClayLight
        YtSignature.EDITORIAL -> palette.text
        YtSignature.FROST, YtSignature.GLASS -> ytDayNight(Color(0x80FFFFFF), Color(0x33A8E0D6))
        else -> palette.lime
    }
    val mark = when (palette.signature) {
        YtSignature.EDITORIAL -> palette.background
        YtSignature.FROST, YtSignature.GLASS -> palette.text
        // at night the clay and pebble fields are dark: the mark takes the light type
        else -> if (ytNight && field.luminance() < 0.3f) palette.text else Color(0xFF111114)
    }
    Box(modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .matchParentSize()
                .clip(ytCardShape(palette, 16))
                .background(field),
        ) {
            Icon(
                painterResource(R.drawable.ic_yt_nav_bookmark_fill),
                contentDescription = null,
                tint = mark,
                modifier = Modifier
                    .padding(start = 26.dp)
                    .size(26.dp)
                    .graphicsLayer {
                        val p = (swipe.offset.value / threshold).coerceIn(0f, 1.2f)
                        scaleX = 0.6f + 0.5f * p
                        scaleY = 0.6f + 0.5f * p
                        alpha = p.coerceAtMost(1f)
                    },
            )
        }
        Box(
            Modifier
                // held (the long press opens the quick sheet): the watched bar grows and shows
                // the time left; it only watches the finger, outside the swipe and press scale
                .ytHeldMark()
                .offset { IntOffset(swipe.offset.value.roundToInt(), 0) }
                .ytSwipe(swipe, threshold, direction = 1, haptics = haptics) { onSave(video) },
        ) {
            SubsCardFace(palette, imageLoader, video, index, onOpen = { onOpen(video) }, onQuick = { onQuick(video) })
        }
    }
}

@Composable
private fun Meta(video: StreamItem, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        text = listOf(video.uploaderName.orEmpty(), ytMeta(video, " · ")).filter { it.isNotBlank() }.joinToString(" · "),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = style,
        modifier = modifier,
    )
}

@Composable
private fun DurationBadge(video: StreamItem, fill: Color, ink: Color, font: androidx.compose.ui.text.font.FontFamily, modifier: Modifier) {
    val text = if (video.isLive) stringResource(R.string.yt_live_badge) else ytDuration(video) ?: return
    Text(
        text = text,
        color = if (video.isLive) Color.White else ink,
        style = TextStyle(fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 11.sp),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (video.isLive) Color(0xFFE53935) else fill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The face of a feed card in the design. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubsCardFace(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    index: Int,
    onOpen: () -> Unit,
    onQuick: () -> Unit,
) {
    val title = video.ytTitle
    when (palette.signature) {
        YtSignature.MOVO -> {
            // a tile of the mosaic: every third one in lime, all tilt towards the finger
            val lime = index % 3 == 2
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .ytTilt(4f)
                    .ytPressable(RoundedCornerShape(18.dp), onLongClick = onQuick, onClick = onOpen)
                    .background(if (lime) palette.lime else palette.surface)
                    .padding(8.dp),
            ) {
                Box(
                    Modifier
                        .width(132.dp)
                        .aspectRatio(16f / 10f)
                        .clip(RoundedCornerShape(12.dp))
                        .ytSeenTop(video, palette),
                ) {
                    YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().ytSeen(video, palette))
                    DurationBadge(video, Color(0xCC111114), Color.White, palette.font, Modifier.align(Alignment.BottomEnd).ytHeldFade().padding(6.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                    Text(title, color = if (lime) palette.ink else palette.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Black, fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = (-0.3).sp))
                    Meta(video, if (lime) palette.ink.copy(alpha = 0.7f) else palette.textSecondary, TextStyle(fontFamily = palette.font, fontSize = 12.sp), Modifier.padding(top = 4.dp))
                }
            }
        }
        YtSignature.AFTER -> {
            // a row of the programme: a letterboxed still, the title in Anton
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .ytPressable(RoundedCornerShape(4.dp), onLongClick = onQuick, onClick = onOpen)
                    .background(palette.surface)
                    .padding(8.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(124.dp)
                        .height(70.dp)
                        .background(Color.Black),
                ) {
                    YtLargeThumbnail(video, imageLoader, Modifier.fillMaxWidth().height(56.dp).ytSeen(video, palette))
                    DurationBadge(video, Color(0xCC000000), Color.White, YtFonts.robotoCondensed, Modifier.align(Alignment.BottomEnd).ytHeldFade().padding(4.dp))
                    // the watched bar over the still's bounds, above the badge
                    Spacer(Modifier.fillMaxWidth().height(56.dp).ytSeenTop(video, palette))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                    Text(title.uppercase(), color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.anton, fontSize = 16.sp, lineHeight = 18.sp, letterSpacing = 0.5.sp))
                    Meta(video, palette.textSecondary, TextStyle(fontFamily = YtFonts.robotoCondensed, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 1.sp), Modifier.padding(top = 5.dp))
                }
            }
        }
        YtSignature.SIDE -> {
            // a post: who, then what, then the picture
            Column(
                Modifier
                    .fillMaxWidth()
                    .ytPressable(RoundedCornerShape(18.dp), onLongClick = onQuick, onClick = onOpen)
                    .background(palette.surface)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = video.uploaderAvatar, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp).clip(CircleShape).background(palette.raised))
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(video.uploaderName.orEmpty(), color = palette.text, maxLines = 1, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 13.5.sp))
                        Text(ytMeta(video, " · "), color = palette.textSecondary, maxLines = 1, style = TextStyle(fontFamily = palette.font, fontSize = 11.5.sp))
                    }
                }
                Text(title, color = palette.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = palette.font, fontSize = 15.sp, lineHeight = 20.sp), modifier = Modifier.padding(top = 10.dp))
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(14.dp))
                        .ytSeenTop(video, palette),
                ) {
                    YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().ytSeen(video, palette))
                    DurationBadge(video, Color(0xCC111114), Color.White, palette.font, Modifier.align(Alignment.BottomEnd).ytHeldFade().padding(8.dp))
                }
            }
        }
        YtSignature.CLAY -> {
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .ytPressable(shape, squish = true, onLongClick = onQuick, onClick = onOpen)
                    .shadow(8.dp, shape, ambientColor = ClayShadow, spotColor = ClayShadow)
                    .background(Brush.verticalGradient(listOf(ClayLight, palette.surface)))
                    .border(1.dp, ytDayNight(Color(0x99FFFFFF), Color(0x26FFF0E4)), shape)
                    .padding(8.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(18.dp))
                        .ytSeenTop(video, palette),
                ) {
                    YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().ytSeen(video, palette))
                    DurationBadge(video, ClayLight, ClayInk, palette.font, Modifier.align(Alignment.BottomEnd).ytHeldFade().padding(10.dp))
                }
                Text(title, color = ClayInk, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 19.sp), modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp))
                Meta(video, ClayLabel, TextStyle(fontFamily = palette.font, fontSize = 12.sp), Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp))
            }
        }
        YtSignature.EDITORIAL -> {
            // a numbered row of the paper; the still is black and white and takes color under the finger
            val press = remember { MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            val color by animateFloatAsState(if (pressed) 1f else 0f, ytSpec(tween(260, easing = YtEaseOut)), label = "editorialColor")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(palette.background)
                    .combinedClickable(interactionSource = press, indication = null, onLongClick = onQuick, onClick = onOpen),
            ) {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                    Text(
                        "%02d".format(index + 1),
                        color = palette.text,
                        style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.sp),
                        modifier = Modifier.width(30.dp).padding(top = 2.dp),
                    )
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(title, color = palette.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp))
                        Meta(video, palette.textSecondary, TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 9.5.sp, letterSpacing = 1.sp), Modifier.padding(top = 5.dp))
                    }
                    AsyncImage(
                        model = video.thumbnail,
                        contentDescription = null,
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        colorFilter = if (color == 0f) ytGrayscale else ytSaturation(color),
                        modifier = Modifier
                            .width(104.dp)
                            .height(58.dp)
                            .ytSeen(video, palette)
                            .ytSeenTop(video, palette),
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.text.copy(alpha = 0.3f)),
                )
            }
        }
        YtSignature.GLASS -> {
            // the still with a caption of glass that bends it
            val still = rememberLayerBackdrop()
            val press = remember { MutableInteractionSource() }
            val swell by rememberSwell(press)
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .ytPressScale(press)
                    .shadow(6.dp, RoundedCornerShape(16.dp), clip = false, ambientColor = ytDayNight(Color(0x405C8C85), Color(0x66000000)), spotColor = ytDayNight(Color(0x405C8C85), Color(0x66000000)))
                    .clip(RoundedCornerShape(16.dp))
                    // above the glass caption
                    .ytSeenTop(video, palette)
                    .background(palette.lime)
                    .combinedClickable(interactionSource = press, indication = LocalIndication.current, onLongClick = onQuick, onClick = onOpen),
            ) {
                YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().layerBackdrop(still).ytSeen(video, palette))
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .fillMaxWidth()
                        .liquidGlass(still, RoundedCornerShape(12.dp), ytDayNight(Color(0x80FFFFFF), Color(0xE6142826)), null, blur = 8.dp, refraction = 8.dp, swell = { swell })
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(title, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 17.sp))
                    Meta(video, palette.textSecondary, TextStyle(fontFamily = YtFonts.googleSans, fontSize = 11.5.sp), Modifier.padding(top = 3.dp))
                }
            }
        }
        YtSignature.POP -> {
            // a colored frame a little askew; it straightens while pressed
            val press = remember { MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            val askew = if (index % 2 == 0) -1.5f else 1.5f
            val tilt by animateFloatAsState(if (pressed) 0f else askew, ytSpec(spring(dampingRatio = 0.5f, stiffness = 260f)), label = "popTilt")
            val shape = RoundedCornerShape(26.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { rotationZ = tilt }
                    .ytSquish(press)
                    .clip(shape)
                    .background(PopFrames[index % PopFrames.size])
                    .combinedClickable(interactionSource = press, indication = null, onLongClick = onQuick, onClick = onOpen)
                    .padding(7.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(20.dp))
                        .ytSeenTop(video, palette),
                ) {
                    YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().ytSeen(video, palette))
                    DurationBadge(video, PopFrames[0], PopInk, YtFonts.nunito, Modifier.align(Alignment.BottomEnd).ytHeldFade().padding(10.dp))
                }
                Text(title, color = PopInk, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, lineHeight = 19.sp), modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp))
                Meta(video, PopInk.copy(alpha = 0.7f), TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Bold, fontSize = 12.sp), Modifier.padding(start = 8.dp, top = 2.dp, bottom = 6.dp))
            }
        }
        YtSignature.SHAPES -> {
            val shape: Shape = if (index % 2 == 0) {
                RoundedCornerShape(topStart = 44.dp, topEnd = 16.dp, bottomEnd = 44.dp, bottomStart = 16.dp)
            } else {
                RoundedCornerShape(topStart = 16.dp, topEnd = 44.dp, bottomEnd = 16.dp, bottomStart = 44.dp)
            }
            val tag = ShapesTags[index % ShapesTags.size]
            Column(
                Modifier
                    .fillMaxWidth()
                    .ytPressable(shape, onLongClick = onQuick, onClick = onOpen)
                    .background(palette.surface)
                    .padding(6.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(shape)
                        // the 44 dp bottom-end corner of the even cards would cut the pill
                        .ytSeenTop(video, palette, pillSide = if (index % 2 == 0) YtPillSide.START else YtPillSide.END),
                ) {
                    YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().ytSeen(video, palette))
                    DurationBadge(video, tag, ShapesInk, YtFonts.googleSans, Modifier.align(if (index % 2 == 0) Alignment.BottomStart else Alignment.BottomEnd).ytHeldFade().padding(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 6.dp)) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CookieShape(6, 0.1f))
                            .background(tag),
                    )
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(title, color = palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.sp))
                        Meta(video, palette.ink.copy(alpha = 0.6f), TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.sp), Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        YtSignature.FROST -> {
            val backdrop = LocalScreenBackdrop.current
            val press = remember { MutableInteractionSource() }
            val thaw by rememberThaw(press)
            Column(
                Modifier
                    .fillMaxWidth()
                    .ytPressScale(press)
                    .frostGlass(backdrop, RoundedCornerShape(22.dp)) { thaw }
                    .combinedClickable(interactionSource = press, indication = LocalIndication.current, onLongClick = onQuick, onClick = onOpen)
                    .padding(8.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                        .ytSeenTop(video, palette),
                ) {
                    YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().ytSeen(video, palette))
                    DurationBadge(video, Color(0x59FFFFFF), Color.White, YtFonts.jakarta, Modifier.align(Alignment.BottomEnd).ytHeldFade().padding(10.dp))
                }
                Text(title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 19.sp), modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp))
                Meta(video, palette.textSecondary, TextStyle(fontFamily = YtFonts.jakarta, fontSize = 12.sp), Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp))
            }
        }
        YtSignature.KIESEL -> {
            val fresh = System.currentTimeMillis() - video.uploaded < DAY
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .ytPressable(RoundedCornerShape(18.dp), squish = true, onLongClick = onQuick, onClick = onOpen)
                    .background(KieselCard)
                    .padding(8.dp),
            ) {
                Box(
                    Modifier
                        .width(124.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .ytSeenTop(video, palette),
                ) {
                    YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().ytSeen(video, palette))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                    Text(title, color = KieselInk, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, lineHeight = 18.sp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        DurationBadge(video, KieselLavender, KieselBlack, YtFonts.jakarta, Modifier)
                        if (fresh) {
                            Box(
                                Modifier
                                    .padding(start = 6.dp)
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(KieselPeach),
                            )
                        }
                        Text(video.uploaderName.orEmpty(), color = KieselGrey, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 11.5.sp), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        YtSignature.FREQUENCY -> {
            // YouTube neu's card on a raised night in Formen's widget corners; the avatar in a
            // cookie frame of the card's shape colour that breathes with the bass
            val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 10.dp, bottomEnd = 22.dp, bottomStart = 22.dp)
            val tag = frequencyTag(palette, index)
            Column(
                Modifier
                    .fillMaxWidth()
                    .ytPressable(shape, onLongClick = onQuick, onClick = onOpen)
                    .background(palette.surface)
                    .padding(6.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(shape)
                        .ytSeenTop(video, palette, shape),
                ) {
                    YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().ytSeen(video, palette))
                    DurationBadge(video, tag, palette.ink, palette.font, Modifier.align(Alignment.BottomEnd).ytHeldFade().padding(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 6.dp, bottom = 4.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .frequencyCookie(tag, lobes = if (index % 2 == 0) 9 else 7, rest = 0.07f, index = index),
                    ) {
                        AsyncImage(model = video.uploaderAvatar, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop, modifier = Modifier.size(34.dp).clip(CircleShape).background(palette.raised))
                    }
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(title, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp))
                        Meta(video, palette.textSecondary, TextStyle(fontFamily = palette.font, fontSize = 12.5.sp), Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
        else -> {
            // YouTube neu: the still, then the channel's avatar with title and meta
            Column(
                Modifier
                    .fillMaxWidth()
                    .ytPressable(RoundedCornerShape(14.dp), onLongClick = onQuick, onClick = onOpen)
                    .background(palette.background),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(14.dp))
                        .ytSeenTop(video, palette),
                ) {
                    YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().ytSeen(video, palette))
                    DurationBadge(video, Color(0xCC111114), Color.White, palette.font, Modifier.align(Alignment.BottomEnd).ytHeldFade().padding(8.dp))
                }
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp)) {
                    AsyncImage(model = video.uploaderAvatar, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp).clip(CircleShape).background(palette.surface))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(title, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp))
                        Meta(video, palette.textSecondary, TextStyle(fontFamily = palette.font, fontSize = 12.5.sp), Modifier.padding(top = 3.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
