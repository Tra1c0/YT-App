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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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

/** Tabs of the After home screen; "Filme" and "Live" are trending categories. */
const val AFTER_TAB_SELECTION = "SELECTION"

/**
 * YT design "After", Heute: a full-width film poster with condensed all-caps title, short
 * credits, duration and one ivory "Ansehen" button, then "Für später" as small posters and
 * further videos as a two-column poster grid. Burgundy, bone white, tangerine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfterHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    videos: List<StreamItem>?,
    continueWatching: List<StreamItem>?,
    tab: String,
    isLoading: Boolean,
    onSelectTab: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenVideo: (StreamItem) -> Unit,
    onShowAll: () -> Unit,
) {
    val playable = videos.orEmpty()
        .filter { !it.isShort && !it.isUpcoming && it.url != null }
        .distinctBy { it.url }
    val hero = playable.firstOrNull()
    val later = continueWatching.orEmpty().filter { it.url != null }.distinctBy { it.url }
        .ifEmpty { playable.drop(1).take(6) }
    val more = playable.drop(1).filter { candidate -> later.none { it.url == candidate.url } }

    PullToRefreshBox(
        isRefreshing = isLoading && videos != null,
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
                AfterHero(
                    palette, imageLoader, hero, tab, isLoading || videos == null, onSelectTab, onOpenVideo,
                    Modifier.fillParentMaxHeight(0.82f),
                )
            }
            if (later.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.yt_after_for_later),
                            color = palette.text,
                            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(onClick = onShowAll),
                        ) {
                            Text(
                                text = stringResource(R.string.yt_after_show_all),
                                color = palette.textSecondary,
                                style = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp),
                            )
                            Icon(
                                painterResource(R.drawable.ic_arrow_right),
                                contentDescription = null,
                                tint = palette.textSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(later, key = { "later-" + it.url }) { video ->
                            AfterPoster(palette, imageLoader, video, onOpenVideo, Modifier.width(220.dp))
                        }
                    }
                }
            }
            if (more.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.yt_after_more),
                        color = palette.text,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp),
                    )
                }
                items(more.chunked(2)) { pair ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                    ) {
                        pair.forEach { video ->
                            AfterPoster(palette, imageLoader, video, onOpenVideo, Modifier.weight(1f))
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AfterHero(
    palette: YtPalette,
    imageLoader: ImageLoader,
    hero: StreamItem?,
    tab: String,
    isLoading: Boolean,
    onSelectTab: (String) -> Unit,
    onOpenVideo: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 520.dp)
            .background(palette.surface),
    ) {
        if (hero != null) {
            AsyncImage(
                model = hero.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to palette.background.copy(alpha = 0.70f),
                        0.18f to Color.Transparent,
                        0.45f to palette.background.copy(alpha = 0.15f),
                        0.78f to palette.background.copy(alpha = 0.88f),
                        1f to palette.background,
                    )
                ),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
        ) {
            val tabs = listOf(
                AFTER_TAB_SELECTION to stringResource(R.string.yt_after_selection),
                TrendingCategory.TRAILERS.name to stringResource(R.string.yt_after_films),
                TrendingCategory.LIVE.name to stringResource(R.string.yt_after_live),
            )
            tabs.forEach { (key, label) ->
                val active = key == tab
                Column(
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.clickable { onSelectTab(key) },
                ) {
                    Text(
                        text = label,
                        color = if (active) palette.text else palette.text.copy(alpha = 0.72f),
                        style = TextStyle(
                            fontFamily = YtFonts.geist,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 17.sp,
                        ),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Box(
                        Modifier
                            .width(if (active) 60.dp else 0.dp)
                            .height(2.dp)
                            .background(palette.text),
                    )
                }
            }
        }
        if (hero == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isLoading) CircularProgressIndicator(color = palette.accent)
                else Text(stringResource(R.string.yt_home_empty), color = palette.textSecondary)
            }
        } else Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            // Poster type: the title in condensed caps, set as a narrow column on the left.
            val title = hero.title.orEmpty().uppercase()
            val size = when {
                title.length <= 20 -> 72.sp
                title.length <= 36 -> 56.sp
                title.length <= 60 -> 44.sp
                else -> 36.sp
            }
            Text(
                text = title,
                color = palette.text,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = YtFonts.anton,
                    fontSize = size,
                    lineHeight = size * 0.96f,
                ),
                modifier = Modifier.fillMaxWidth(0.78f),
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = hero.uploaderName.orEmpty(),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
                    )
                    Text(
                        text = listOfNotNull(
                            hero.uploadedDate,
                            hero.views?.takeIf { it > 0 }?.let { stringResource(R.string.yt_after_views, it.formatShort()) },
                        ).joinToString(" · "),
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp),
                    )
                }
                if (hero.isLive) {
                    AfterLiveBadge(palette)
                } else hero.duration?.takeIf { it > 0 }?.let {
                    Text(
                        text = DateUtils.formatElapsedTime(it),
                        color = palette.text,
                        style = TextStyle(fontFamily = YtFonts.geistMono, fontSize = 15.sp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.text)
                    .clickable { onOpenVideo(hero) }
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            ) {
                Text(
                    text = stringResource(if (hero.isLive) R.string.yt_after_enter_room else R.string.yt_after_watch),
                    color = palette.ink,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                )
                Spacer(Modifier.width(14.dp))
                Icon(
                    painterResource(R.drawable.ic_arrow_right),
                    contentDescription = null,
                    tint = palette.ink,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AfterPoster(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    onOpenVideo: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 11f)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.surface)
            .clickable { onOpenVideo(video) },
    ) {
        AsyncImage(
            model = video.thumbnail,
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to palette.background.copy(alpha = 0.95f),
                    )
                ),
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = video.title.orEmpty(),
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.sp),
                )
                Text(
                    text = video.uploaderName.orEmpty(),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.sp),
                )
            }
            if (video.isLive) {
                AfterLiveBadge(palette, Modifier.padding(start = 6.dp))
            } else video.duration?.takeIf { it > 0 }?.let {
                Text(
                    text = DateUtils.formatElapsedTime(it),
                    color = palette.text,
                    style = TextStyle(fontFamily = YtFonts.geistMono, fontSize = 12.sp),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** Small tangerine LIVE label. */
@Composable
fun AfterLiveBadge(palette: YtPalette, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.yt_live_badge),
        color = palette.onAccent,
        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 12.sp),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(palette.accent)
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}
