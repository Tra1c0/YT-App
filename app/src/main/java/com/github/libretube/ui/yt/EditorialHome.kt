package com.github.libretube.ui.yt

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem

/**
 * Design "Editorial", home: tabs Für dich / Abos / Live over a black rule with a blue bar,
 * topic filters, a numbered list with wide black-and-white images and a Shorts block.
 * Measured in the reference, see docs/DESIGN-MASSE.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorialHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    data: YtHomeData,
    tab: String,
    onSelectTab: (String) -> Unit,
    categories: List<TrendingCategory>,
    category: String,
    onSelectCategory: (String) -> Unit,
    categoryVideos: List<StreamItem>?,
    actions: YtHomeActions,
) {
    val source = if (tab == YT_TAB_FOR_YOU && category != YT_FILTER_ALL) categoryVideos else data.forTab(tab)
    val videos = (if (tab == YT_TAB_LIVE) source?.filter { it.url != null }?.distinctBy { it.url } else ytPlayable(source)).orEmpty()
    val shorts = data.shorts.orEmpty()
    val enter = rememberYtEnterState()
    // a horizontal swipe turns to the next or previous tab: the page follows the finger a
    // little, and the new page comes in from the side it was pulled from, crisp, no spring
    val tabs = listOf(YT_TAB_FOR_YOU, YT_TAB_SUBS, YT_TAB_LIVE)
    val motion = LocalYtMotion.current
    val haptics = rememberYtHaptics()
    val swipe = rememberYtSwipe()
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val turn = remember { Animatable(0f) }
    val turnSpec = ytFinite(tween<Float>(220, easing = YtEaseOut))
    val lastTab = remember { arrayOf(tab) }
    LaunchedEffect(tab) {
        if (tab != lastTab[0]) {
            val from = if (tabs.indexOf(tab) > tabs.indexOf(lastTab[0])) 1f else -1f
            lastTab[0] = tab
            if (motion) {
                turn.snapTo(from)
                turn.animateTo(0f, turnSpec)
            }
        }
    }

    YtRefreshBox(
        palette = palette,
        isRefreshing = data.isLoading && source != null,
        onRefresh = actions.onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset((swipe.offset.value * 0.3f).roundToInt(), 0) }
                .graphicsLayer {
                    translationX = turn.value * size.width * 0.3f
                    alpha = 1f - kotlin.math.abs(turn.value) * 0.85f
                },
            contentPadding = PaddingValues(bottom = 16.dp + ytNavPadding()),
        ) {
            // the entries swipe on their own now (hide, queue), so the page turns by the header
            item(key = "tabs") {
                Column(
                    Modifier.ytSwipe(swipe, threshold, direction = 0, haptics = haptics) { way ->
                        val i = tabs.indexOf(tab)
                        val next = (i - way).coerceIn(0, tabs.lastIndex)
                        if (next != i) onSelectTab(tabs[next])
                    },
                ) {
                    EditorialMainTabs(
                        palette = palette,
                        tabs = listOf(
                            YT_TAB_FOR_YOU to stringResource(R.string.yt_tab_for_you),
                            YT_TAB_SUBS to stringResource(R.string.yt_tab_subscriptions),
                            YT_TAB_LIVE to stringResource(R.string.yt_tab_live),
                        ),
                        selected = tab,
                        onSelect = onSelectTab,
                    )
                    Box(
                        Modifier
                            .padding(horizontal = 17.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(palette.text),
                    )
                }
            }
            if (tab == YT_TAB_FOR_YOU) {
                item {
                    EditorialTabs(
                        palette = palette,
                        tabs = listOf(YT_FILTER_ALL to stringResource(R.string.all)) +
                            categories.map { it.name to stringResource(it.titleRes) },
                        selected = category,
                        onSelect = onSelectCategory,
                        large = false,
                    )
                }
            }
            if (source == null || videos.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        if (source == null || data.isLoading) CircularProgressIndicator(color = palette.accent)
                        else Text(stringResource(R.string.yt_home_nothing), color = palette.textSecondary, modifier = Modifier.padding(32.dp))
                    }
                }
            }
            itemsIndexed(videos.take(3), key = { _, v -> "top-" + v.url }) { index, video ->
                EditorialItem(palette, imageLoader, index + 1, video, actions, ytItemMotion().ytEnter(enter, tab + video.url, index))
            }
            if (shorts.isNotEmpty() && tab != YT_TAB_LIVE) {
                item { EditorialShorts(palette, imageLoader, shorts, actions) }
            }
            itemsIndexed(videos.drop(3), key = { _, v -> "more-" + v.url }) { index, video ->
                EditorialItem(palette, imageLoader, index + 4, video, actions, ytItemMotion().ytEnter(enter, tab + video.url, index + 3))
            }
        }
    }
}

/** The main tabs: one blue bar that slides from tab to tab, sitting on the black rule. */
@Composable
private fun EditorialMainTabs(
    palette: YtPalette,
    tabs: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val bounds = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    val density = LocalDensity.current
    val target = bounds[selected]
    val barX by animateDpAsState(with(density) { (target?.first ?: 0).toDp() }, ytStateSpec(), label = "editorialBarX")
    val barWidth by animateDpAsState(with(density) { (target?.second ?: 0).toDp() }, ytStateSpec(), label = "editorialBarWidth")
    Box(Modifier.fillMaxWidth().padding(start = 17.dp, end = 16.dp, top = 6.dp)) {
        // the Feed-Regler in the paper's mono, at the end of the tabs, on their line; it takes
        // no height of its own, so the blue bar stays on the rule
        Box(Modifier.matchParentSize().padding(bottom = 9.dp), contentAlignment = Alignment.CenterEnd) {
            YtTuneWord(
                text = stringResource(R.string.yt_feed_open_editorial).uppercase(),
                style = TextStyle(fontFamily = YtFonts.plexMono, fontSize = 11.sp, letterSpacing = 1.sp, textDecoration = TextDecoration.Underline),
                color = palette.text,
                active = palette.accent,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
            tabs.forEach { (key, label) ->
                val active = key == selected
                Text(
                    text = label,
                    color = palette.text,
                    maxLines = 1,
                    style = TextStyle(
                        fontFamily = YtFonts.inter,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 16.sp,
                        letterSpacing = (-0.2).sp,
                    ),
                    modifier = Modifier
                        .onGloballyPositioned { bounds[key] = it.positionInParent().x.toInt() to it.size.width }
                        .clickable { onSelect(key) }
                        .padding(bottom = 9.dp),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = barX)
                .width(barWidth)
                .height(3.dp)
                .background(palette.accent),
        )
    }
}

@Composable
private fun EditorialTabs(
    palette: YtPalette,
    tabs: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    large: Boolean,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 17.dp),
        horizontalArrangement = Arrangement.spacedBy(if (large) 34.dp else 26.dp),
        modifier = Modifier.padding(top = if (large) 6.dp else 12.dp),
    ) {
        items(tabs, key = { it.first }) { (key, label) ->
            val active = key == selected
            var width by remember { mutableIntStateOf(0) }
            val bar by animateDpAsState(
                if (active) with(LocalDensity.current) { width.toDp() } else 0.dp,
                ytStateSpec(),
                label = "editorialTab",
            )
            Column(Modifier.clickable { onSelect(key) }) {
                Text(
                    text = label,
                    color = palette.text,
                    maxLines = 1,
                    style = TextStyle(
                        fontFamily = YtFonts.inter,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontSize = if (large) 16.sp else 12.5.sp,
                        letterSpacing = (-0.2).sp,
                    ),
                    onTextLayout = { width = it.size.width },
                )
                Spacer(Modifier.height(if (large) 6.dp else 5.dp))
                Box(
                    Modifier
                        .width(bar)
                        .height(3.dp)
                        .background(palette.accent),
                )
            }
        }
    }
}

