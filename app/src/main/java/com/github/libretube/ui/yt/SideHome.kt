package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.github.libretube.extensions.toID

const val SIDE_TAB_FOR_YOU = "FOR_YOU"
const val SIDE_TAB_PEOPLE = "PEOPLE"

/** What a post in the Side feed can do; the screen itself only shows. */
class SideFeedActions(
    val onOpenVideo: (StreamItem) -> Unit,
    val onOpenChannel: (StreamItem) -> Unit,
    val onBookmark: (StreamItem) -> Unit,
    val onShare: (StreamItem) -> Unit,
    val onToggleHeart: (StreamItem) -> Unit,
    val onPost: () -> Unit,
)

/**
 * YT design "Side", Start: videos as posts. Each post starts with the creator and context,
 * then the landscape video, the title and a calm row with heart, bookmark and an invitation
 * to the conversation. Matte graphite, apricot selection, 20dp gutters, 14dp media corners.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    forYou: List<StreamItem>?,
    people: List<StreamItem>?,
    tab: String,
    hearts: Set<String>,
    isLoading: Boolean,
    onSelectTab: (String) -> Unit,
    onRefresh: () -> Unit,
    actions: SideFeedActions,
) {
    val source = if (tab == SIDE_TAB_PEOPLE) people else forYou
    val posts = source.orEmpty()
        .filter { !it.isShort && !it.isUpcoming && it.url != null }
        .distinctBy { it.url }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        PullToRefreshBox(
            isRefreshing = isLoading && source != null,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.yt_side_headline),
                        color = palette.text,
                        style = TextStyle(
                            fontFamily = YtFonts.figtree,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 34.sp,
                            lineHeight = 38.sp,
                        ),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                    )
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
                    ) {
                        SideTab(palette, stringResource(R.string.yt_side_for_you), tab == SIDE_TAB_FOR_YOU) {
                            onSelectTab(SIDE_TAB_FOR_YOU)
                        }
                        SideTab(palette, stringResource(R.string.yt_side_people), tab == SIDE_TAB_PEOPLE) {
                            onSelectTab(SIDE_TAB_PEOPLE)
                        }
                    }
                    SideDivider(palette)
                }
                if (posts.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isLoading || source == null) CircularProgressIndicator(color = palette.accent)
                            else Text(
                                stringResource(
                                    if (tab == SIDE_TAB_PEOPLE) R.string.yt_side_people_empty else R.string.yt_home_empty
                                ),
                                color = palette.textSecondary,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                        }
                    }
                }
                items(posts, key = { it.url!! }) { post ->
                    SidePost(palette, imageLoader, post, post.url!!.toID() in hearts, actions)
                    SideDivider(palette)
                }
            }
        }
        // "Beitrag": separate from playback, above the navigation
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 16.dp)
                .clickable(onClick = actions.onPost),
        ) {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_yt_pen),
                    contentDescription = null,
                    tint = palette.onAccent,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(
                text = stringResource(R.string.yt_side_post),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 14.sp),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun SideTab(palette: YtPalette, label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) palette.onAccent else palette.textSecondary,
        style = TextStyle(fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 17.sp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) palette.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 10.dp),
    )
}

@Composable
fun SideDivider(palette: YtPalette, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.surface),
    )
}

@Composable
private fun SidePost(
    palette: YtPalette,
    imageLoader: ImageLoader,
    post: StreamItem,
    hearted: Boolean,
    actions: SideFeedActions,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable { actions.onOpenChannel(post) },
            ) {
                AsyncImage(
                    model = post.uploaderAvatar,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(palette.surface),
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        text = post.uploaderName.orEmpty(),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 17.sp),
                    )
                    Text(
                        text = listOfNotNull(
                            post.uploadedDate,
                            post.duration?.takeIf { it > 0 }?.let {
                                stringResource(R.string.yt_side_minutes, ((it + 30) / 60).coerceAtLeast(1))
                            },
                        ).joinToString(" · "),
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 14.sp),
                    )
                }
            }
            Box {
                Icon(
                    painterResource(R.drawable.ic_yt_dots),
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { menu = true }
                        .padding(8.dp)
                        .size(20.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.yt_side_open_channel)) },
                        onClick = { menu = false; actions.onOpenChannel(post) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        onClick = { menu = false; actions.onShare(post) },
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .ytPressable(RoundedCornerShape(14.dp)) { actions.onOpenVideo(post) }
                .background(palette.surface),
        ) {
            AsyncImage(
                model = post.thumbnail,
                contentDescription = null,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            val badge = if (post.isLive) stringResource(R.string.yt_live_badge)
            else post.duration?.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it) }
            badge?.let {
                Text(
                    text = it,
                    color = Color.White,
                    style = TextStyle(fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Text(
            text = post.title.orEmpty(),
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.figtree, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 27.sp),
            modifier = Modifier
                .padding(top = 12.dp)
                .clickable { actions.onOpenVideo(post) },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { actions.onToggleHeart(post) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            ) {
                Icon(
                    painterResource(if (hearted) R.drawable.ic_yt_heart_fill else R.drawable.ic_yt_heart),
                    contentDescription = stringResource(R.string.yt_side_heart),
                    tint = if (hearted) palette.accent else palette.text,
                    modifier = Modifier.size(24.dp),
                )
            }
            SideVerticalRule(palette)
            Icon(
                painterResource(R.drawable.ic_bookmark_outlined),
                contentDescription = stringResource(R.string.save),
                tint = palette.text,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { actions.onBookmark(post) }
                    .padding(6.dp)
                    .size(22.dp),
            )
            SideVerticalRule(palette)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { actions.onOpenVideo(post) }
                    .padding(vertical = 4.dp),
            ) {
                AsyncImage(
                    model = post.uploaderAvatar,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(palette.surface),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.yt_side_prompt),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.figtree, fontSize = 13.sp),
                    )
                    Text(
                        text = stringResource(R.string.yt_side_join),
                        color = palette.accent,
                        style = TextStyle(fontFamily = YtFonts.figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                    )
                }
                Icon(
                    painterResource(R.drawable.ic_yt_chevron_right),
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SideVerticalRule(palette: YtPalette) {
    Box(
        Modifier
            .padding(horizontal = 10.dp)
            .width(1.dp)
            .height(22.dp)
            .background(palette.raised),
    )
}
