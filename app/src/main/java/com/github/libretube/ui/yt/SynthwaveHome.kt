package com.github.libretube.ui.yt

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.formatShort

/*
 * YT: the home of the design "Synthwave": the scene (SynthwaveScene: striped sun, wireframe
 * ridges, palms, stars, the neon floor driving towards you) is the full background of the screen,
 * also behind the header. The list starts right under the header, as on the other homes: the
 * headline on a plate of night glass, the topic filters (they stay at the top), then cards of
 * dark glass with neon edges: continue watching, the videos, "Gerade live", Shorts; the scene
 * shows around and faintly through them. Every word stands on glass, never on the scene itself,
 * so it reads 4.5:1 even over the sun (YtSynthwavePaletteTest). The scene moves in the draw phase only; the list never recomposes
 * for it. It lifts a little as the list scrolls (parallax).
 */

/** The cards: tight retro corners. */
private val CardShape: Shape = RoundedCornerShape(16.dp)
private val ThumbShape: Shape = RoundedCornerShape(11.dp)

/** How tall a list item roughly is, for the parallax (it only needs to be about right). */
private val ParallaxItem = 280.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SynthwaveHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    data: YtHomeData,
    categories: List<TrendingCategory>,
    category: String,
    onSelectCategory: (String) -> Unit,
    categoryVideos: List<StreamItem>?,
    progress: (StreamItem) -> Float,
    actions: YtHomeActions,
) {
    SynthwaveSceneHost()
    val all = category == YT_FILTER_ALL
    val source = if (all) data.forYou else categoryVideos
    val videos = ytPlayable(source).orEmpty()
    val live = if (all) data.live.orEmpty().filter { it.isLive && it.url != null }.distinctBy { it.url }.take(6) else emptyList()
    val regular = videos.filter { !it.isLive || !all }
    val hero = regular.firstOrNull()
    val rest = regular.drop(1)
    val shorts = if (all) data.shorts.orEmpty().filter { it.url != null }.take(8) else emptyList()
    val continueItem = if (all) data.continueWatching?.firstOrNull { it.url != null && !it.isShort } else null
    val loading = if (all) data.isLoading else categoryVideos == null

    val enter = rememberYtEnterState()
    val list = rememberLazyListState()
    val pinned by remember { derivedStateOf { list.firstVisibleItemIndex >= 1 } }
    val density = LocalDensity.current
    val itemPx = with(density) { ParallaxItem.toPx() }
    // the header lies over the scene (YtHeader.overlayHome): the list starts below it
    val headerOverlap = with(density) { YtChrome.headerOverlapPx.toDp() }
    // a list brought back scrolled down (back from another tab) shows its cards at once: they
    // entered when they were first seen, and fading them in again would leave only the scene
    // and the pinned filters on screen for a moment
    val restored = remember { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 }
    Box(Modifier.fillMaxSize()) {
        // the scene in its own layer, read while drawing: a frame redraws it, not the list
        // read while drawing: scrolling redraws the scene's layer, it never recomposes
        val scrolled = remember(list, itemPx) { { SynthwaveMath.scrolled(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, itemPx) } }
        SynthwaveBackdrop(Modifier.matchParentSize(), horizon = HORIZON, scroll = scrolled)
        YtRefreshBox(
            palette = palette,
            isRefreshing = loading && source != null,
            onRefresh = actions.onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = headerOverlap),
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp + ytNavPadding()),
            ) {
                item(key = "headline") { Headline(palette) }
                stickyHeader(key = "filters") {
                    Filters(palette, categories, category, onSelectCategory, pinned)
                }
                if (source == null || videos.isEmpty()) {
                    if (loading) {
                        items(2) { Skeleton(palette) }
                    } else {
                        item {
                            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.yt_home_nothing),
                                    color = palette.textSecondary,
                                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp),
                                    modifier = Modifier
                                        .padding(horizontal = 24.dp)
                                        .synthwaveGlass(RoundedCornerShape(12.dp))
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
                continueItem?.let { video ->
                    item(key = "continue-" + video.url) {
                        Box(Modifier.synthwaveEnter(restored, enter, "continue" + video.url, 0)) {
                            Continue(palette, imageLoader, video, progress(video), actions)
                        }
                    }
                }
                hero?.let { video ->
                    item(key = "hero-" + video.url) {
                        VideoCard(palette, imageLoader, video, 0, actions, ytItemMotion().synthwaveEnter(restored, enter, "hero" + video.url, 1))
                    }
                }
                if (live.isNotEmpty()) {
                    item { SectionTitle(palette, stringResource(R.string.yt_home_live_now)) { onSelectCategory(TrendingCategory.LIVE.name) } }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(live, key = { it.url!! }) { video ->
                                LiveCard(palette, imageLoader, video, actions, Modifier.synthwaveEnter(restored, enter, "live" + video.url, 2 + live.indexOf(video)))
                            }
                        }
                    }
                }
                if (shorts.isNotEmpty()) {
                    item { SectionTitle(palette, stringResource(R.string.yt_synthwave_shorts), actions.onShowShorts) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(shorts, key = { _, v -> "s-" + v.url }) { index, video ->
                                ShortTile(palette, imageLoader, video, index, actions)
                            }
                        }
                    }
                }
                if (rest.isNotEmpty()) {
                    item { SectionTitle(palette, stringResource(R.string.yt_synthwave_more)) }
                    itemsIndexed(rest, key = { _, video -> "v-" + video.url }) { index, video ->
                        VideoCard(palette, imageLoader, video, index + 1, actions, ytItemMotion().synthwaveEnter(restored, enter, "rest" + video.url, 3 + index))
                    }
                }
            }
        }
    }
}

