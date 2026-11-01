package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
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
import com.github.libretube.util.TextUtils

/** Key of the "Alle" filter; the other filters are trending categories. */
const val YT_FILTER_ALL = "ALL"

/** Key of the Feed-Regler's glyph at the start of the filter row (the pill glides onto it). */
private const val YT_FILTER_TUNE = "TUNE"

/**
 * YT design "YouTube, neu gedacht", view A (Startseite): headline, topic filters, one dominant
 * recommendation, "Gerade live" and further videos. Spacing follows the brief: 20dp gutters,
 * 20dp video corners, 16dp compact surfaces.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YouTubeHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    feed: List<StreamItem>?,
    trending: List<StreamItem>?,
    categories: List<TrendingCategory>,
    selectedFilter: String,
    isLoading: Boolean,
    onSelectFilter: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenVideo: (StreamItem) -> Unit,
    onOpenChannel: (StreamItem) -> Unit,
    onShowAllLive: () -> Unit = {},
    onSave: (StreamItem) -> Unit = {},
    onShare: (StreamItem) -> Unit = {},
) {
    // subscriptions first; without any (or with Shorts only) the charts
    val source = if (selectedFilter == YT_FILTER_ALL) feed?.takeIf { videos -> videos.any { !it.isShort } } ?: trending else trending
    val videos = source.orEmpty()
        .filter { !it.isShort && !it.isUpcoming && it.url != null }
        .distinctBy { it.url }
    val live = if (selectedFilter == YT_FILTER_ALL) videos.filter { it.isLive }.take(6) else emptyList()
    val regular = videos.filter { !it.isLive || selectedFilter != YT_FILTER_ALL }
    val hero = regular.firstOrNull()
    val rest = regular.drop(1)

    val enter = rememberYtEnterState()
    val list = rememberLazyListState()
    // a long press opens quick actions for a video
    var quick by remember { mutableStateOf<StreamItem?>(null) }
    // once the headline has scrolled away the filters pin at the top and the list runs under them
    val pinned by remember { derivedStateOf { list.firstVisibleItemIndex >= 1 } }
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
    YtRefreshBox(
        palette = palette,
        isRefreshing = isLoading && source != null,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp + ytNavPadding()),
        ) {
            item {
                Text(
                    text = stringResource(R.string.yt_home_headline),
                    color = palette.text,
                    style = TextStyle(
                        fontFamily = YtFonts.geist,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 40.sp,
                        lineHeight = 38.sp,
                        letterSpacing = (-0.8).sp,
                    ),
                    modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 4.dp, bottom = 9.dp),
                )
            }
            stickyHeader {
                FilterRow(palette, categories, selectedFilter, onSelectFilter, pinned)
            }
            if (source == null) {
                if (isLoading) {
                    // the shape of what is coming, shimmering as on YouTube, instead of a spinner
                    items(2) { YouTubeSkeleton(palette) }
                } else {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.yt_home_empty), color = palette.textSecondary)
                        }
                    }
                }
            }
            hero?.let { video ->
                item(key = "hero-" + video.url) {
                    LargeVideo(palette, imageLoader, video, 0, onOpenVideo, onOpenChannel, { quick = it }, ytItemMotion().ytEnter(enter, "hero" + video.url, 0))
                }
            }
            if (live.isNotEmpty()) {
                item { SectionTitle(palette, stringResource(R.string.yt_home_live_now), onShowAllLive) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        items(live, key = { it.url!! }) { video ->
                            LiveCard(palette, imageLoader, video, onOpenVideo, Modifier.ytEnter(enter, "live" + video.url, 1 + live.indexOf(video)))
                        }
                    }
                }
            }
            if (rest.isNotEmpty()) {
                item { SectionTitle(palette, stringResource(R.string.yt_home_more)) }
                itemsIndexed(rest, key = { _, video -> video.url!! }) { index, video ->
                    LargeVideo(palette, imageLoader, video, index + 1, onOpenVideo, onOpenChannel, { quick = it }, ytItemMotion().ytEnter(enter, "rest" + video.url, 2 + index))
                }
            }
        }
    }
    YtQuickSheet(
        palette = palette,
        imageLoader = imageLoader,
        video = quick,
        onDismiss = { quick = null },
        onSave = onSave,
        onShare = onShare,
        onChannel = onOpenChannel,
    )
    }
}

/**
 * The topic filters: one pill that glides from word to word (the words themselves only change
 * color), in a row that scrolls to keep the chosen filter in view. While pinned at the top, a
 * soft edge below it shows the list running underneath.
 */
