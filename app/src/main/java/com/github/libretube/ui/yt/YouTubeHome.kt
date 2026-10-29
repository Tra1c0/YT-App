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
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
) {
    val source = if (selectedFilter == YT_FILTER_ALL) feed ?: trending else trending
    val videos = source.orEmpty().filter { !it.isShort && !it.isUpcoming && it.url != null }
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
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.yt_home_headline),
                    color = palette.text,
                    style = TextStyle(
                        fontFamily = YtFonts.geist,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 34.sp,
                        lineHeight = 36.sp,
                        letterSpacing = (-0.6).sp,
                    ),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
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
                item { SectionTitle(palette, stringResource(R.string.yt_home_live_now)) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 20.dp),
    ) {
        items(filters, key = { it.first }) { (key, label) ->
            val active = key == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) palette.text else palette.surface)
                    .clickable { onSelect(key) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    text = label,
                    color = if (active) palette.ink else palette.text,
                    style = TextStyle(
                        fontFamily = YtFonts.geist,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 15.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(palette: YtPalette, title: String) {
    Text(
        text = title,
        color = palette.text,
        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 21.sp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
    )
}

@Composable
private fun Thumbnail(
    imageLoader: ImageLoader,
    url: String?,
    corner: Int,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = url,
        contentDescription = null,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(corner.dp)),
    )
}

@Composable
private fun DurationChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = androidx.compose.ui.graphics.Color.White,
        style = TextStyle(fontFamily = YtFonts.geistMono, fontWeight = FontWeight.Medium, fontSize = 12.sp),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(androidx.compose.ui.graphics.Color(0xCC000000))
            .padding(horizontal = 7.dp, vertical = 3.dp),
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
            .clickable { onOpenVideo(video) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Box {
            Thumbnail(imageLoader, video.thumbnail, corner = 20, modifier = Modifier.fillMaxWidth())
            formatDuration(video.duration)?.let {
                DurationChip(
                    it,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AsyncImage(
                model = video.uploaderAvatar,
                contentDescription = video.uploaderName,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(palette.surface)
                    .clickable { onOpenChannel(video) },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title.orEmpty(),
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = YtFonts.geist,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
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
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
                    modifier = Modifier.padding(top = 4.dp),
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
            .width(232.dp)
            .clickable { onOpenVideo(video) },
    ) {
        Box {
            Thumbnail(imageLoader, video.thumbnail, corner = 16, modifier = Modifier.fillMaxWidth())
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.live)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
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
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 11.sp),
                )
            }
        }
        Text(
            text = video.title.orEmpty(),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = video.uploaderName.orEmpty(),
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
        )
    }
}
