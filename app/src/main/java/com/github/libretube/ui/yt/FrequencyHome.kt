package com.github.libretube.ui.yt

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
 * YT: the home of the design "Frequency": the layout of YouTube neu (a big headline, the topic
 * filters that stay at the top, one large video, "Gerade live", more large videos) in the shapes
 * of Formen (cookie frames, widget corners, a continue-watching widget with the wavy progress)
 * over a deep night whose aura drifts with the sound. All the motion that follows the sound is
 * read while drawing (FrequencyPulse); the text never moves. In silence it is a calm, finished
 * page.
 */

/** Widget corners of Formen, smaller, for the large cards. */
private val CardShape: Shape = RoundedCornerShape(topStart = 22.dp, topEnd = 10.dp, bottomEnd = 22.dp, bottomStart = 22.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FrequencyHomeScreen(
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
    FrequencyPulseHost()
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
    Box(
        Modifier
            .fillMaxSize()
            .frequencyAura(palette),
    ) {
        YtRefreshBox(
            palette = palette,
            isRefreshing = loading && source != null,
            onRefresh = actions.onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp + ytNavPadding()),
            ) {
                item { Headline(palette) }
                stickyHeader {
                    Filters(palette, categories, category, onSelectCategory, pinned)
                }
                if (source == null || videos.isEmpty()) {
                    if (loading) {
                        items(2) { Skeleton(palette) }
                    } else {
                        item {
                            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.yt_home_nothing), color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp), modifier = Modifier.padding(horizontal = 24.dp))
                            }
                        }
                    }
                }
                continueItem?.let { video ->
                    item(key = "continue-" + video.url) {
                        Box(Modifier.ytEnter(enter, "continue" + video.url, 0)) {
                            Continue(palette, imageLoader, video, progress(video), actions)
                        }
                    }
                }
                hero?.let { video ->
                    item(key = "hero-" + video.url) {
                        LargeVideo(palette, imageLoader, video, 0, actions, ytItemMotion().ytEnter(enter, "hero" + video.url, 1))
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
                                LiveCard(palette, imageLoader, video, actions, Modifier.ytEnter(enter, "live" + video.url, 2 + live.indexOf(video)))
                            }
                        }
                    }
                }
                if (shorts.isNotEmpty()) {
                    item { SectionTitle(palette, stringResource(R.string.yt_frequency_shorts), actions.onShowShorts) }
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
                    item { SectionTitle(palette, stringResource(R.string.yt_frequency_more)) }
                    itemsIndexed(rest, key = { _, video -> "v-" + video.url }) { index, video ->
                        LargeVideo(palette, imageLoader, video, index + 1, actions, ytItemMotion().ytEnter(enter, "rest" + video.url, 3 + index))
                    }
                }
            }
        }
    }
}

/** The headline of YouTube neu with three of Formen's small shapes that breathe with the sound. */
@Composable
private fun Headline(palette: YtPalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.yt_frequency_headline),
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
            modifier = Modifier.weight(1f),
        )
        val shapes = remember {
            listOf<Shape>(CookieShape(8), CircleShape, RoundedCornerShape(topStartPercent = 50, topEndPercent = 20, bottomEndPercent = 50, bottomStartPercent = 20))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shapes.forEachIndexed { i, shape ->
                Box(
                    Modifier
                        .size(18.dp)
                        .frequencyBreath()
                        .frequencyTurn(if (i % 2 == 0) 1f else -1f)
                        .clip(shape)
                        .background(frequencyTag(palette, i)),
                )
            }
        }
    }
}