@Composable
private fun FilterRow(
    palette: YtPalette,
    categories: List<TrendingCategory>,
    selected: String,
    onSelect: (String) -> Unit,
    pinned: Boolean,
) {
    val filters = listOf(YT_FILTER_ALL to stringResource(R.string.all)) +
        categories.map { it.name to stringResource(it.titleRes) }
    val scroll = rememberScrollState()
    val bounds = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    // while the Feed-Regler is open the pill rests on its glyph at the start of the row
    val shown = if (YtFeedUi.sheet) YT_FILTER_TUNE else selected
    val target = bounds[shown]
    val pillX by animateFloatAsState((target?.first ?: 0).toFloat(), ytStateSpec(), label = "filterX")
    val pillWidth by animateFloatAsState((target?.second ?: 0).toFloat(), ytStateSpec(), label = "filterWidth")
    val edge by animateFloatAsState(if (pinned) 1f else 0f, ytStateSpec(), label = "filterEdge")
    val scrollSpec = ytFinite(tween<Float>(260, easing = YtEaseOut))
    LaunchedEffect(shown, target) {
        val (x, w) = target ?: return@LaunchedEffect
        val margin = 48
        if (x - margin < scroll.value) {
            scroll.animateScrollTo((x - margin).coerceAtLeast(0), scrollSpec)
        } else if (x + w + margin > scroll.value + scroll.viewportSize) {
            scroll.animateScrollTo(x + w + margin - scroll.viewportSize, scrollSpec)
        }
    }
    Column(Modifier.background(palette.background)) {
        Box(
            Modifier
                .horizontalScroll(scroll)
                .padding(start = 15.dp, end = 15.dp, top = 9.dp, bottom = 15.dp),
        ) {
            Box(
                Modifier
                    .height(34.dp)
                    // placed and sized in layout, so the glide never recomposes the row
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(Constraints.fixed(pillWidth.roundToInt().coerceAtLeast(0), constraints.maxHeight))
                        layout(placeable.width, placeable.height) { placeable.place(pillX.roundToInt(), 0) }
                    }
                    .graphicsLayer { alpha = if (target == null) 0f else 1f }
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.text),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val tuneActive = shown == YT_FILTER_TUNE
                val tuneInk by animateColorAsState(if (tuneActive) palette.ink else palette.text, ytStateSpec(), label = "ytTuneInk")
                val tuneFill by animateColorAsState(if (tuneActive) Color.Transparent else palette.raised, ytStateSpec(), label = "ytTuneFill")
                val openSheet = rememberOpenFeedSheet()
                val tuneDescription = stringResource(R.string.yt_feed_open)
                Box(
                    modifier = Modifier
                        .testTag("yt_feed_button")
                        .onGloballyPositioned { bounds[YT_FILTER_TUNE] = it.positionInParent().x.roundToInt() to it.size.width }
                        .clip(RoundedCornerShape(12.dp))
                        .background(tuneFill)
                        .clickable(onClick = openSheet)
                        .semantics {
                            contentDescription = tuneDescription
                            role = Role.Button
                        }
                        .height(34.dp)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_yt_tune), contentDescription = null, tint = tuneInk, modifier = Modifier.size(18.dp))
                }
                filters.forEach { (key, label) ->
                    val active = key == shown
                    val color by animateColorAsState(if (active) palette.ink else palette.text, ytStateSpec(), label = "ytFilterText")
                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { bounds[key] = it.positionInParent().x.roundToInt() to it.size.width }
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(interactionSource = null, indication = null) { onSelect(key) }
                            .height(34.dp)
                            .padding(horizontal = 19.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = color,
                            style = TextStyle(
                                fontFamily = YtFonts.geist,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.5.sp,
                            ),
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .graphicsLayer { alpha = edge }
                .background(Brush.verticalGradient(listOf(palette.background, Color.Transparent))),
        )
    }
}

