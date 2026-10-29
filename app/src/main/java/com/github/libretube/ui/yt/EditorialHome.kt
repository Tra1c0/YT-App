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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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

    PullToRefreshBox(
        isRefreshing = data.isLoading && source != null,
        onRefresh = actions.onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                EditorialTabs(
                    palette = palette,
                    tabs = listOf(
                        YT_TAB_FOR_YOU to stringResource(R.string.yt_tab_for_you),
                        YT_TAB_SUBS to stringResource(R.string.yt_tab_subscriptions),
                        YT_TAB_LIVE to stringResource(R.string.yt_tab_live),
                    ),
                    selected = tab,
                    onSelect = onSelectTab,
                    large = true,
                )
                Box(
                    Modifier
                        .padding(horizontal = 17.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.text),
                )
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
                EditorialItem(palette, imageLoader, index + 1, video, actions)
            }
            if (shorts.isNotEmpty() && tab != YT_TAB_LIVE) {
                item { EditorialShorts(palette, imageLoader, shorts, actions) }
            }
            itemsIndexed(videos.drop(3), key = { _, v -> "more-" + v.url }) { index, video ->
                EditorialItem(palette, imageLoader, index + 4, video, actions)
            }
        }
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
) {
    Column(Modifier.padding(start = 17.dp, end = 16.dp, top = 10.dp)) {
        Row(Modifier.clickable { actions.onOpenVideo(video) }) {
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
                    colorFilter = ytGrayscale,
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
                contentDescription = null,
                tint = palette.text,
                modifier = Modifier
                    .size(22.dp)
                    .clickable { actions.onSave(video) },
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