@Composable
private fun EditorialItem(
    palette: YtPalette,
    imageLoader: ImageLoader,
    number: Int,
    video: StreamItem,
    actions: YtHomeActions,
    modifier: Modifier = Modifier,
) {
    // print proof: the black-and-white picture takes on its colors while it is pressed
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val color by animateFloatAsState(
        if (pressed && LocalYtMotion.current) 1f else 0f,
        tween(if (pressed) 160 else 320, easing = YtEaseOut),
        label = "editorialColor",
    )
    // pushed either way, the entry slides off a black slip: AUSBLENDEN to the left, ANS ENDE
    // to the right (ytCardSwipe); MERKEN moved to the dots (quick actions)
    Column(
        modifier
            .ytCardSwipe(palette, video, RectangleShape, inset = PaddingValues(end = 16.dp), testTag = "yt_card_${number - 1}")
            // held: a hard frame, then it drags onto the mini player; let go unmoved: quick actions
            .ytHomeCard(palette, video, RectangleShape, inset = PaddingValues(end = 16.dp), onRelease = { actions.onQuick(video) })
            .background(palette.background)
            .padding(start = 17.dp, end = 16.dp, top = 10.dp),
    ) {
        Row(Modifier.clickable(interactionSource = press, indication = null) { actions.onOpenVideo(video) }) {
            Text(
                text = "%02d".format(number),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.inter, fontWeight = FontWeight.Black, fontSize = 29.sp, letterSpacing = (-1).sp),
                modifier = Modifier
                    .width(43.dp)
                    .padding(top = 0.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(317f / 103f)
                    .background(palette.surface),
            ) {
                AsyncImage(
                    model = video.thumbnail,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (color == 0f) ytGrayscale else ytSaturation(color),
                    modifier = Modifier.fillMaxSize(),
                )
                EditorialBadge(
                    palette,
                    video,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 43.dp, top = 5.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable { actions.onOpenVideo(video) },
            ) {
                Text(
                    text = video.title.orEmpty(),
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = (-0.2).sp),
                )
                Text(
                    text = video.uploaderName.orEmpty(),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.inter, fontSize = 11.5.sp),
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .clickable { actions.onOpenChannel(video) },
                )
                Text(
                    text = ytMeta(video),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.inter, fontSize = 11.sp),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Icon(
                painterResource(R.drawable.ic_yt_dots_vertical),
                contentDescription = stringResource(R.string.yt_home_more_actions),
                tint = palette.text,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { actions.onQuick(video) },
            )
        }
        Box(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.text),
        )
    }
}