/** The design's entrance (ytEnter), skipped for a list brought back scrolled down. */
@Composable
private fun Modifier.synthwaveEnter(restored: Boolean, enter: YtEnterState, key: Any, index: Int): Modifier =
    if (restored) this else ytEnter(enter, key, index)

/** Where the home's horizon lies, as a fraction of the screen (the scene is behind the header too). */
private const val HORIZON = 0.5f

/** The breathing gap between the header and the headline. */
private val HEADLINE_GAP = 6.dp

/**
 * The headline right under the header (a small breathing gap above it), in the retro face on a
 * plate of night glass with a neon edge (the words never stand on the scene itself).
 */
@Composable
private fun Headline(palette: YtPalette) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier
                .padding(start = 14.dp, end = 14.dp, top = HEADLINE_GAP, bottom = 10.dp)
                .synthwaveGlow(shape, palette.accent, 6.dp, 0.8f)
                .synthwaveGlass(shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.yt_synthwave_headline),
                style = synthwaveDisplay(26.sp, palette.text, spacing = 1.2.sp),
            )
            Text(
                text = stringResource(R.string.yt_synthwave_subline),
                color = palette.textSecondary,
                style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The topic filters with the Feed-Regler button at the start (a cyan neon disc). Every chip is
 * night glass; the chosen one is lit magenta with dark ink and a soft glow. Pinned at the top,
 * a band of glass lies behind them, so the list runs under it readably.
 */
@Composable
private fun Filters(
    palette: YtPalette,
    categories: List<TrendingCategory>,
    selected: String,
    onSelect: (String) -> Unit,
    pinned: Boolean,
) {
    val filters = listOf(YT_FILTER_ALL to stringResource(R.string.all)) +
        categories.map { it.name to stringResource(it.titleRes) }
    val band by animateFloatAsState(if (pinned) 1f else 0f, ytStateSpec(), label = "synthwaveBand")
    Column(
        Modifier.graphicsLayer().background(SynthwaveGlass.top.copy(alpha = SynthwaveGlass.top.alpha * band)),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
        ) {
            val openSheet = rememberOpenFeedSheet()
            val turn by animateFloatAsState(if (YtFeedUi.sheet) 1f else 0f, ytSpec(spring(dampingRatio = 0.7f, stiffness = 300f)), label = "synthwaveTune")
            val tuneDescription = stringResource(R.string.yt_feed_open)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .testTag("yt_feed_button")
                    .size(36.dp)
                    .synthwaveGlow(CircleShape, palette.lime, 5.dp)
                    .clip(CircleShape)
                    .background(palette.lime)
                    .clickable(interactionSource = null, indication = null, onClick = openSheet)
                    .semantics {
                        contentDescription = tuneDescription
                        role = Role.Button
                    },
            ) {
                Icon(
                    painterResource(R.drawable.ic_yt_tune),
                    contentDescription = null,
                    tint = palette.ink,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = 90f * turn },
                )
            }
            filters.forEach { (key, label) ->
                val active = key == selected
                val fill by animateColorAsState(if (active) palette.accent else SynthwaveGlass.pill, ytStateSpec(), label = "synthwaveChipFill")
                val ink by animateColorAsState(if (active) palette.onAccent else palette.text, ytStateSpec(), label = "synthwaveChipInk")
                val shape = RoundedCornerShape(50)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(34.dp)
                        .then(if (active) Modifier.synthwaveGlow(shape, palette.accent, 5.dp) else Modifier)
                        .clip(shape)
                        .background(fill)
                        .then(if (active) Modifier else Modifier.border(1.dp, SynthwaveGlass.edge(0.5f), shape))
                        .clickable(interactionSource = null, indication = null) { onSelect(key) },
                ) {
                    Text(
                        text = label,
                        color = ink,
                        maxLines = 1,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        // the neon edge of the pinned band
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .graphicsLayer { alpha = band }
                .background(SynthwaveGlass.edge(0.7f)),
        )
    }
}

