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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
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
    headline: String = stringResource(R.string.yt_library_headline),
    subline: String = stringResource(R.string.yt_library_subline),
    onOpenSettings: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    account: com.github.libretube.ui.yt.account.YtAccountInfo? = null,
    onAccount: (() -> Unit)? = null,
) {
    val playlists = collections.filter { !it.isBookmark }
    val bookmarks = collections.filter { it.isBookmark }
    val enter = rememberYtEnterState()
    // the newer designs use their action chips for the quick access, the first four their rows
    val chips = palette.signature !in listOf(YtSignature.YOUTUBE, YtSignature.MOVO, YtSignature.SIDE)
    val editorial = palette.signature == YtSignature.EDITORIAL

    YtScreenSurface(palette, Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp + ytNavPadding()),
        ) {
            item { LibraryHeadline(palette, headline, subline) }
            // the YouTube account: one tap to sign in, or the signed-in account with its data
            if (onAccount != null) item { LibraryAccount(palette, account, onAccount) }
            item {
                if (chips) {
                    WatchActionRow(
                        palette,
                        buildList {
                            if (showHistory) add(WatchAction(R.drawable.ic_history, stringResource(R.string.watch_history), onOpenHistory))
                            add(WatchAction(R.drawable.ic_download, stringResource(R.string.downloads), onOpenDownloads))
                        },
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 10.dp),
                    ) {
                        if (showHistory) {
                            QuickAccess(palette, R.drawable.ic_history, stringResource(R.string.watch_history), onOpenHistory, Modifier.weight(1f))
                        }
                        QuickAccess(palette, R.drawable.ic_download, stringResource(R.string.downloads), onOpenDownloads, Modifier.weight(1f))
                    }
                }
            }
            continueWatching.firstOrNull()?.let { first ->
                item { LibrarySection(palette, stringResource(R.string.yt_library_continue), onOpenHistory) }
                item {
                    Box(Modifier.ytEnter(enter, "continue" + first.video.url, 0)) {
                        LibraryContinue(palette, imageLoader, first, progress(first), remainingLabel(first), onOpenVideo)
                    }
                }
                val more = continueWatching.drop(1)
                if (more.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 10.dp),
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
                    modifier = Modifier.padding(start = 15.dp, end = 12.dp, top = 20.dp, bottom = 10.dp),
                ) {
                    LibraryTitle(palette, stringResource(R.string.yt_library_collections), Modifier.weight(1f))
                    LibraryAdd(palette, onNewCollection)
                }
            }
            if (playlists.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.yt_library_no_collections),
                        color = palette.textSecondary,
                        style = TextStyle(fontFamily = palette.font, fontSize = 15.sp),
                        modifier = Modifier.padding(horizontal = 15.dp),
                    )
                }
            }
            if (editorial) {
                // Editorial lists its collections as the numbered rows of a table
                itemsIndexed(playlists, key = { _, c -> "c-" + c.id }) { index, collection ->
                    Box(Modifier.ytEnter(enter, "collections" + collection.id, index + 1)) {
                        LibraryCollectionRow(palette, imageLoader, collection, index) { onOpenCollection(collection) }
                    }
                }
            } else {
                // two cards per row in the colors and shapes of the design
                itemsIndexed(playlists.chunked(2)) { row, pair ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(if (palette.signature == YtSignature.POP) 12.dp else 8.dp),
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .height(150.dp)
                            .ytEnter(enter, "collections" + pair.first().id, row + 1),
                    ) {
                        pair.forEachIndexed { index, collection ->
                            LibraryCollection(
                                palette = palette,
                                imageLoader = imageLoader,
                                collection = collection,
                                index = row * 2 + index,
                                onClick = { onOpenCollection(collection) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            items(bookmarks, key = { "bookmark-" + it.id }) { bookmark ->
                Box(Modifier.ytEnter(enter, "bookmark" + bookmark.id, 2 + bookmarks.indexOf(bookmark))) {
                    BookmarkRow(palette, bookmark) { onOpenCollection(bookmark) }
                }
            }
            // settings, help and about sit here, in the chips of the design, besides the header menu
            item {
                LibraryTitle(palette, stringResource(R.string.yt_library_more), Modifier.padding(start = 15.dp, end = 15.dp, top = 26.dp, bottom = 10.dp))
                WatchActionRow(
                    palette,
                    listOf(
                        WatchAction(R.drawable.ic_settings, stringResource(R.string.settings), onOpenSettings),
                        WatchAction(R.drawable.ic_help, stringResource(R.string.help), onOpenHelp),
                        WatchAction(R.drawable.ic_info, stringResource(R.string.about), onOpenAbout),
                    ),
                )
            }
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
            .heightIn(min = 50.dp)
            .ytSurface(palette, ytCardShape(palette, 12))
            .clickable(onClick = onClick)
            .padding(start = 15.dp, end = 12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = palette.text, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = palette.font, fontSize = 15.sp),
            modifier = Modifier.weight(1f),
        )
        Icon(painterResource(R.drawable.ic_yt_chevron_right), contentDescription = null, tint = palette.text, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun LibrarySection(palette: YtPalette, title: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 15.dp, end = 15.dp, top = 22.dp, bottom = 10.dp)
            .clickable(onClick = onClick),
    ) {
        LibraryTitle(palette, title)
        Icon(
            painterResource(R.drawable.ic_yt_chevron_right),
            contentDescription = null,
            tint = if (palette.signature == YtSignature.SHAPES || palette.signature == YtSignature.FROST) Color.White else palette.text,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(15.dp),
        )
    }
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
    // the watched part fills up when the card appears, so the eye finds where you left off
    val motion = LocalYtMotion.current
    val fill = remember { Animatable(if (motion) 0f else fraction) }
    LaunchedEffect(fraction) {
        if (motion) fill.animateTo(fraction, tween(700, delayMillis = 150, easing = YtEaseOut)) else fill.snapTo(fraction)
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x66FFFFFF))
            .drawBehind {
                drawRect(palette.accent, size = size.copy(width = size.width * fill.value.coerceIn(0f, 1f)))
            },
    )
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
                .clip(ytCardShape(palette, 16)),
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
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun BookmarkRow(palette: YtPalette, bookmark: YtCollection, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 10.dp, end = 9.dp, top = 6.dp)
            .fillMaxWidth()
            .ytSurface(palette, ytCardShape(palette, 14))
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
    ) {
        Icon(painterResource(R.drawable.ic_yt_nav_bookmark), contentDescription = null, tint = palette.text, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = bookmark.name,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = palette.font, fontSize = 15.sp),
            )
            Text(
                text = pluralStringResource(R.plurals.yt_videos_count, bookmark.videoCount.toInt(), bookmark.videoCount.toInt()),
                color = palette.textSecondary,
                style = TextStyle(fontFamily = palette.font, fontSize = 12.5.sp),
            )
        }
        Icon(painterResource(R.drawable.ic_yt_chevron_right), contentDescription = null, tint = palette.text, modifier = Modifier.size(16.dp))
    }
}

/** The account card of the personal area: sign in with one tap, or who is signed in. */
@Composable
private fun LibraryAccount(palette: YtPalette, account: com.github.libretube.ui.yt.account.YtAccountInfo?, onClick: () -> Unit) {
    val shape = ytCardShape(palette, 16)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .ytPressable(shape, squish = palette.surfaceStyle == YtSurfaceStyle.CLAY, onClick = onClick)
            .ytSurface(palette, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        com.github.libretube.ui.yt.account.YtAccountAvatar(palette, account, 42)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = when {
                    account == null -> stringResource(R.string.yt_account_title)
                    account.name.isNotBlank() -> account.name
                    else -> stringResource(R.string.yt_account_row_title)
                },
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            )
            Text(
                text = if (account == null) stringResource(R.string.yt_account_pref_summary) else stringResource(R.string.yt_account_import),
                color = palette.textSecondary,
                maxLines = 2,
                style = TextStyle(fontFamily = palette.font, fontSize = 12.sp, lineHeight = 16.sp),
            )
        }
    }
}
