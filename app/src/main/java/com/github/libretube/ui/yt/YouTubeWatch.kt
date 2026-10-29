package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Streams
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toID
import com.github.libretube.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of the video page that the player fragment updates. */
class YtWatchController {
    var videoId by mutableStateOf<String?>(null)
    var streams by mutableStateOf<Streams?>(null)
    var chapterIndex by mutableIntStateOf(-1)
}

/** Actions of the video page; most reuse the existing buttons of the classic layout. */
class YtWatchActions(
    val onSeek: (Long) -> Unit,
    val onSave: () -> Unit,
    val onShare: () -> Unit,
    val onDownload: () -> Unit,
    val onAudioOnly: () -> Unit,
    val onComments: () -> Unit,
    val onOpenChannel: () -> Unit,
    val onShowDescription: (Boolean) -> Unit,
    val currentPosition: () -> Long = { 0L },
)

private enum class WatchTab { CHAPTERS, COMMENTS, DESCRIPTION }

/**
 * YT design "YouTube, neu gedacht", view B (Videoseite) below the player: title and metadata,
 * creator row with "Abonnieren", compact actions, tabs Kapitel / Kommentare / Beschreibung and the
 * chapter list with the current chapter marked. "Als Nächstes" stays the classic list below.
 */
@Composable
fun YouTubeWatchDetails(
    palette: YtPalette,
    imageLoader: ImageLoader,
    controller: YtWatchController,
    actions: YtWatchActions,
) {
    val streams = controller.streams ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channelId = streams.uploaderUrl?.toID()

    var subscribed by androidx.compose.runtime.remember(channelId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(channelId) {
        subscribed = channelId?.let {
            withContext(Dispatchers.IO) { runCatching { SubscriptionHelper.isSubscribed(it) }.getOrNull() }
        }
    }
    val hasChapters = streams.chapters.isNotEmpty()
    var tab by androidx.compose.runtime.remember(streams.title) {
        mutableStateOf(if (hasChapters) WatchTab.CHAPTERS else WatchTab.DESCRIPTION)
    }
    LaunchedEffect(tab, streams.title) { actions.onShowDescription(tab == WatchTab.DESCRIPTION) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = streams.title,
            color = palette.text,
            style = TextStyle(
                fontFamily = YtFonts.geist,
                fontWeight = FontWeight.Bold,
                fontSize = 18.5.sp,
                lineHeight = 23.sp,
                letterSpacing = (-0.2).sp,
            ),
            modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 8.dp),
        )
        Text(
            text = TextUtils.formatViewsString(context, streams.views, streams.uploaded ?: 0L),
            color = palette.textSecondary,
            style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.5.sp),
            modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 3.dp),
        )

        // Creator row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 13.dp, end = 12.dp, top = 14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = actions.onOpenChannel),
            ) {
                AsyncImage(
                    model = streams.uploaderAvatar,
                    contentDescription = streams.uploader,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(palette.surface),
                )
                Spacer(Modifier.width(15.dp))
                Column {
                    Text(
                        text = streams.uploader,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 14.5.sp),
                    )
                    if (streams.uploaderSubscriberCount >= 0) {
                        Text(
                            text = stringResource(R.string.subscribers, streams.uploaderSubscriberCount.formatShort()),
                            color = palette.textSecondary,
                            style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.5.sp),
                        )
                    }
                }
            }
            if (channelId != null) {
                val isSubscribed = subscribed == true
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isSubscribed) palette.raised else palette.text)
                        .clickable {
                            val newState = !isSubscribed
                            subscribed = newState
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    if (newState) {
                                        SubscriptionHelper.subscribe(
                                            channelId,
                                            streams.uploader,
                                            streams.uploaderAvatar,
                                            streams.uploaderVerified
                                        )
                                    } else {
                                        SubscriptionHelper.unsubscribe(channelId)
                                    }
                                }
                            }
                        }
                        .height(32.dp)
                        .padding(horizontal = 22.dp),
                ) {
                    Text(
                        text = stringResource(if (isSubscribed) R.string.yt_watch_subscribed else R.string.yt_watch_subscribe),
                        color = if (isSubscribed) palette.text else palette.ink,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                    )
                }
            }
        }

        // Compact actions
        LazyRow(
            contentPadding = PaddingValues(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 18.dp),
        ) {
            item {
                ActionPill(
                    palette,
                    R.drawable.ic_like,
                    if (streams.likes > 0) streams.likes.formatShort() else stringResource(R.string.yt_watch_like),
                ) {}
            }
            item { ActionPill(palette, R.drawable.ic_playlist_add, stringResource(R.string.yt_watch_save), actions.onSave) }
            item { ActionPill(palette, R.drawable.ic_share, stringResource(R.string.share), actions.onShare) }
            if (!streams.isLive) {
                item { ActionPill(palette, R.drawable.ic_download, stringResource(R.string.yt_watch_download), actions.onDownload) }
            }
            item { ActionPill(palette, R.drawable.ic_headphones, stringResource(R.string.yt_watch_audio), actions.onAudioOnly) }
        }

        // Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            if (hasChapters) {
                WatchTabLabel(palette, stringResource(R.string.yt_watch_chapters), tab == WatchTab.CHAPTERS, Modifier.weight(1f)) { tab = WatchTab.CHAPTERS }
            }
            WatchTabLabel(palette, stringResource(R.string.yt_watch_description), tab == WatchTab.DESCRIPTION, Modifier.weight(1f)) { tab = WatchTab.DESCRIPTION }
            WatchTabLabel(palette, stringResource(R.string.yt_watch_comments), false, Modifier.weight(1f)) { actions.onComments() }
        }
        Box(
            Modifier
                .padding(horizontal = 13.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.raised),
        )

        if (tab == WatchTab.CHAPTERS && hasChapters) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 3.dp, end = 3.dp, top = 8.dp, bottom = 4.dp),
            ) {
                streams.chapters.forEachIndexed { index, chapter ->
                    val next = streams.chapters.getOrNull(index + 1)
                    val length = (next?.start ?: streams.duration) - chapter.start
                    ChapterRow(
                        palette = palette,
                        imageLoader = imageLoader,
                        chapter = chapter,
                        fallbackImage = streams.thumbnailUrl,
                        lengthSeconds = length,
                        active = index == controller.chapterIndex,
                        onClick = { actions.onSeek(chapter.start * 1000) },
                    )
                }
            }
        }
        if (tab == WatchTab.CHAPTERS || !hasChapters) {
            Text(
                text = stringResource(R.string.yt_watch_up_next),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 18.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun ActionPill(palette: YtPalette, @DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(palette.surface)
            .clickable(onClick = onClick)
            .height(36.dp)
            .padding(horizontal = 18.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = palette.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 13.sp),
        )
    }
}