/** A section title on a small plate of night glass, with a neon tube before it. */
@Composable
private fun SectionTitle(palette: YtPalette, title: String, onShowAll: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f, fill = false)
                .synthwaveGlass(RoundedCornerShape(10.dp), edge = 0f)
                .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 16.dp)
                    .synthwaveGlow(RoundedCornerShape(2.dp), palette.accent, 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(listOf(palette.accent, palette.lime))),
            )
            Text(
                text = title.uppercase(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = synthwaveDisplay(15.sp, palette.text, spacing = 1.2.sp),
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (onShowAll != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .synthwaveGlass(RoundedCornerShape(50), edge = 0f)
                    .clickable(onClick = onShowAll)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.yt_home_show_all),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
                )
                Icon(
                    painterResource(R.drawable.ic_yt_chevron_right),
                    contentDescription = null,
                    tint = palette.lime,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(14.dp),
                )
            }
        }
    }
}

/**
 * Continue watching on night glass: the still, a neon progress line from magenta to cyan, the
 * title, and a cyan play disc with a glow.
 */
@Composable
private fun Continue(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, progress: Float, actions: YtHomeActions) {
    val neon = remember(palette) { Brush.horizontalGradient(listOf(palette.accent, palette.lime)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 14.dp)
            .fillMaxWidth()
            .ytPressable(CardShape) { actions.onOpenVideo(video) }
            .synthwaveGlass(CardShape)
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .width(118.dp)
                .aspectRatio(16f / 9f)
                .clip(ThumbShape)
                .ytSeenTop(video, palette, ThumbShape),
        ) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.raised)
                    .ytSeen(video, palette),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.yt_synthwave_continue).uppercase(),
                style = synthwaveDisplay(11.sp, palette.lime, glow = palette.lime, spacing = 1.sp),
            )
            Text(
                text = video.ytTitle,
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
                modifier = Modifier.padding(top = 3.dp),
            )
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x33FFFFFF))
                    .drawBehind { drawRect(neon, size = size.copy(width = size.width * progress.coerceIn(0f, 1f))) },
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(38.dp)
                .synthwaveGlow(CircleShape, palette.lime, 5.dp)
                .clip(CircleShape)
                .background(palette.lime),
        ) {
            Icon(painterResource(R.drawable.ic_yt_play_round), contentDescription = null, tint = palette.ink, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * A video on a card of night glass with a neon edge (the first one also glows): the still with
 * the duration on a neon tag (dark ink), then the avatar in a neon ring, title and meta. Swipe
 * left hides, right queues; held it lifts and drags onto the mini player.
 */
@Composable
private fun VideoCard(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    index: Int,
    actions: YtHomeActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tag = synthwaveTag(palette, index)
    val inset = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // left hides, right queues; the field behind leaves out the gap below the card
            .ytCardSwipe(palette, video, CardShape, inset = inset, testTag = "yt_card_$index")
            // held: lifts, and drags onto the mini player; let go unmoved: the quick sheet
            .ytHomeCard(palette, video, CardShape, inset = inset, onRelease = { actions.onQuick(video) })
            .ytPressable(CardShape) { actions.onOpenVideo(video) }
            .padding(inset)
            .then(if (index == 0) Modifier.synthwaveGlow(CardShape, palette.accent, 7.dp, 0.9f) else Modifier)
            .synthwaveGlass(CardShape)
            .padding(6.dp),
    ) {
        // the watched bar and, held, the time left draw above the duration tag
        Box(Modifier.ytSeenTop(video, palette, ThumbShape)) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(ThumbShape)
                    .background(palette.raised)
                    .ytSeen(video, palette),
            )
            (if (video.isLive) stringResource(R.string.yt_live_badge) else ytDuration(video))?.let {
                Text(
                    text = it,
                    color = if (video.isLive) Color.White else palette.ink,
                    style = TextStyle(fontFamily = YtFonts.geistMono, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .ytHeldFade()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (video.isLive) palette.live else tag)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = video.uploaderAvatar,
                contentDescription = video.uploaderName,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable { actions.onOpenChannel(video) }
                    .border(1.5.dp, tag, CircleShape)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(palette.surface),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.ytTitle,
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 19.sp),
                )
                Text(
                    text = ytViewsAndAge(context, video.views ?: -1L, video.uploaded, video.uploaderName),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.5.sp),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** A live stream on night glass: the still with a LIVE tag (white on the live red), title, viewers. */
@Composable
private fun LiveCard(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, actions: YtHomeActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(212.dp)
            .ytHeldMark()
            .ytPressable(CardShape) { actions.onOpenVideo(video) }
            .synthwaveGlass(CardShape)
            .padding(6.dp),
    ) {
        Box {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(ThumbShape)
                    .background(palette.raised),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(palette.live)
                    .padding(horizontal = 7.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.yt_live_badge),
                    color = Color.White,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 12.sp),
                )
            }
        }
        Text(
            text = video.ytTitle,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            modifier = Modifier.padding(start = 2.dp, top = 8.dp),
        )
        Text(
            text = listOfNotNull(
                video.uploaderName,
                video.views?.takeIf { it > 0 }?.let { stringResource(R.string.yt_home_viewers, it.formatShort()) },
            ).joinToString(" · "),
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.5.sp),
            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
        )
    }
}