@Composable
private fun EditorialBadge(palette: YtPalette, video: StreamItem, modifier: Modifier = Modifier) {
    val (text, color) = if (video.isLive) {
        stringResource(R.string.yt_live_badge) to palette.accent
    } else {
        (ytDuration(video) ?: return) to Color.Black
    }
    Text(
        text = text,
        color = Color.White,
        style = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp),
        modifier = modifier
            .background(color)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun EditorialShorts(
    palette: YtPalette,
    imageLoader: ImageLoader,
    shorts: List<StreamItem>,
    actions: YtHomeActions,
) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 17.dp, end = 16.dp, bottom = 9.dp),
        ) {
            Text(
                text = stringResource(R.string.yt_nav_shorts),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.inter, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = (-0.3).sp),
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = actions.onShowShorts),
            ) {
                Text(
                    text = stringResource(R.string.yt_after_show_all),
                    color = palette.text,
                    style = TextStyle(fontFamily = YtFonts.inter, fontSize = 13.sp),
                )
                Icon(
                    painterResource(R.drawable.ic_yt_chevron_right),
                    contentDescription = null,
                    tint = palette.text,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(14.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 17.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            items(shorts, key = { "short-" + it.url }) { short ->
                Column(
                    Modifier
                        .width(174.dp)
                        .clickable { actions.onOpenVideo(short) },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(174f / 83f)
                            .background(palette.surface),
                    ) {
                        AsyncImage(
                            model = short.thumbnail,
                            contentDescription = null,
                            imageLoader = imageLoader,
                            contentScale = ContentScale.Crop,
                            colorFilter = ytGrayscale,
                            modifier = Modifier.fillMaxSize(),
                        )
                        EditorialBadge(
                            palette,
                            short,
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp),
                        )
                    }
                    Text(
                        text = short.title.orEmpty(),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.inter, fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    Text(
                        text = short.uploaderName.orEmpty(),
                        color = palette.textSecondary,
                        maxLines = 1,
                        style = TextStyle(fontFamily = YtFonts.inter, fontSize = 11.sp),
                    )
                    ytViews(short)?.let {
                        Text(
                            text = it,
                            color = palette.text,
                            maxLines = 1,
                            style = TextStyle(fontFamily = YtFonts.inter, fontSize = 11.sp),
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .padding(start = 17.dp, end = 16.dp, top = 12.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.text),
        )
    }
}
