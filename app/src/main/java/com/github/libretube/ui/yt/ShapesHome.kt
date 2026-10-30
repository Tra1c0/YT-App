package com.github.libretube.ui.yt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.github.libretube.api.obj.StreamItem
import kotlin.math.PI
import kotlin.math.sin

private val ShapesYellow = Color(0xFFF6D365)
private val ShapesSalmon = Color(0xFFF28B82)
private val ShapesLimeDark = Color(0xFF1F3B12)

/**
 * Design "Formen" (from the inspiration images 5 and 6): a night gradient with bright Material
 * shapes as widgets: new videos in a yellow pill, Shorts and Live tiles, search, a "continue
 * watching" card with a wavy progress line and big rounded controls, then videos in playful
 * rounded cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShapesHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    data: YtHomeData,
    progress: (StreamItem) -> Float,
    actions: YtHomeActions,
) {
    val videos = ytPlayable(data.forYou).orEmpty()
    val continueItem = data.continueWatching?.firstOrNull { it.url != null } ?: videos.firstOrNull()
    val newCount = data.subscriptions?.count { System.currentTimeMillis() - it.uploaded < DAY_MILLIS } ?: 0
    val liveCount = data.live?.count { it.isLive } ?: 0
    val enter = rememberYtEnterState()

    YtRefreshBox(
        palette = palette,
        isRefreshing = data.isLoading && data.forYou != null,
        onRefresh = actions.onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF12122A), Color(0xFF1C1A45), Color(0xFF3F3489), Color(0xFF6656C4)))),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp + ytNavPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .height(170.dp)
                        .ytEnter(enter, "widgets", 0),
                ) {
                    // new videos: a tilted yellow pill with big numbers
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 80.dp, topEnd = 40.dp, bottomEnd = 80.dp, bottomStart = 40.dp))
                            .background(ShapesYellow)
                            .clickable(onClick = actions.onShowSubscriptions),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "%02d".format(ytCountUp(newCount.coerceAtMost(99))),
                                color = palette.ink,
                                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 54.sp, lineHeight = 54.sp),
                            )
                            Text(
                                text = stringResource(R.string.yt_shapes_new),
                                color = palette.ink.copy(alpha = 0.7f),
                                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 22.sp),
                            )
                            Text(
                                text = stringResource(R.string.yt_shapes_new_in_subs),
                                color = palette.ink.copy(alpha = 0.7f),
                                style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.sp),
                            )
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(22.dp))
                                .background(palette.surface)
                                .padding(6.dp),
                        ) {
                            ShapesTile(Color(0xFFF7C4D0), Modifier.weight(1f), actions.onShowShorts) {
                                YtShortsGlyph(palette.ink, filled = true, modifier = Modifier.size(24.dp))
                            }
                            ShapesTile(palette.lavender, Modifier.weight(1f), actions.onShowSubscriptions) {
                                Icon(painterResource(R.drawable.ic_yt_nav_subs), contentDescription = null, tint = palette.ink, modifier = Modifier.size(24.dp))
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(23.dp))
                                .background(palette.surface)
                                .clickable(onClick = actions.onSearch)
                                .padding(horizontal = 14.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_yt_search), contentDescription = null, tint = palette.ink, modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(R.string.yt_search_youtube),
                                color = palette.ink.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 13.sp),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
            continueItem?.let { video ->
                item {
                    Box(Modifier.ytEnter(enter, "continue", 1)) {
                        ShapesContinue(palette, imageLoader, video, progress(video), actions)
                    }
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .height(150.dp)
                        .ytEnter(enter, "tiles", 2),
                ) {
                    // live now: big condensed numbers on salmon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(24.dp))
                            .background(ShapesSalmon)
                            .clickable(onClick = actions.onShowSubscriptions)
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = "%02d".format(ytCountUp(liveCount.coerceAtMost(99))),
                            color = Color(0xFF4A1C1A),
                            style = TextStyle(fontFamily = YtFonts.anton, fontSize = 78.sp, lineHeight = 78.sp),
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(
                                text = stringResource(R.string.yt_live_badge),
                                color = Color(0xFF4A1C1A),
                                style = TextStyle(fontFamily = YtFonts.anton, fontSize = 28.sp),
                            )
                            Text(
                                text = stringResource(R.string.yt_shapes_live_rooms),
                                color = Color(0xFF4A1C1A),
                                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                            )
                        }
                    }
                    // first short in a lime drop
                    // a Short, or else something live, in the lime drop
                    val short = data.shorts?.firstOrNull() ?: data.live?.firstOrNull { it.url != null }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(150.dp)
                            .clip(RoundedCornerShape(topStart = 75.dp, topEnd = 75.dp, bottomEnd = 75.dp, bottomStart = 16.dp))
                            .background(palette.lime)
                            .clickable { short?.let(actions.onOpenVideo) ?: actions.onShowShorts() },
                    ) {
                        Box(
                            Modifier
                                .size(112.dp)
                                .clip(CookieShape(11))
                                .background(ShapesLimeDark),
                        ) {
                            short?.let { YtLargeThumbnail(it, imageLoader, Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
            if (videos.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        if (data.isLoading) CircularProgressIndicator(color = palette.lime)
                        else Text(stringResource(R.string.yt_home_nothing), color = palette.textSecondary)
                    }
                }
            }
            itemsIndexed(videos.drop(1), key = { _, v -> "v-" + v.url }) { index, video ->
                Box(Modifier.ytEnter(enter, "v" + video.url, index + 3)) {
                    ShapesVideo(palette, imageLoader, video, index, actions)
                }
            }
        }
    }
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

@Composable
private fun ShapesTile(color: Color, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .clickable(onClick = onClick),
    ) { content() }
}

/** The player-like card: title, a wavy progress line and big rounded controls. */
@Composable
private fun ShapesContinue(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, progress: Float, actions: YtHomeActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 90.dp, bottomEnd = 90.dp))
            .background(palette.surface)
            .clickable { actions.onOpenVideo(video) }
            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 22.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.yt_shapes_continue),
                color = palette.accent,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 12.sp),
            )
            Text(
                text = video.title.orEmpty(),
                color = palette.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp),
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = video.uploaderName.orEmpty(),
                color = palette.ink.copy(alpha = 0.6f),
                maxLines = 1,
                style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.sp),
            )
            WavyProgress(
                progress,
                palette.accent,
                Color(0xFFD9D4EE),
                Modifier.padding(top = 12.dp).fillMaxWidth().height(18.dp),
                phase = wavePhase(),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = 14.dp)
                .size(84.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF6656C4)),
        ) {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier.fillMaxSize(),
            )
            Icon(painterResource(R.drawable.ic_yt_play_round), contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
    }
}