/**
 * YouTube neu's topic filters with the Feed-Regler glyph at the start (an aqua cookie, as on
 * Formen). The chosen filter sits on a coral pill whose fill (not its word) breathes with the
 * bass; pinned at the top, a soft edge shows the list running under it.
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
    val edge by animateFloatAsState(if (pinned) 1f else 0f, ytStateSpec(), label = "frequencyEdge")
    Column(Modifier.background(palette.background.copy(alpha = if (pinned) 0.96f else 0f))) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
        ) {
            val openSheet = rememberOpenFeedSheet()
            val turn by animateFloatAsState(if (YtFeedUi.sheet) 1f else 0f, ytSpec(spring(dampingRatio = 0.6f, stiffness = 300f)), label = "frequencyTune")
            val tuneDescription = stringResource(R.string.yt_feed_open)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .testTag("yt_feed_button")
                    .size(38.dp)
                    .clickable(interactionSource = null, indication = null, onClick = openSheet)
                    .semantics {
                        contentDescription = tuneDescription
                        role = Role.Button
                    },
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { rotationZ = 45f * turn }
                        .frequencyCookie(palette.lime, lobes = 8, rest = 0.08f),
                )
                Icon(painterResource(R.drawable.ic_yt_tune), contentDescription = null, tint = palette.ink, modifier = Modifier.size(18.dp))
            }
            filters.forEach { (key, label) ->
                val active = key == selected
                val fill by animateColorAsState(if (active) palette.accent else palette.raised, ytStateSpec(), label = "frequencyFilterFill")
                val ink by animateColorAsState(if (active) palette.onAccent else palette.text, ytStateSpec(), label = "frequencyFilterInk")
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(34.dp)
                        .clickable(interactionSource = null, indication = null) { onSelect(key) },
                ) {
                    // the fill breathes, the word stays where it is
                    Box(
                        Modifier
                            .matchParentSize()
                            .then(if (active) Modifier.frequencyBreath(0.05f) else Modifier)
                            .clip(RoundedCornerShape(50))
                            .background(fill),
                    )
                    Text(
                        text = label,
                        color = ink,
                        maxLines = 1,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                        modifier = Modifier.padding(horizontal = 17.dp),
                    )
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
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
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
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onShowAll)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
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

/**
 * Formen's continue-watching widget on Frequency's raised night: title, the wavy progress in
 * coral (its wave travels only while there is sound) and a cookie play button. Dragging along the
 * wave scrubs; letting go opens the video right there.
 */