@Composable
private fun WatchTabLabel(
    palette: YtPalette,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = if (active) palette.text else palette.textSecondary,
            style = TextStyle(
                fontFamily = YtFonts.geist,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            ),
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Box(
            Modifier
                .fillMaxWidth(0.81f)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (active) palette.accent else Color.Transparent),
        )
    }
}

@Composable
private fun ChapterRow(
    palette: YtPalette,
    imageLoader: ImageLoader,
    chapter: ChapterSegment,
    fallbackImage: String?,
    lengthSeconds: Long,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (active) Modifier.border(1.5.dp, palette.accent, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = if (active) 16.dp else 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
    ) {
        if (active) {
            Icon(
                painterResource(R.drawable.ic_pause),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(9.dp))
        }
        Box {
            AsyncImage(
                model = chapter.image.ifBlank { fallbackImage },
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(if (active) 76.dp else 111.dp)
                    .height(62.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        Spacer(Modifier.width(19.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = DateUtils.formatElapsedTime(chapter.start),
                color = palette.textSecondary,
                style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.5.sp),
            )
            Text(
                text = chapter.title,
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            )
        }
        if (lengthSeconds > 0) {
            Text(
                text = DateUtils.formatElapsedTime(lengthSeconds),
                color = palette.textSecondary,
                style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
