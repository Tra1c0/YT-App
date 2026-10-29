package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem

/** Tabs of the Movo home screen. */
const val MOVO_TAB_DISCOVER = "discover"
const val MOVO_TAB_FOLLOWING = "following"

/**
 * YT design "Movo", Entdecken: "Was zieht dich rein?", tabs Entdecken / Folge ich and an
 * asymmetric mosaic. Each block of four videos is one tall cobalt card on the left, a pink and
 * a lime card on the right and one wide photographic card below. 26dp corners, 8dp gaps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovoHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    discover: List<StreamItem>?,
    following: List<StreamItem>?,
    tab: String,
    isLoading: Boolean,
    onSelectTab: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenVideo: (StreamItem) -> Unit,
) {
    val source = if (tab == MOVO_TAB_FOLLOWING) following else discover ?: following
    val videos = source.orEmpty().filter { !it.isShort && !it.isUpcoming && it.url != null }
    val blocks = videos.chunked(4)
    val opening = rememberYtOpening()
    val open: (StreamItem) -> Unit = { video ->
        opening.value = video.url
        onOpenVideo(video)
    }

    CompositionLocalProvider(LocalYtOpening provides opening.value) {
        PullToRefreshBox(
            isRefreshing = isLoading && source != null,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.yt_movo_headline),
                        color = palette.text,
                        style = TextStyle(
                            fontFamily = YtFonts.geist,
                            fontWeight = FontWeight.Black,
                            fontSize = 49.sp,
                            lineHeight = 45.sp,
                            letterSpacing = (-1.5).sp,
                        ),
                        modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 10.dp),
                    )
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        modifier = Modifier.padding(start = 3.dp, bottom = 1.dp),
                    ) {
                        MovoTab(palette, stringResource(R.string.yt_movo_discover), tab == MOVO_TAB_DISCOVER) {
                            onSelectTab(MOVO_TAB_DISCOVER)
                        }
                        MovoTab(palette, stringResource(R.string.yt_movo_following), tab == MOVO_TAB_FOLLOWING) {
                            onSelectTab(MOVO_TAB_FOLLOWING)
                        }
                    }
                }
                if (source == null) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isLoading) CircularProgressIndicator(color = palette.accent)
                            else Text(stringResource(R.string.yt_home_empty), color = palette.textSecondary)
                        }
                    }
                }
                items(blocks) { block ->
                    MovoBlock(palette, imageLoader, block, open)
                }
            }
        }
    }
}

@Composable
private fun MovoTab(palette: YtPalette, label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) palette.accent else palette.surface)
            .clickable(onClick = onClick)
            .height(32.dp)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) palette.onAccent else palette.text,
            style = TextStyle(
                fontFamily = YtFonts.geist,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
            ),
        )
    }
}

@Composable
private fun MovoBlock(
    palette: YtPalette,
    imageLoader: ImageLoader,
    block: List<StreamItem>,
    onOpenVideo: (StreamItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val tall = block.getOrNull(0)
        val upper = block.getOrNull(1)
        val lower = block.getOrNull(2)
        val wide = block.getOrNull(3)
        if (tall != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(325.dp),
            ) {
                MovoCard(
                    palette, imageLoader, tall, palette.accent, Color.White, titleSize = 25.sp,
                    uppercase = true, onOpenVideo = onOpenVideo,
                    modifier = Modifier
                        .weight(1.24f)
                        .fillMaxHeight(),
                )
                if (upper != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        MovoCard(
                            palette, imageLoader, upper, palette.pink, palette.ink, titleSize = 21.sp,
                            uppercase = false, onOpenVideo = onOpenVideo,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                        if (lower != null) {
                            MovoCard(
                                palette, imageLoader, lower, palette.lime, palette.ink, titleSize = 21.sp,
                                uppercase = false, onOpenVideo = onOpenVideo,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        if (wide != null) {
            MovoCard(
                palette, imageLoader, wide, palette.surface, Color.White, titleSize = 23.sp,
                uppercase = false, onOpenVideo = onOpenVideo,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(143.dp),
            )
        }
    }
}

/**
 * A video cover: the thumbnail fills the card and fades into the card color at the bottom,
 * where the big title, the creator, the duration and a white play button sit.
 */
@Composable
private fun MovoCard(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    color: Color,
    onColor: Color,
    titleSize: TextUnit,
    uppercase: Boolean,
    onOpenVideo: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .ytRecede(video.url)
            .ytPressable(RoundedCornerShape(16.dp)) { onOpenVideo(video) }
            .background(color),
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
                        0f to color.copy(alpha = 0.10f),
                        0.45f to color.copy(alpha = 0.35f),
                        0.72f to color.copy(alpha = 0.92f),
                        1f to color,
                    )
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 11.dp, end = 48.dp, bottom = 9.dp),
        ) {
            val title = video.title.orEmpty()
            Text(
                text = if (uppercase) title.uppercase() else title,
                color = onColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = YtFonts.geist,
                    fontWeight = FontWeight.Black,
                    fontSize = titleSize,
                    lineHeight = titleSize * 0.98f,
                    letterSpacing = (titleSize.value * -0.015f).sp,
                ),
            )
            Text(
                text = video.uploaderName.orEmpty(),
                color = onColor.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp),
                modifier = Modifier.padding(top = 2.dp),
            )
            video.duration?.takeIf { it > 0 }?.let {
                Text(
                    text = DateUtils.formatElapsedTime(it),
                    color = Color.White,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0x8C0E1030))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(if (titleSize > 22.sp) 36.dp else 32.dp)
                .clip(CircleShape)
                .background(Color.White),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = null,
                tint = Color(0xFF0B0B0D),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
