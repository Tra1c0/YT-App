package com.github.libretube.ui.yt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.formatShort

private val GlassShadow = Color(0x405C8C85)

/** Frosted glass: translucent white with a bright edge and a soft shadow. */
fun Modifier.ytGlass(shape: Shape, strong: Boolean = false, shadow: Boolean = true): Modifier = this
    .then(if (shadow) Modifier.shadow(if (strong) 8.dp else 5.dp, shape, clip = false, ambientColor = GlassShadow, spotColor = GlassShadow) else Modifier)
    .clip(shape)
    .background(
        if (strong) Brush.verticalGradient(listOf(Color(0xF7FFFFFF), Color(0xE6FFFFFF)))
        else Brush.verticalGradient(listOf(Color(0x9EFFFFFF), Color(0x5CFFFFFF)))
    )
    .border(1.dp, Color(0xD9FFFFFF), shape)

/**
 * Design "Glas", home: search field, segmented tabs Für dich / Abos / Live, large videos with
 * a glass play button and a Shorts row. Measured in the reference, see docs/DESIGN-MASSE.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    data: YtHomeData,
    tab: String,
    onSelectTab: (String) -> Unit,
    actions: YtHomeActions,
) {
    val source = data.forTab(tab)
    val videos = (if (tab == YT_TAB_LIVE) source?.filter { it.url != null }?.distinctBy { it.url } else ytPlayable(source)).orEmpty()
    val shorts = data.shorts.orEmpty()

    PullToRefreshBox(
        isRefreshing = data.isLoading && source != null,
        onRefresh = actions.onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFCFE9E2), Color(0xFFDDF2EE), Color(0xFFD3ECE6)))),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 18.dp, end = 18.dp, top = 4.dp)
                        .fillMaxWidth()
                        .height(38.dp)
                        .ytGlass(RoundedCornerShape(19.dp))
                        .clickable(onClick = actions.onSearch)
                        .padding(horizontal = 16.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_yt_search),
                        contentDescription = null,
                        tint = palette.text,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.yt_search_youtube),
                        color = palette.textSecondary,
                        style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 13.sp),
                        modifier = Modifier.padding(start = 15.dp),
                    )
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(start = 21.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
                ) {
                    listOf(
                        Triple(YT_TAB_FOR_YOU, R.string.yt_tab_for_you, 1.23f),
                        Triple(YT_TAB_SUBS, R.string.yt_tab_subscriptions, 1f),
                        Triple(YT_TAB_LIVE, R.string.yt_tab_live, 1.16f),
                    ).forEach { (key, label, weight) ->
                        val active = key == tab
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(weight)
                                .height(31.dp)
                                .ytGlass(RoundedCornerShape(16.dp), strong = active, shadow = active)
                                .clickable { onSelectTab(key) },
                        ) {
                            Text(
                                text = stringResource(label),
                                color = palette.text,
                                maxLines = 1,
                                style = TextStyle(
                                    fontFamily = YtFonts.googleSans,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = if (active) 14.sp else 13.5.sp,
                                ),
                            )
                        }
                    }
                }
            }
            if (source == null || videos.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        if (source == null || data.isLoading) CircularProgressIndicator(color = palette.text)
                        else Text(stringResource(R.string.yt_home_nothing), color = palette.textSecondary, modifier = Modifier.padding(32.dp))
                    }
                }
            }
            items(videos.take(2), key = { "top-" + it.url }) { GlassVideo(palette, imageLoader, it, actions) }
            if (shorts.isNotEmpty() && tab != YT_TAB_LIVE) {
                item { GlassShorts(palette, imageLoader, shorts, actions) }
            }
            items(videos.drop(2), key = { "more-" + it.url }) { GlassVideo(palette, imageLoader, it, actions) }
        }
    }
}

@Composable
private fun GlassVideo(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, actions: YtHomeActions) {
    Column(Modifier.padding(start = 20.dp, end = 18.dp, bottom = 11.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(355f / 175f)
                .shadow(6.dp, RoundedCornerShape(12.dp), clip = false, ambientColor = GlassShadow, spotColor = GlassShadow)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.lime)
                .clickable { actions.onOpenVideo(video) },
        ) {
            YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize())
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .ytGlass(CircleShape),
            ) {
                Icon(
                    painterResource(R.drawable.ic_yt_play_round),
                    contentDescription = null,
                    tint = palette.text,
                    modifier = Modifier
                        .padding(start = 3.dp)
                        .size(24.dp),
                )
            }
            (if (video.isLive) stringResource(R.string.yt_live_badge) else ytDuration(video))?.let {
                Text(
                    text = it,
                    color = Color.White,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (video.isLive) palette.live else Color(0xB3101C1E))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            AsyncImage(
                model = video.uploaderAvatar ?: video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(palette.lime)
                    .clickable { actions.onOpenChannel(video) },
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
                    .clickable { actions.onOpenVideo(video) },
            ) {
                Text(
                    text = video.title.orEmpty(),
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 15.5.sp, lineHeight = 19.sp),
                )
                Text(
                    text = listOf(video.uploaderName.orEmpty(), ytMeta(video, " · ")).filter { it.isNotBlank() }.joinToString("  ·  "),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.sp),
                    modifier = Modifier.padding(top = 2.dp),
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
    }
}

@Composable
private fun GlassShorts(palette: YtPalette, imageLoader: ImageLoader, shorts: List<StreamItem>, actions: YtHomeActions) {
    Column(Modifier.padding(top = 4.dp, bottom = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 21.dp, end = 18.dp, bottom = 10.dp),
        ) {
            YtShortsGlyph(palette.live, filled = true, modifier = Modifier.size(24.dp))
            Text(
                text = stringResource(R.string.yt_nav_shorts),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 18.sp),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(26.dp)
                    .ytGlass(RoundedCornerShape(13.dp), shadow = false)
                    .clickable(onClick = actions.onShowShorts)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.yt_after_show_all),
                    color = palette.text,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.5.sp),
                )
                Icon(
                    painterResource(R.drawable.ic_yt_chevron_right),
                    contentDescription = null,
                    tint = palette.text,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(12.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(shorts, key = { "short-" + it.url }) { short ->
                Box(
                    Modifier
                        .width(173.dp)
                        .aspectRatio(173f / 132f)
                        .shadow(5.dp, RoundedCornerShape(12.dp), clip = false, ambientColor = GlassShadow, spotColor = GlassShadow)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.lime)
                        .clickable { actions.onOpenVideo(short) },
                ) {
                    YtLargeThumbnail(short, imageLoader, Modifier.fillMaxSize())
                    Icon(
                        painterResource(R.drawable.ic_yt_dots_vertical),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(18.dp),
                    )
                    short.views?.takeIf { it > 0 }?.let { views ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0x8C101C1E))
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_yt_eye),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = views.formatShort(),
                                color = Color.White,
                                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 12.5.sp),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
