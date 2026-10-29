package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YT design "Movo", Videoseite below the player: oversized title, creator row with a cobalt
 * "Folgen" and round Merken / Teilen buttons, the lavender "Im Video" chapter surface and a
 * discussion entry. "Als Nächstes" stays the classic related list below.
 */
@Composable
fun MovoWatchDetails(
    palette: YtPalette,
    imageLoader: ImageLoader,
    controller: YtWatchController,
    actions: YtWatchActions,
) {
    val streams = controller.streams ?: return
    val scope = rememberCoroutineScope()
    val channelId = streams.uploaderUrl?.toID()
    var subscribed by remember(channelId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(channelId) {
        subscribed = channelId?.let {
            withContext(Dispatchers.IO) { runCatching { SubscriptionHelper.isSubscribed(it) }.getOrNull() }
        }
    }
    var showDescription by remember(streams.title) { mutableStateOf(false) }
    LaunchedEffect(showDescription, streams.title) { actions.onShowDescription(showDescription) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = streams.title,
            color = palette.text,
            style = TextStyle(
                fontFamily = YtFonts.geist,
                fontWeight = FontWeight.Black,
                fontSize = 30.sp,
                lineHeight = 32.sp,
                letterSpacing = (-0.8).sp,
            ),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
        ) {
            AsyncImage(
                model = streams.uploaderAvatar,
                contentDescription = streams.uploader,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(palette.surface)
                    .clickable(onClick = actions.onOpenChannel),
            )
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = actions.onOpenChannel),
            ) {
                Text(
                    text = streams.uploader,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                )
                if (streams.uploaderSubscriberCount >= 0) {
                    Text(
                        text = stringResource(R.string.yt_movo_followers, streams.uploaderSubscriberCount.formatShort()),
                        color = palette.textSecondary,
                        maxLines = 1,
                        style = TextStyle(fontFamily = YtFonts.geist, fontSize = 13.sp),
                    )
                }
            }
            if (channelId != null) {
                val isSubscribed = subscribed == true
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isSubscribed) palette.raised else palette.accent)
                        .clickable {
                            val newState = !isSubscribed
                            subscribed = newState
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    if (newState) {
                                        SubscriptionHelper.subscribe(
                                            channelId, streams.uploader, streams.uploaderAvatar, streams.uploaderVerified
                                        )
                                    } else {
                                        SubscriptionHelper.unsubscribe(channelId)
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                ) {
                    Text(
                        text = stringResource(if (isSubscribed) R.string.yt_movo_following_state else R.string.yt_movo_follow),
                        color = if (isSubscribed) palette.text else palette.onAccent,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            RoundAction(palette, R.drawable.ic_bookmark_outlined, stringResource(R.string.yt_movo_save), actions.onSave)
            Spacer(Modifier.width(10.dp))
            RoundAction(palette, R.drawable.ic_share, stringResource(R.string.share), actions.onShare)
        }

        if (streams.chapters.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 22.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(palette.lavender)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.yt_movo_in_video),
                        color = palette.ink,
                        style = TextStyle(
                            fontFamily = YtFonts.geist,
                            fontWeight = FontWeight.Black,
                            fontSize = 28.sp,
                            letterSpacing = (-0.6).sp,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = pluralStringResource(R.plurals.yt_chapters_count, streams.chapters.size, streams.chapters.size),
                        color = palette.ink,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 14.sp),
                    )
                }
                streams.chapters.forEachIndexed { index, chapter ->
                    val active = index == controller.chapterIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) Color.White else Color.White.copy(alpha = 0.35f))
                            .clickable { actions.onSeek(chapter.start * 1000) }
                            .padding(8.dp),
                    ) {
                        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                            if (active) {
                                Icon(
                                    painterResource(R.drawable.ic_play),
                                    contentDescription = null,
                                    tint = palette.ink,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        AsyncImage(
                            model = chapter.image.ifBlank { streams.thumbnailUrl },
                            contentDescription = null,
                            imageLoader = imageLoader,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(96.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = chapter.title,
                                color = palette.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp),
                            )
                            Text(
                                text = DateUtils.formatElapsedTime(chapter.start),
                                color = palette.ink.copy(alpha = 0.6f),
                                style = TextStyle(fontFamily = YtFonts.geistMono, fontSize = 12.sp),
                            )
                        }
                    }
                }
            }
        }

        // Discussion entry and description toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, top = 24.dp)
                .fillMaxWidth()
                .clickable(onClick = actions.onComments),
        ) {
            Text(
                text = stringResource(R.string.yt_movo_discussion),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Black, fontSize = 24.sp),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.yt_watch_comments),
                color = palette.textSecondary,
                style = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp),
            )
            Icon(
                painterResource(R.drawable.ic_arrow_right),
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(18.dp),
            )
        }
        Box(
            modifier = Modifier
                .padding(start = 20.dp, top = 14.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(if (showDescription) palette.text else palette.surface)
                .clickable { showDescription = !showDescription }
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(
                text = stringResource(R.string.yt_watch_description),
                color = if (showDescription) palette.ink else palette.text,
                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            )
        }
    }
}

@Composable
private fun RoundAction(palette: YtPalette, @DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(palette.raised),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = palette.text, modifier = Modifier.size(20.dp))
        }
        Text(
            text = label,
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.sp),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
