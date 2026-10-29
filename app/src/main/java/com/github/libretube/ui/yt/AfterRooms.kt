package com.github.libretube.ui.yt

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.formatShort

/**
 * YT design "After", Räume: current live streams as rooms. A short creator rail picks the
 * room, one large room cover shows it with label, viewer count and description, and
 * "Raum betreten" is the one main action. Below the cover, the next rooms are previewed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfterRoomsScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    rooms: List<StreamItem>?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onChangeRegion: () -> Unit,
    onEnterRoom: (StreamItem) -> Unit,
) {
    val live = rooms.orEmpty().filter { it.url != null && !it.isShort }.distinctBy { it.url }
    var selected by remember(rooms) { mutableIntStateOf(0) }
    val room = live.getOrNull(selected)

    PullToRefreshBox(
        isRefreshing = isLoading && rooms != null,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.yt_after_rooms_title),
                        color = palette.text,
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontFamily = YtFonts.geist, fontSize = 17.sp),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(vertical = 12.dp),
                    )
                    Icon(
                        painterResource(R.drawable.ic_region),
                        contentDescription = stringResource(R.string.region),
                        tint = palette.text,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clip(CircleShape)
                            .clickable(onClick = onChangeRegion)
                            .padding(12.dp)
                            .size(22.dp),
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.yt_after_rooms_headline),
                    color = palette.text,
                    style = TextStyle(
                        fontFamily = YtFonts.geist,
                        fontWeight = FontWeight.Black,
                        fontSize = 46.sp,
                        lineHeight = 44.sp,
                        letterSpacing = (-1.2).sp,
                    ),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp),
                )
            }
            if (live.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoading || rooms == null) CircularProgressIndicator(color = palette.accent)
                        else Text(stringResource(R.string.yt_after_rooms_empty), color = palette.textSecondary)
                    }
                }
                return@LazyColumn
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(live, key = { _, item -> "rail-" + item.url }) { index, creator ->
                        CreatorPortrait(palette, imageLoader, creator, index == selected) { selected = index }
                    }
                }
            }
            room?.let { current ->
                item {
                    RoomCover(palette, imageLoader, current, selected, onEnterRoom)
                }
                val next = (1..2).mapNotNull { live.getOrNull((selected + it) % live.size) }
                    .filter { it.url != current.url }
                    .distinctBy { it.url }
                if (next.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 20.dp)
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(palette.surface)
                                .padding(vertical = 6.dp),
                        ) {
                            next.forEach { other ->
                                NextRoomLine(palette, imageLoader, other) {
                                    selected = live.indexOf(other)
                                }
                            }
                        }
                    }
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .padding(top = 14.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.accent)
                            .clickable { onEnterRoom(current) }
                            .padding(vertical = 16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.yt_after_enter_room),
                            color = palette.onAccent,
                            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 18.sp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Icon(
                            painterResource(R.drawable.ic_arrow_right),
                            contentDescription = null,
                            tint = palette.onAccent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorPortrait(
    palette: YtPalette,
    imageLoader: ImageLoader,
    creator: StreamItem,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(84.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(84.dp, 92.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    if (active) BorderStroke(2.dp, palette.text) else BorderStroke(0.dp, Color.Transparent),
                    RoundedCornerShape(12.dp),
                )
                .background(palette.surface),
        ) {
            AsyncImage(
                model = creator.uploaderAvatar ?: creator.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (creator.isLive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(palette.accent),
                    )
                    Text(
                        text = stringResource(R.string.yt_live_badge),
                        color = palette.accent,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 10.sp),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        Text(
            text = creator.uploaderName.orEmpty(),
            color = if (active) palette.text else palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.sp),
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
    }
}

@Composable
private fun RoomCover(
    palette: YtPalette,
    imageLoader: ImageLoader,
    room: StreamItem,
    index: Int,
    onEnterRoom: (StreamItem) -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 14.dp)
            .fillMaxWidth()
            .height(470.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.surface)
            .clickable { onEnterRoom(room) },
    ) {
        AsyncImage(
            model = room.thumbnail,
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
                        0.35f to Color.Transparent,
                        0.62f to palette.background.copy(alpha = 0.55f),
                        1f to palette.background.copy(alpha = 0.96f),
                    )
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 22.dp, end = 22.dp, bottom = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.yt_after_room_label, index + 1),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.geist, fontSize = 15.sp, letterSpacing = 6.sp),
            )
            Text(
                text = room.title.orEmpty(),
                color = palette.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = YtFonts.geist,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    lineHeight = 33.sp,
                    letterSpacing = (-0.6).sp,
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                if (room.isLive) AfterLiveBadge(palette, Modifier.padding(end = 12.dp))
                room.views?.takeIf { it > 0 }?.let { viewers ->
                    Icon(
                        painterResource(R.drawable.ic_yt_users),
                        contentDescription = null,
                        tint = palette.text,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(R.string.yt_after_watching_now, viewers.formatShort()),
                        color = palette.text,
                        style = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 14.dp),
            ) {
                AsyncImage(
                    model = room.uploaderAvatar,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, palette.text, CircleShape)
                        .background(palette.raised),
                )
                Text(
                    text = room.shortDescription?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.yt_after_room_host, room.uploaderName.orEmpty()),
                    color = palette.text.copy(alpha = 0.86f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp, lineHeight = 18.sp),
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun NextRoomLine(
    palette: YtPalette,
    imageLoader: ImageLoader,
    room: StreamItem,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = room.uploaderAvatar ?: room.thumbnail,
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(palette.raised),
        )
        Text(
            text = room.uploaderName.orEmpty(),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
            modifier = Modifier
                .padding(start = 12.dp)
                .width(86.dp),
        )
        Text(
            text = room.title.orEmpty(),
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        room.views?.takeIf { it > 0 }?.let {
            Text(
                text = it.formatShort(),
                color = palette.textSecondary,
                style = TextStyle(fontFamily = YtFonts.geistMono, fontSize = 12.sp),
            )
        }
    }
}