/** Material 3 Expressive style progress: wavy where played, straight where not, with a thumb. */
@Composable
fun WavyProgress(progress: Float, played: Color, rest: Color, modifier: Modifier = Modifier, phase: () -> Float = { 0f }) {
    Canvas(modifier) {
        val stroke = size.height * 0.22f
        val mid = size.height / 2f
        val end = size.width * progress.coerceIn(0f, 1f)
        val wave = Path()
        val wavelength = size.height * 1.6f
        val amplitude = size.height * 0.22f
        var x = 0f
        wave.moveTo(0f, mid)
        while (x <= end) {
            // the wave travels along the played part while [phase] runs from 0 to 1
            wave.lineTo(x, mid + amplitude * sin(2 * PI * (x / wavelength - phase())).toFloat())
            x += 2f
        }
        drawPath(wave, played, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawLine(rest, Offset(end + stroke * 1.5f, mid), Offset(size.width, mid), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(played, Offset(end, mid - size.height * 0.45f), Offset(end, mid + size.height * 0.45f), strokeWidth = stroke * 1.4f, cap = StrokeCap.Round)
    }
}

@Composable
private fun ShapesVideo(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, index: Int, actions: YtHomeActions) {
    // alternating corners give each card its own shape
    val shape = when (index % 3) {
        0 -> RoundedCornerShape(28.dp)
        1 -> RoundedCornerShape(topStart = 60.dp, topEnd = 16.dp, bottomEnd = 60.dp, bottomStart = 16.dp)
        else -> RoundedCornerShape(topStart = 16.dp, topEnd = 60.dp, bottomEnd = 16.dp, bottomStart = 60.dp)
    }
    val tag = listOf(palette.lime, ShapesYellow, palette.pink, palette.lavender)[index % 4]
    Column(Modifier.clickable { actions.onOpenVideo(video) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(palette.raised),
        ) {
            YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize())
            (if (video.isLive) stringResource(R.string.yt_live_badge) else ytDuration(video))?.let {
                Text(
                    text = it,
                    color = palette.ink,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(tag)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp),
        ) {
            AsyncImage(
                model = video.uploaderAvatar,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CookieShape(8, 0.06f))
                    .background(tag)
                    .clickable { actions.onOpenChannel(video) },
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = video.title.orEmpty(),
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.sp),
                )
                Text(
                    text = listOf(video.uploaderName.orEmpty(), ytMeta(video, " · ")).filter { it.isNotBlank() }.joinToString(" · "),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.sp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tag),
            )
        }
    }
}

/** The travelling phase of the wave, read where the wave is drawn. */
@Composable
private fun wavePhase(): () -> Float {
    val phase = rememberWavePhase()
    return { phase.value }
}
