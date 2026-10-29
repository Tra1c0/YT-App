package com.github.libretube.ui.yt

import android.text.format.DateUtils
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.github.libretube.extensions.formatShort
import com.github.libretube.util.TextUtils

/** Key of the "Alle" filter; the other filters are trending categories. */
const val YT_FILTER_ALL = "ALL"

/**
 * YT design "YouTube, neu gedacht", view A (Startseite): headline, topic filters, one dominant
 * recommendation, "Gerade live" and further videos. Spacing follows the brief: 20dp gutters,
 * 20dp video corners, 16dp compact surfaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    PullToRefreshBox(
        isRefreshing = isLoading && source != null,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
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
            item {
                FilterRow(palette, categories, selectedFilter, onSelectFilter)
            }
            if (source == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoading) CircularProgressIndicator(color = palette.accent)
                        else Text(stringResource(R.string.yt_home_empty), color = palette.textSecondary)
                    }
                }
            }
            hero?.let { video ->
                item {
                    LargeVideo(palette, imageLoader, video, onOpenVideo, onOpenChannel)
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
                            LiveCard(palette, imageLoader, video, onOpenVideo)
                        }
                    }
                }
            }
            if (rest.isNotEmpty()) {
                item { SectionTitle(palette, stringResource(R.string.yt_home_more)) }
                items(rest, key = { it.url!! }) { video ->
                    LargeVideo(palette, imageLoader, video, onOpenVideo, onOpenChannel)
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    palette: YtPalette,
    categories: List<TrendingCategory>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val filters = listOf(YT_FILTER_ALL to stringResource(R.string.all)) +
        categories.map { it.name to stringResource(it.titleRes) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 9.dp, bottom = 15.dp),
    ) {
        items(filters, key = { it.first }) { (key, label) ->
            val active = key == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) palette.text else palette.surface)
                    .clickable { onSelect(key) }
                    .height(34.dp)
                    .padding(horizontal = 19.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) palette.ink else palette.text,
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
    onOpenVideo: (StreamItem) -> Unit,
    onOpenChannel: (StreamItem) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
) {
    Column(
        modifier = Modifier
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