/** A Short on night glass with a neon edge of its tag colour, its title on the glass below. */
@Composable
private fun ShortTile(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, index: Int, actions: YtHomeActions) {
    val shape = RoundedCornerShape(14.dp)
    val neon = synthwaveTag(palette, index)
    Column(
        Modifier
            .width(122.dp)
            .ytHeldMark()
            .ytPressable(shape) { actions.onOpenVideo(video) }
            .synthwaveGlass(shape, edge = 0f)
            .border(1.dp, neon.copy(alpha = 0.8f), shape)
            .padding(5.dp),
    ) {
        val thumb = RoundedCornerShape(10.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(thumb)
                .background(palette.raised)
                .ytSeenTop(video, palette, thumb),
        ) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .ytSeen(video, palette),
            )
        }
        Text(
            text = video.ytTitle,
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 16.sp),
            modifier = Modifier.padding(start = 2.dp, top = 6.dp, end = 2.dp, bottom = 2.dp),
        )
    }
}

/** A placeholder card while the feed loads: night glass with a light sweeping across. */
@Composable
private fun Skeleton(palette: YtPalette) {
    val bone = palette.raised
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 16.dp)
            .synthwaveGlass(CardShape)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(ThumbShape)
                .ytShimmer(bone),
        )
        Row(Modifier.padding(start = 6.dp, top = 12.dp, end = 8.dp, bottom = 6.dp)) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .ytShimmer(bone),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth(0.9f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .ytShimmer(bone),
                )
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(0.55f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .ytShimmer(bone),
                )
            }
        }
    }
}
