package com.github.libretube.ui.yt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem

private val FrostDark = Color(0xFF1F2A26)

/** Frosted sage glass: a little white, a light edge. */
private fun Modifier.frost(shape: Shape): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x1FFFFFFF))))
    .border(1.dp, Color(0x59FFFFFF), shape)

/**
 * Design "Frost" (from inspiration image 7): sage with frosted glass. A greeting, search, today's
 * numbers as glass cards (new videos, live, Shorts), then a large "Für dich" card and a list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrostHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    data: YtHomeData,
    actions: YtHomeActions,
) {
    val videos = ytPlayable(data.forYou).orEmpty()
    val newVideos = data.subscriptions?.count { System.currentTimeMillis() - it.uploaded < 24L * 60 * 60 * 1000 }
    val live = data.live?.count { it.isLive }
    val shorts = data.shorts?.size
    val enter = rememberYtEnterState()

    PullToRefreshBox(
        isRefreshing = data.isLoading && data.forYou != null,
        onRefresh = actions.onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF6F8A80), Color(0xFF627B71), Color(0xFF536A61)))),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 20.dp + ytNavPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    text = buildAnnotatedString {
                        val greeting = stringResource(R.string.yt_frost_greeting)
                        val split = greeting.indexOf('!').takeIf { it > 0 }?.plus(1) ?: 0
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(greeting.substring(0, split)) }
                        append(greeting.substring(split))
                    },
                    color = palette.text,
                    style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp),
                )
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .frost(RoundedCornerShape(23.dp))
                        .clickable(onClick = actions.onSearch)
                        .padding(horizontal = 6.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    ) {
                        Icon(painterResource(R.drawable.ic_yt_search), contentDescription = null, tint = FrostDark, modifier = Modifier.size(17.dp))
                    }
                    Text(
                        text = stringResource(R.string.yt_search_youtube),
                        color = palette.textSecondary,
                        style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 14.sp),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            item { FrostSection(palette, stringResource(R.string.yt_frost_today), actions.onShowSubscriptions) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { FrostStat(palette, stringResource(R.string.yt_frost_new), newVideos, stringResource(R.string.yt_tab_subscriptions), actions.onShowSubscriptions) }
                    item { FrostStat(palette, stringResource(R.string.yt_frost_live), live, stringResource(R.string.yt_live_badge), actions.onShowSubscriptions) }
                    item { FrostStat(palette, stringResource(R.string.yt_frost_shorts), shorts, stringResource(R.string.yt_nav_shorts), actions.onShowShorts) }
                }
            }
            item { FrostSection(palette, stringResource(R.string.yt_frost_for_you), null) }
            if (videos.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        if (data.isLoading) CircularProgressIndicator(color = Color.White)
                        else Text(stringResource(R.string.yt_home_nothing), color = palette.textSecondary)
                    }
                }
            }
            videos.firstOrNull()?.let { first ->
                item(key = "big-" + first.url) {
                    Box(Modifier.ytEnter(enter, "big" + first.url, 0)) { FrostBig(palette, imageLoader, first, actions) }
                }
            }
            items(videos.drop(1), key = { "row-" + it.url }) {
                Box(Modifier.ytEnter(enter, "row" + it.url, videos.indexOf(it))) { FrostRow(palette, imageLoader, it, actions) }
            }
        }
    }
}

@Composable
private fun FrostSection(palette: YtPalette, title: String, onSeeAll: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = title,
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 17.sp),
            modifier = Modifier.weight(1f),
        )
        if (onSeeAll != null) {
            Text(
                text = stringResource(R.string.yt_frost_see_all),
                color = palette.textSecondary,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 12.5.sp),
                modifier = Modifier.clickable(onClick = onSeeAll),
            )
        }
    }
}

@Composable
private fun FrostStat(palette: YtPalette, label: String, value: Int?, tag: String, onClick: () -> Unit) {
    Column(
        Modifier
            .width(128.dp)
            .height(138.dp)
            .frost(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(
            text = label,
            color = palette.text,
            maxLines = 2,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp),
        )
        Text(
            text = ytCountUp(value ?: 0).toString(),
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp),
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tag,
                color = FrostDark,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE8EEEA))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFFFFC27A), palette.accent))),
            )
        }
    }
}

@Composable
private fun FrostBig(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, actions: YtHomeActions) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.35f)
            .frost(RoundedCornerShape(26.dp))
            .clickable { actions.onOpenVideo(video) }
            .padding(8.dp),
    ) {
        YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xB31F2A26))),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, end = 70.dp, bottom = 14.dp),
        ) {
            Text(
                text = video.title.orEmpty(),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOfNotNull(video.uploaderName, ytDuration(video)).forEach {
                    Text(
                        text = it,
                        color = Color.White,
                        maxLines = 1,
                        style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x40FFFFFF))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.White),
        ) {
            Icon(painterResource(R.drawable.ic_yt_play_round), contentDescription = null, tint = FrostDark, modifier = Modifier.padding(start = 2.dp).size(20.dp))
        }
    }
}

@Composable
private fun FrostRow(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, actions: YtHomeActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .frost(RoundedCornerShape(20.dp))
            .clickable { actions.onOpenVideo(video) }
            .padding(8.dp),
    ) {
        YtLargeThumbnail(
            video,
            imageLoader,
            Modifier
                .width(128.dp)
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(14.dp)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 6.dp),
        ) {
            Text(
                text = video.title.orEmpty(),
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
            )
            Text(
                text = video.uploaderName.orEmpty(),
                color = palette.textSecondary,
                maxLines = 1,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 12.sp),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { actions.onOpenChannel(video) },
            )
            Text(
                text = ytMeta(video, " · "),
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 11.5.sp),
            )
        }
    }
}
