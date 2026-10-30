package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
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
    val opening = rememberYtOpening()
    val enter = rememberYtEnterState()
    val open: (StreamItem) -> Unit = { video ->
        opening.value = video.url
        onOpenVideo(video)
    }

    PullToRefreshBox(
        isRefreshing = isLoading && videos != null,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        val list = rememberLazyListState()
        val motion = LocalYtMotion.current
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + ytNavPadding()),
        ) {
            item {
                // a camera move: the poster stays behind at half speed and darkens as the
                // programme scrolls over it
                Box(
                    Modifier
                        .fillParentMaxHeight(0.78f)
                        .clipToBounds(),
                ) {
                    AfterHero(
                        palette, imageLoader, hero, opening.value, tab, isLoading || videos == null, onSelectTab, open,
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (motion && list.firstVisibleItemIndex == 0) {
                                    translationY = list.firstVisibleItemScrollOffset * 0.5f
                                }
                            }
                            .drawWithContent {
                                drawContent()
                                if (motion && list.firstVisibleItemIndex == 0) {
                                    val fade = (list.firstVisibleItemScrollOffset / size.height).coerceIn(0f, 1f)
                                    drawRect(Color.Black.copy(alpha = 0.6f * fade))
                                }
                            },
                    )
                }
            }
            if (later.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 23.dp, end = 18.dp, top = 14.dp, bottom = 9.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.yt_after_for_later),
                            color = palette.text,
                            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(onClick = onShowAll),
                        ) {
                            Text(
                                text = stringResource(R.string.yt_after_show_all),
                                color = palette.textSecondary,
                                style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.5.sp),
                            )
                            Icon(
                                painterResource(R.drawable.ic_arrow_right),
                                contentDescription = null,
                                tint = palette.textSecondary,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(13.dp),
                            )
                        }
                    }
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(later, key = { "later-" + it.url }) { video ->
                            AfterPoster(
                                palette, imageLoader, video, open,
                                Modifier
                                    .width(177.dp)
                                    .ytEnter(enter, "later" + video.url, later.indexOf(video)),
                            )
                        }
                    }
                }
            }
            if (more.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.yt_after_more),
                        color = palette.text,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                        modifier = Modifier.padding(start = 23.dp, end = 18.dp, top = 20.dp, bottom = 9.dp),
                    )
                }
                itemsIndexed(more.chunked(2)) { row, pair ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
                            .ytEnter(enter, "more" + pair.first().url, row),
                    ) {
                        pair.forEach { video ->
                            AfterPoster(palette, imageLoader, video, open, Modifier.weight(1f))
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
    openingUrl: String?,
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
        // a calm crossfade between posters, and into playback when the film starts
        val heroAlpha by animateFloatAsState(
            targetValue = if (hero != null && openingUrl == hero.url) 0.3f else 1f,
            animationSpec = ytSpec(tween(450)),
            label = "heroFade",
        )
        Crossfade(
            targetState = hero,
            animationSpec = ytFinite(tween(700)),
            label = "heroPoster",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = heroAlpha },
        ) { video ->
            if (video != null) {
                val zoom by rememberKenBurns()
                YtLargeThumbnail(
                    video,
                    imageLoader,
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                        },
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to palette.background.copy(alpha = 0.88f),
                        0.2f to palette.background.copy(alpha = 0.45f),
                        0.32f to Color.Transparent,
                        0.45f to palette.background.copy(alpha = 0.15f),
                        0.78f to palette.background.copy(alpha = 0.88f),
                        1f to palette.background,
                    )
                ),
        )
        val headerOverlap = with(LocalDensity.current) { YtChrome.headerOverlapPx.toDp() }
        Row(
            horizontalArrangement = Arrangement.spacedBy(25.dp),
            modifier = Modifier.padding(start = 27.dp, end = 20.dp, top = headerOverlap + 4.dp),
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
                        color = if (active) palette.text else palette.text.copy(alpha = 0.86f),
                        style = TextStyle(
                            fontFamily = YtFonts.geist,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp,
                            shadow = Shadow(Color(0xCC000000), Offset(0f, 1f), 8f),
                        ),
                        modifier = Modifier.padding(top = 6.dp, bottom = 7.dp),
                    )
                    val underline by animateDpAsState(if (active) 47.dp else 0.dp, ytStateSpec(), label = "afterTab")
                    Box(
                        Modifier
                            .width(underline)
                            .height(2.5.dp)
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
                .padding(start = 27.dp, end = 25.dp, bottom = 17.dp),
        ) {
            // Poster type: the title in condensed caps as a narrow column on the left. Real titles
            // are longer than the reference's three words, so the size steps down until it fits.
            val title = hero.title.orEmpty().uppercase()
            var size by remember(hero.url) { mutableStateOf(63.sp) }
            Text(
                text = title,
                color = palette.text,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = YtFonts.anton,
                    fontSize = size,
                    lineHeight = size * 0.98f,
                ),
                onTextLayout = { layout ->
                    if (layout.hasVisualOverflow && size > 34.sp) size = (size.value - 3f).sp
                },
                modifier = Modifier.fillMaxWidth(0.66f),
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = hero.uploaderName.orEmpty(),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
                    )
                    Text(
                        text = listOfNotNull(
                            hero.uploadedDate,
                            hero.views?.takeIf { it > 0 }?.let { stringResource(R.string.yt_after_views, it.formatShort()) },
                        ).joinToString(" · "),
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
                    )
                }
                if (hero.isLive) {
                    AfterLiveBadge(palette)
                } else hero.duration?.takeIf { it > 0 }?.let {
                    Text(
                        text = DateUtils.formatElapsedTime(it),
                        color = palette.text,
                        style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
                    )
                }
            }
            // one short ivory button, 166 x 35 dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .width(166.dp)
                    .height(35.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFFF7EBD8))
                    .clickable { onOpenVideo(hero) }
                    .padding(start = 26.dp, end = 18.dp),
            ) {
                Text(
                    text = stringResource(if (hero.isLive) R.string.yt_after_enter_room else R.string.yt_after_watch),
                    color = palette.ink,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                )
                Icon(
                    painterResource(R.drawable.ic_arrow_right),
                    contentDescription = null,
                    tint = palette.ink,
                    modifier = Modifier.size(16.dp),
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
            .aspectRatio(177f / 105f)
            .ytPressable(RoundedCornerShape(10.dp)) { onOpenVideo(video) }
            .background(palette.surface),
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
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = video.title.orEmpty(),
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, lineHeight = 14.sp),
                )
                Text(
                    text = video.uploaderName.orEmpty(),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 11.sp),
                )
            }
            if (video.isLive) {
                AfterLiveBadge(palette, Modifier.padding(start = 6.dp))
            } else video.duration?.takeIf { it > 0 }?.let {
                Text(
                    text = DateUtils.formatElapsedTime(it),
                    color = palette.text,
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 11.sp),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** Small tangerine LIVE label. */
@Composable
fun AfterLiveBadge(palette: YtPalette, modifier: Modifier = Modifier) {
    val pulse by rememberPulse(min = 0.6f, durationMillis = 1200)
    Text(
        text = stringResource(R.string.yt_live_badge),
        color = palette.onAccent,
        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 12.sp),
        modifier = modifier
            .graphicsLayer { alpha = pulse }
            .clip(RoundedCornerShape(50))
            .background(palette.accent)
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}
