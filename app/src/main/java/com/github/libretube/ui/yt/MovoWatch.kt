package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.core.text.HtmlCompat
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.Comment
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.SceneMode
import com.github.libretube.ui.yt.scene.YtSceneChips
import com.github.libretube.ui.yt.scene.rememberYtSceneUi
import com.github.libretube.ui.yt.player.ytShareScrubSource
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
    // first comment for the discussion preview; YT: page 1 through the activity's comment state,
    // shared with the classic sheet, and with spoiler protection the first one that is no spoiler
    val scene = controller.comments?.scene
    val sceneUi = rememberYtSceneUi(scene)
    var page by remember(controller.videoId) { mutableStateOf<CommentsPage?>(null) }
    LaunchedEffect(controller.videoId) {
        val id = controller.videoId ?: return@LaunchedEffect
        page = runCatching {
            scene?.cache?.page(id, null)
                ?: withContext(Dispatchers.IO) { MediaServiceRepository.instance.getComments(id) }
        }.getOrNull()
    }
    LaunchedEffect(sceneUi.mode, controller.videoId) {
        if (sceneUi.mode == SceneMode.SCENE) scene?.autoScan()
    }
    val sceneList = remember(sceneUi.indexed, sceneUi.step, sceneUi.mode) {
        if (sceneUi.mode == SceneMode.SCENE && scene != null) scene.sceneList().map { it.comment } else emptyList()
    }
    val firstComment: Comment? = when (sceneUi.mode) {
        SceneMode.OFF -> page?.comments?.firstOrNull()
        SceneMode.SPOILER -> page?.comments?.firstOrNull { sceneUi.verdict(it).let { v -> !v.hidden && !v.folded } }
        SceneMode.SCENE -> sceneList.firstOrNull { sceneUi.verdict(it).let { v -> !v.hidden && !v.folded } }
    }
    val commentCount = page?.commentCount ?: 0L

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = ytTitle(streams.title),
            color = palette.text,
            style = TextStyle(
                fontFamily = YtFonts.geist,
                fontWeight = FontWeight.Black,
                fontSize = 36.sp,
                lineHeight = 38.sp,
                letterSpacing = (-0.7).sp,
            ),
            modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 14.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 15.dp, end = 12.dp, top = 12.dp),
        ) {
            AsyncImage(
                model = streams.uploaderAvatar,
                contentDescription = streams.uploader,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(palette.surface)
                    .clickable(onClick = actions.onOpenChannel),
            )
            Spacer(Modifier.width(16.dp))
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
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                )
                if (streams.uploaderSubscriberCount >= 0) {
                    Text(
                        text = stringResource(R.string.yt_movo_followers, streams.uploaderSubscriberCount.formatShort()),
                        color = palette.textSecondary,
                        maxLines = 1,
                        style = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp),
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
                        .height(30.dp)
                        .padding(horizontal = 18.dp),
                ) {
                    Text(
                        text = stringResource(if (isSubscribed) R.string.yt_movo_following_state else R.string.yt_movo_follow),
                        color = if (isSubscribed) palette.text else palette.onAccent,
                        style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            RoundAction(palette, R.drawable.ic_bookmark_outlined, stringResource(R.string.yt_movo_save), actions.onSave)
            Spacer(Modifier.width(16.dp))
            // YT: held and dragged onto the time bar, it shares from that second
            RoundAction(
                palette, R.drawable.ic_share, stringResource(R.string.share), actions.onShare,
                gesture = Modifier.ytShareScrubSource(actions.shareScrub),
            )
        }

        if (streams.chapters.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .padding(start = 9.dp, end = 9.dp, top = 18.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.lavender)
                    .padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 5.dp, end = 5.dp, top = 2.dp, bottom = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.yt_movo_in_video),
                        color = palette.ink,
                        style = TextStyle(
                            fontFamily = YtFonts.geist,
                            fontWeight = FontWeight.Black,
                            fontSize = 25.sp,
                            letterSpacing = (-0.5).sp,
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
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) Color.White else Color.White.copy(alpha = 0.35f))
                            .clickable { actions.onSeek(chapter.start * 1000) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
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
                                .width(75.dp)
                                .aspectRatio(2f)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = chapter.title,
                                color = palette.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp),
                            )
                            Text(
                                text = DateUtils.formatElapsedTime(chapter.start),
                                color = palette.ink.copy(alpha = 0.6f),
                                style = TextStyle(fontFamily = YtFonts.geistMono, fontSize = 11.sp),
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
                .padding(start = 20.dp, end = 14.dp, top = 20.dp)
                .fillMaxWidth()
                .clickable(onClick = actions.onComments),
        ) {
            Text(
                text = stringResource(R.string.yt_movo_discussion),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Black, fontSize = 18.5.sp),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.yt_watch_comments) +
                    if (commentCount > 0) "  " + commentCount.formatShort() else "",
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
        // YT: Alle / Diese Stelle / Spoilerschutz, as the classic sheet has them
        if (scene != null && !scene.isLive && page?.comments?.isNotEmpty() == true) {
            YtSceneChips(
                selected = sceneUi.mode,
                onSelect = scene::setMode,
                font = YtFonts.geist,
                active = palette.text,
                onActive = palette.ink,
                idle = palette.surface,
                onIdle = palette.text,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 15.dp),
            )
        }
        firstComment?.let { comment -> MovoCommentCard(palette, imageLoader, comment, actions.onComments) }
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
        if (showDescription) {
            WatchDescription(
                streams, actions.onLink, palette.text, palette.lime, R.font.font_geist_400, expanded = true,
                modifier = Modifier
                    .padding(start = 15.dp, end = 15.dp, top = 4.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(palette.surface)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun RoundAction(
    palette: YtPalette,
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    gesture: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.then(gesture).clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(palette.raised),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = palette.text, modifier = Modifier.size(20.dp))
        }
        Text(
            text = label,
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** One comment as in the reference: avatar, name, time, likes and the text on a dark card. */
@Composable
private fun MovoCommentCard(palette: YtPalette, imageLoader: ImageLoader, comment: Comment, onClick: () -> Unit) {
    val text = remember(comment.commentId) {
        HtmlCompat.fromHtml(comment.commentText.orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    }
    Row(
        modifier = Modifier
            .padding(start = 9.dp, end = 9.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF151518))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 9.dp),
    ) {
        AsyncImage(
            model = comment.thumbnail,
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(palette.raised),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.author.removePrefix("@"),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = comment.commentedTime,
                    color = palette.textSecondary,
                    maxLines = 1,
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp),
                    modifier = Modifier.padding(start = 10.dp),
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    painterResource(R.drawable.ic_yt_heart),
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = comment.likeCount.formatShort(),
                    color = palette.textSecondary,
                    style = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp),
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
            Text(
                text = text,
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.geist, fontSize = 15.sp, lineHeight = 20.sp),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