@Composable
private fun SectionTitle(palette: YtPalette, title: String, onShowAll: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 13.dp, end = 15.dp, top = 20.dp, bottom = 10.dp),
    ) {
        Text(
            text = title,
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 19.sp),
            modifier = Modifier.weight(1f),
        )
        if (onShowAll != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onShowAll),
            ) {
                Text(
                    text = stringResource(R.string.yt_home_show_all),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
                )
                Icon(
                    painterResource(R.drawable.ic_yt_chevron_right),
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(
    imageLoader: ImageLoader,
    url: String?,
    corner: Int,
    modifier: Modifier = Modifier,
    ratio: Float = 16f / 9f,
) {
    AsyncImage(
        model = url,
        contentDescription = null,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(corner.dp)),
    )
}

@Composable
private fun DurationChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = androidx.compose.ui.graphics.Color.White,
        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 12.5.sp),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(androidx.compose.ui.graphics.Color(0xCC000000))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatDuration(seconds: Long?): String? =
    seconds?.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it) }

@Composable
private fun LargeVideo(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    /** Its place in the list, for the emulator checks. */
    index: Int,
    onOpenVideo: (StreamItem) -> Unit,
    onOpenChannel: (StreamItem) -> Unit,
    onQuick: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            // left hides, right queues; the field behind leaves out the gap below the card
            .ytCardSwipe(
                palette, video, RoundedCornerShape(14.dp),
                inset = PaddingValues(start = 4.dp, end = 4.dp, bottom = 16.dp),
                testTag = "yt_card_$index",
            )
            // held: lifts, and drags onto the mini player; let go unmoved: the quick sheet
            .ytHomeCard(
                palette, video, RoundedCornerShape(14.dp),
                inset = PaddingValues(start = 4.dp, end = 4.dp, bottom = 16.dp),
                onRelease = { onQuick(video) },
            )
            .ytPressable(RoundedCornerShape(14.dp)) { onOpenVideo(video) }
            .padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
    ) {
        Box {
            Thumbnail(imageLoader, video.thumbnail, corner = 14, modifier = Modifier.fillMaxWidth(), ratio = 2f)
            formatDuration(video.duration)?.let {
                DurationChip(
                    it,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(start = 7.dp, end = 8.dp, top = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = video.uploaderAvatar,
                contentDescription = video.uploaderName,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(palette.surface)
                    .clickable { onOpenChannel(video) },
            )
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title.orEmpty(),
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = YtFonts.geist,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    ),
                )
                Text(
                    text = TextUtils.formatViewsString(
                        context,
                        video.views ?: -1L,
                        video.uploaded,
                        video.uploaderName,
                    ),
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

@Composable
private fun LiveCard(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    onOpenVideo: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(204.dp)
            .ytPressable(RoundedCornerShape(10.dp)) { onOpenVideo(video) },
    ) {
        Box {
            Thumbnail(imageLoader, video.thumbnail, corner = 10, modifier = Modifier.fillMaxWidth(), ratio = 204f / 121f)
            video.views?.takeIf { it > 0 }?.let {
                Text(
                    text = it.formatShort(),
                    color = androidx.compose.ui.graphics.Color.White,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(androidx.compose.ui.graphics.Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.live)
                    .padding(horizontal = 7.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.White),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.yt_live_badge),
                    color = androidx.compose.ui.graphics.Color.White,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                )
            }
        }
        Text(
            text = video.title.orEmpty(),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp),
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
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/** A placeholder video: picture, avatar and two lines, with a light sweeping across. */
@Composable
private fun YouTubeSkeleton(palette: YtPalette) {
    val bone = palette.surface
    Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .ytShimmer(bone),
        )
        Row(Modifier.padding(start = 8.dp, top = 12.dp, end = 8.dp)) {
            Box(
                Modifier
                    .size(36.dp)
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