@Composable
private fun Continue(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, progress: Float, actions: YtHomeActions) {
    var seek by remember { mutableStateOf<Float?>(null) }
    val haptics = rememberYtHaptics()
    val grabbed by animateFloatAsState(if (seek != null) 1f else 0f, ytSpec(spring(dampingRatio = 0.5f, stiffness = 420f)), label = "frequencyGrab")
    val shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 72.dp, bottomEnd = 72.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 16.dp)
            .fillMaxWidth()
            .ytPressable(shape) { actions.onOpenVideo(video) }
            .ytSurface(palette, shape)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.yt_frequency_continue),
                color = palette.accent,
                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 12.sp),
            )
            Text(
                text = video.ytTitle,
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp),
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = video.uploaderName.orEmpty(),
                color = palette.textSecondary,
                maxLines = 1,
                style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.sp),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(30.dp)
                    .pointerInput(video.url) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                ?: return@awaitEachGesture
                            haptics.tick()
                            seek = (start.position.x / size.width).coerceIn(0f, 1f)
                            try {
                                horizontalDrag(start.id) { change ->
                                    change.consume()
                                    seek = (change.position.x / size.width).coerceIn(0f, 1f)
                                }
                                val at = seek ?: return@awaitEachGesture
                                haptics.confirm()
                                val duration = video.duration ?: 0L
                                if (duration > 0) actions.onOpenVideoAt(video, (at * duration).toLong()) else actions.onOpenVideo(video)
                            } finally {
                                // a cancelled drag (the list took the touch) lets the wave go
                                seek = null
                            }
                        }
                    },
            ) {
                WavyProgress(
                    progress,
                    palette.accent,
                    Color(0x40FFFFFF),
                    Modifier.fillMaxWidth().height(18.dp),
                    // the wave travels with the music and stands in silence
                    phase = { FrequencyPulse.turn.floatValue / 90f },
                    seek = { seek },
                    grabbed = { grabbed },
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(80.dp),
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .frequencyCookie(palette.lavender, lobes = 9, rest = 0.07f),
            )
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    // the thin watched bar only: this card has its own wavy bar and no hold
                    .ytSeenTop(video, palette, CircleShape)
                    .ytSeen(video, palette),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xB30B0A18)),
            ) {
                Icon(painterResource(R.drawable.ic_yt_play_round), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * YouTube neu's large video in Formen's widget corners: the avatar sits in a cookie frame of the
 * card's shape colour that breathes with the bass; the duration on a pill of that colour.
 */
@Composable
private fun LargeVideo(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    index: Int,
    actions: YtHomeActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tag = frequencyTag(palette, index)
    val inset = PaddingValues(start = 10.dp, end = 10.dp, bottom = 18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // left hides, right queues; the field behind leaves out the gap below the card
            .ytCardSwipe(palette, video, CardShape, inset = inset, testTag = "yt_card_$index")
            // held: lifts, and drags onto the mini player; let go unmoved: the quick sheet
            .ytHomeCard(palette, video, CardShape, inset = inset, onRelease = { actions.onQuick(video) })
            .ytPressable(CardShape) { actions.onOpenVideo(video) }
            .padding(inset),
    ) {
        // the watched bar and, held, the time left draw above the duration pill
        Box(Modifier.ytSeenTop(video, palette, CardShape)) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(CardShape)
                    .background(palette.raised)
                    .ytSeen(video, palette),
            )
            (if (video.isLive) stringResource(R.string.yt_live_badge) else ytDuration(video))?.let {
                Text(
                    text = it,
                    color = palette.ink,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .ytHeldFade()
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(tag)
                        .padding(horizontal = 9.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .frequencyCookie(tag, lobes = if (index % 2 == 0) 9 else 7, rest = 0.07f)
                    .clip(CircleShape)
                    .clickable { actions.onOpenChannel(video) },
            ) {
                AsyncImage(
                    model = video.uploaderAvatar,
                    contentDescription = video.uploaderName,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(palette.surface),
                )
            }
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

/** YouTube neu's live card: picture, a LIVE badge with a dot, viewers; Formen's corners. */
@Composable
private fun LiveCard(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, actions: YtHomeActions, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 8.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    Column(
        modifier = modifier
            .width(204.dp)
            .ytHeldMark()
            .ytPressable(shape) { actions.onOpenVideo(video) },
    ) {
        Box {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(204f / 121f)
                    .clip(shape)
                    .background(palette.raised),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.live)
                    .padding(horizontal = 8.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .frequencyBreath(0.06f)
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
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/** A Short in one of Formen's shapes (alternating corners), its title under it. */
@Composable
private fun ShortTile(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, index: Int, actions: YtHomeActions) {
    val shape: Shape = if (index % 2 == 0) {
        RoundedCornerShape(topStart = 40.dp, topEnd = 14.dp, bottomEnd = 40.dp, bottomStart = 14.dp)
    } else {
        RoundedCornerShape(topStart = 14.dp, topEnd = 40.dp, bottomEnd = 14.dp, bottomStart = 40.dp)
    }
    Column(
        Modifier
            .width(118.dp)
            .ytHeldMark()
            .ytPressable(shape) { actions.onOpenVideo(video) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(shape)
                .background(frequencyTag(palette, index))
                .ytSeenTop(video, palette, shape),
        ) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(shape)
                    .ytSeen(video, palette),
            )
        }
        Text(
            text = video.ytTitle,
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 16.sp),
            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
        )
    }
}

/** A placeholder video while the feed loads: the light sweeps across, as on YouTube neu. */
@Composable
private fun Skeleton(palette: YtPalette) {
    val bone = palette.raised
    Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 18.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(CardShape)
                .ytShimmer(bone),
        )
        Row(Modifier.padding(start = 6.dp, top = 12.dp, end = 8.dp)) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CookieShape(9, 0.07f))
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
