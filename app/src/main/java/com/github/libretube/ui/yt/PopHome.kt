package com.github.libretube.ui.yt

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

private val PopYellow = Color(0xFFFDE682)
private val PopPink = Color(0xFFF7B3CE)
private val PopPinkStrong = Color(0xFFE0558F)
private val PopLilac = Color(0xFFDFD6F7)
private val PopPill = Color(0xFFD5CDFA)
private val PopMetaOnYellow = Color(0xFFA38E64)
private val PopMeta = Color(0xFF8A7AA6)
private val PopShadow = Color(0x403B2054)

/**
 * Design "Pop", home: blob tabs, a yellow feature card with one large rounded corner, white
 * list cards and a Shorts row with a live card. Measured in the reference,
 * see docs/DESIGN-MASSE.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PopHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    data: YtHomeData,
    tab: String,
    onSelectTab: (String) -> Unit,
    actions: YtHomeActions,
) {
    val source = data.forTab(tab)
    val videos = (if (tab == YT_TAB_LIVE) source?.filter { it.url != null }?.distinctBy { it.url } else ytPlayable(source)).orEmpty()
    val shorts = data.shorts.orEmpty()
    val liveCard = data.live?.firstOrNull { it.url != null && it.isLive }
    val enter = rememberYtEnterState()

    YtRefreshBox(
        palette = palette,
        isRefreshing = data.isLoading && source != null,
        onRefresh = actions.onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp + ytNavPadding()),
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(start = 13.dp, top = 3.dp, bottom = 9.dp),
                ) {
                    PopTab(palette, stringResource(R.string.yt_tab_for_you), null, tab == YT_TAB_FOR_YOU, 99) { onSelectTab(YT_TAB_FOR_YOU) }
                    PopTab(palette, stringResource(R.string.yt_tab_subscriptions), R.drawable.ic_yt_nav_subs, tab == YT_TAB_SUBS, 78) { onSelectTab(YT_TAB_SUBS) }
                    PopTab(palette, stringResource(R.string.yt_tab_live), R.drawable.ic_yt_nav_live, tab == YT_TAB_LIVE, 74) { onSelectTab(YT_TAB_LIVE) }
                }
            }
            if (source == null || videos.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        if (source == null || data.isLoading) CircularProgressIndicator(color = palette.accent)
                        else Text(stringResource(R.string.yt_home_nothing), color = palette.textSecondary, modifier = Modifier.padding(32.dp))
                    }
                }
            }
            videos.firstOrNull()?.let { first ->
                item(key = "feature-" + first.url) { PopFeature(palette, imageLoader, first, actions, Modifier.ytEnter(enter, tab + first.url, 0)) }
            }
            items(videos.drop(1).take(1), key = { "list-" + it.url }) { PopListCard(palette, imageLoader, it, actions, Modifier.ytEnter(enter, tab + it.url, 1)) }
            if ((shorts.isNotEmpty() || liveCard != null) && tab != YT_TAB_LIVE) {
                item { PopShorts(palette, imageLoader, shorts, liveCard, actions) }
            }
            items(videos.drop(2), key = { "more-" + it.url }) {
                PopListCard(palette, imageLoader, it, actions, Modifier.ytEnter(enter, tab + it.url, videos.indexOf(it)))
            }
        }
    }
}

@Composable
private fun PopTab(palette: YtPalette, label: String, icon: Int?, active: Boolean, width: Int, onClick: () -> Unit) {
    val background by animateColorAsState(if (active) palette.accent else PopLilac, ytStateSpec(), label = "popTab")
    val content by animateColorAsState(if (active) Color.White else palette.text, ytStateSpec(), label = "popTabContent")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width.dp)
            .height(50.dp)
            .ytSelectBounce(active)
            .clip(BlobShape(0.62f, 0.5f, 0.56f, 0.64f))
            .background(background)
            .clickable(onClick = onClick),
    ) {
        if (icon == null) {
            Text(
                text = label,
                color = content,
                maxLines = 1,
                style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 14.5.sp),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
                Text(
                    text = label,
                    color = content,
                    maxLines = 1,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp),
                )
            }
        }
    }
}

@Composable
private fun PopFeature(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    actions: YtHomeActions,
    modifier: Modifier = Modifier,
) {
    val card = FeatureShape(smallFraction = 0.06f, largeFraction = 0.165f)
    Column(
        modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .shadow(6.dp, card, clip = false, ambientColor = PopShadow, spotColor = PopShadow)
            .clip(card)
            .background(PopYellow)
            .clickable { actions.onOpenVideo(video) }
            .padding(start = 8.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(165.dp)
                .clip(FeatureShape(smallFraction = 0.04f, largeFraction = 0.16f))
                .background(PopLilac),
        ) {
            YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize())
            (if (video.isLive) stringResource(R.string.yt_live_badge) else ytDuration(video))?.let {
                Text(
                    text = it,
                    color = Color.White,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (video.isLive) PopPinkStrong else Color(0xBF2B1A45))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) {
            Text(
                text = video.title.orEmpty(),
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 23.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
                modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(R.drawable.ic_yt_dots_vertical),
                contentDescription = null,
                tint = palette.text,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(22.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, top = 8.dp),
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(PopPink)
                    .clickable { actions.onOpenChannel(video) },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = video.uploaderAvatar,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    text = video.uploaderName.orEmpty(),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp),
                )
                Text(
                    text = ytMeta(video),
                    color = PopMetaOnYellow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp),
                )
            }
            // saving spins the cookie a fifth of a turn with a springy overshoot
            var turns by remember { mutableIntStateOf(0) }
            val rotation by animateFloatAsState(turns * 72f, ytSpec(spring(dampingRatio = 0.45f, stiffness = 260f)), label = "popStar")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .graphicsLayer { rotationZ = rotation }
                    .shadow(3.dp, CookieShape(9), clip = false, ambientColor = PopShadow, spotColor = PopShadow)
                    .clip(CookieShape(9))
                    .background(Color.White)
                    .clickable {
                        turns++
                        actions.onSave(video)
                    },
            ) {
                Icon(painterResource(R.drawable.ic_yt_star), contentDescription = null, tint = palette.text, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun PopListCard(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem,
    actions: YtHomeActions,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .padding(start = 14.dp, end = 14.dp, top = 12.dp)
            .fillMaxWidth()
            .height(99.dp)
            .shadow(4.dp, shape, clip = false, ambientColor = PopShadow, spotColor = PopShadow)
            .clip(shape)
            .background(palette.surface)
            .clickable { actions.onOpenVideo(video) }
            .padding(start = 8.dp, top = 7.5.dp, bottom = 7.5.dp, end = 6.dp),
    ) {
        Box(
            Modifier
                .width(180.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(PopLilac),
        ) {
            YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize())
            (if (video.isLive) stringResource(R.string.yt_live_badge) else ytDuration(video))?.let {
                Text(
                    text = it,
                    color = Color.White,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (video.isLive) PopPinkStrong else Color(0xBF2B1A45))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 8.dp),
        ) {
            Text(
                text = video.title.orEmpty(),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 13.5.sp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                AsyncImage(
                    model = video.uploaderAvatar,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(PopPill)
                        .clickable { actions.onOpenChannel(video) },
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = video.uploaderName.orEmpty(),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp),
                    )
                    Text(
                        text = ytMeta(video),
                        color = PopMeta,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp),
                    )
                }
                Icon(
                    painterResource(R.drawable.ic_yt_dots_vertical),
                    contentDescription = null,
                    tint = palette.text,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { actions.onSave(video) },
                )
            }
        }
    }
}

@Composable
private fun PopShorts(
    palette: YtPalette,
    imageLoader: ImageLoader,
    shorts: List<StreamItem>,
    live: StreamItem?,
    actions: YtHomeActions,
) {
    Column(Modifier.padding(top = 18.dp, bottom = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 15.dp, end = 17.dp, bottom = 10.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(50.dp, 42.dp)
                    .clip(BlobShape(0.6f, 0.52f, 0.58f, 0.62f))
                    .background(PopPink),
            ) {
                YtShortsGlyph(palette.text, filled = true, modifier = Modifier.size(26.dp))
            }
            Text(
                text = stringResource(R.string.yt_nav_shorts),
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 17.sp),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PopPill)
                    .clickable(onClick = actions.onShowShorts)
                    .padding(horizontal = 13.dp),
            ) {
                Text(
                    text = stringResource(R.string.yt_after_show_all),
                    color = palette.text,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                )
                Icon(
                    painterResource(R.drawable.ic_yt_chevron_right),
                    contentDescription = null,
                    tint = palette.text,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(12.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shorts, key = { "short-" + it.url }) { short -> PopShortCard(palette, imageLoader, short, actions) }
            live?.let { item(key = "live-" + it.url) { PopShortCard(palette, imageLoader, it, actions) } }
        }
    }
}

@Composable
private fun PopShortCard(palette: YtPalette, imageLoader: ImageLoader, video: StreamItem, actions: YtHomeActions) {
    Box(
        Modifier
            .width(148.dp)
            .height(158.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PopLilac)
            .clickable { actions.onOpenVideo(video) },
    ) {
        YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize())
        Icon(
            painterResource(R.drawable.ic_yt_dots_vertical),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(18.dp),
        )
        if (video.isLive) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC1D032E))))
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.live)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_yt_nav_live), contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.yt_live_badge),
                        color = Color.White,
                        style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 12.sp),
                    )
                }
                Text(
                    text = video.title.orEmpty(),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 13.sp, lineHeight = 16.sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            ytViews(video)?.let {
                Text(
                    text = it,
                    color = Color.White,
                    style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PopPinkStrong)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
    }
}
