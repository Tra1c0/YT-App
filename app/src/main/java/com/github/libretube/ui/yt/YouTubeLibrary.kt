package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
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

/** A video the user started, with the playback position in milliseconds. */
data class YtContinueItem(val video: StreamItem, val positionMillis: Long)

/** A local playlist or a bookmarked one, with up to three preview thumbnails. */
data class YtCollection(
    val id: String,
    val name: String,
    val videoCount: Long,
    val previews: List<String>,
    val isBookmark: Boolean,
)

/**
 * YT design "YouTube, neu gedacht", view C (Persönlicher Bereich): "Dein YouTube.", quick access
 * to history and downloads, "Weiterschauen" with progress, colored collection cards and
 * bookmarked playlists as quiet rows.
 */
@Composable
fun YouTubeLibraryScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    continueWatching: List<YtContinueItem>,
    collections: List<YtCollection>,
    showHistory: Boolean,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenVideo: (YtContinueItem) -> Unit,
    onOpenCollection: (YtCollection) -> Unit,
    onNewCollection: () -> Unit,
) {
    val playlists = collections.filter { !it.isBookmark }
    val bookmarks = collections.filter { it.isBookmark }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)) {
                Text(
                    text = stringResource(R.string.yt_library_headline),
                    color = palette.text,
                    style = TextStyle(
                        fontFamily = YtFonts.geist,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 34.sp,
                        lineHeight = 36.sp,
                        letterSpacing = (-0.6).sp,
                    ),
                )
                Text(
                    text = stringResource(R.string.yt_library_subline),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 17.sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                if (showHistory) {
                    QuickAccess(palette, R.drawable.ic_history, stringResource(R.string.watch_history), onOpenHistory, Modifier.weight(1f))
                }
                QuickAccess(palette, R.drawable.ic_download, stringResource(R.string.downloads), onOpenDownloads, Modifier.weight(1f))
            }
        }
        continueWatching.firstOrNull()?.let { first ->
            item { LibrarySection(palette, stringResource(R.string.yt_library_continue)) }
            item { ContinueCard(palette, imageLoader, first, onOpenVideo) }
            val more = continueWatching.drop(1)
            if (more.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        items(more, key = { it.video.url.orEmpty() }) { item ->
                            SmallContinueCard(palette, imageLoader, item, onOpenVideo)
                        }
                    }
                }
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.yt_library_collections),
                    color = palette.text,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 21.sp),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(palette.raised)
                        .clickable(onClick = onNewCollection),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.createPlaylist),
                        tint = palette.text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.yt_library_no_collections),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 15.sp),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        // Two equally sized cards per row, alternating lime and lavender as in the brief.
        items(playlists.chunked(2)) { pair ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .height(176.dp),
            ) {
                pair.forEachIndexed { index, collection ->
                    CollectionCard(
                        palette = palette,
                        imageLoader = imageLoader,
                        collection = collection,
                        color = if (index == 0) palette.lime else palette.lavender,
                        onClick = { onOpenCollection(collection) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        items(bookmarks, key = { "bookmark-" + it.id }) { bookmark ->
            BookmarkRow(palette, bookmark) { onOpenCollection(bookmark) }
        }
    }
}

@Composable
private fun QuickAccess(
    palette: YtPalette,
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = palette.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 16.sp),
            modifier = Modifier.weight(1f),
        )
        Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LibrarySection(palette: YtPalette, title: String) {
    Text(
        text = title,
        color = palette.text,
        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 21.sp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 12.dp),
    )
}

private fun remainingLabel(item: YtContinueItem): String? {
    val durationSeconds = item.video.duration?.takeIf { it > 0 } ?: return null
    val remaining = (durationSeconds - item.positionMillis / 1000).coerceAtLeast(0)
    return DateUtils.formatElapsedTime(remaining)
}

private fun progress(item: YtContinueItem): Float {
    val durationMillis = (item.video.duration ?: 0L) * 1000
    if (durationMillis <= 0) return 0f
    return (item.positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
}

@Composable
private fun ProgressBar(palette: YtPalette, fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color(0x66FFFFFF)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(palette.accent),
        )
    }
}

@Composable
private fun ContinueCard(
    palette: YtPalette,
    imageLoader: ImageLoader,
    item: YtContinueItem,
    onOpen: (YtContinueItem) -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .clickable { onOpen(item) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp)),
        ) {
            AsyncImage(
                model = item.video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xB3000000)),
            ) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            remainingLabel(item)?.let {
                Text(
                    text = stringResource(R.string.yt_library_remaining, it),
                    color = Color.White,
                    style = TextStyle(fontFamily = YtFonts.geistMono, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(start = 10.dp, end = 10.dp, bottom = 14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            ProgressBar(palette, progress(item), Modifier.align(Alignment.BottomStart))
        }
        Text(
            text = item.video.title.orEmpty(),
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = item.video.uploaderName.orEmpty(),
            color = palette.textSecondary,
            style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun SmallContinueCard(
    palette: YtPalette,
    imageLoader: ImageLoader,
    item: YtContinueItem,
    onOpen: (YtContinueItem) -> Unit,
) {
    Column(
        Modifier
            .width(200.dp)
            .clickable { onOpen(item) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            AsyncImage(
                model = item.video.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            ProgressBar(palette, progress(item), Modifier.align(Alignment.BottomStart))
        }
        Text(
            text = item.video.title.orEmpty(),
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun CollectionCard(
    palette: YtPalette,
    imageLoader: ImageLoader,
    collection: YtCollection,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = collection.name,
                color = palette.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = YtFonts.geist,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                    lineHeight = 26.sp,
                    letterSpacing = (-0.4).sp,
                ),
                modifier = Modifier.weight(1f),
            )
            Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, tint = palette.ink, modifier = Modifier.size(20.dp))
        }
        Text(
            text = pluralStringResource(R.plurals.yt_videos_count, collection.videoCount.toInt(), collection.videoCount.toInt()),
            color = palette.ink,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 14.sp),
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            collection.previews.take(3).forEach { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
            repeat((3 - collection.previews.size).coerceAtLeast(0)) {
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x1F000000)),
                )
            }
        }
    }
}

@Composable
private fun BookmarkRow(palette: YtPalette, bookmark: YtCollection, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(painterResource(R.drawable.ic_bookmark_outlined), contentDescription = null, tint = palette.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = bookmark.name,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 16.sp),
            )
            Text(
                text = pluralStringResource(R.plurals.yt_videos_count, bookmark.videoCount.toInt(), bookmark.videoCount.toInt()),
                color = palette.textSecondary,
                style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
            )
        }
        Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(18.dp))
    }
}
