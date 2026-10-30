package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * YT: "Kiesel", home, after the dashboard of the inspiration image: a reminder card stacked on
 * a lavender one (continue watching), statistics of your week (new videos per day as bars, live
 * and Shorts), "Für dich" as large lavender and peach cards, more videos as white rows, and a
 * black panel with your channels at the bottom. Bars grow, numbers count, the stack fans out
 * when touched, the black panel rises.
 */

private val KieselCard = Color(0xFFF7F4F1)
private val KieselGrey = Color(0xFF7A7672)
private val KieselBeige = Color(0xFFE2DCD5)
private const val DAY = 24L * 60 * 60 * 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KieselHomeScreen(
    palette: YtPalette,
    imageLoader: ImageLoader,
    data: YtHomeData,
    /** Milliseconds left of a video the history has a position for. */
    remaining: (StreamItem) -> Long,
    actions: YtHomeActions,
) {
    val subs = data.subscriptions.orEmpty()
    val now = remember(subs) { System.currentTimeMillis() }
    // new videos of your channels per day, the last seven days, today last
    val week = remember(subs) {
        IntArray(7) { i -> subs.count { it.uploaded > 0 && (now - it.uploaded) / DAY == (6 - i).toLong() } }
    }
    val weekTotal = week.sum()
    val live = data.live?.count { it.isLive } ?: 0
    val shorts = data.shorts?.size ?: 0
    val reminder = data.continueWatching?.firstOrNull { remaining(it) > 0 } ?: subs.firstOrNull()
    val forYou = data.forYou.orEmpty().take(10)
    val more = data.forYou.orEmpty().drop(10).take(12)
    val channels = remember(subs) {
        subs.filter { it.uploaderUrl != null }
            .groupBy { it.uploaderUrl!! }
            .entries
            .sortedByDescending { it.value.size }
            .take(5)
            .map { it.value.first() to it.value.count { v -> now - v.uploaded < 7 * DAY } }
    }
    val enter = rememberYtEnterState()

    PullToRefreshBox(
        isRefreshing = data.isLoading && data.forYou != null,
        onRefresh = actions.onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                reminder?.let {
                    KieselReminder(it, remaining(it), actions, Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp))
                }
            }
            item {
                KieselSection(stringResource(R.string.yt_kiesel_statistics), actions.onShowSubscriptions, Modifier.padding(top = 22.dp))
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1.28f),
                    ) {
                        KieselBars(week, weekTotal)
                        KieselStat(live, stringResource(R.string.yt_kiesel_live))
                    }
                    KieselLegend(
                        listOf(
                            Triple(KieselLavender, stringResource(R.string.yt_kiesel_videos), subs.size),
                            Triple(KieselBeige, stringResource(R.string.yt_kiesel_shorts), shorts),
                            Triple(KieselPeach, stringResource(R.string.yt_kiesel_streams), live),
                        ),
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
            if (data.forYou == null) {
                item {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KieselInk)
                    }
                }
            }
            if (forYou.isNotEmpty()) {
                item { KieselSection(stringResource(R.string.yt_kiesel_for_you), actions.onShowSubscriptions, Modifier.padding(top = 24.dp)) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(forYou, key = { _, v -> "card" + v.url }) { index, video ->
                            KieselCardItem(
                                video, imageLoader, if (index % 2 == 0) KieselLavender else KieselPeach, actions,
                                Modifier.ytEnter(enter, "card" + video.url, index),
                            )
                        }
                    }
                }
            }
            if (more.isNotEmpty()) {
                item { KieselSection(stringResource(R.string.yt_kiesel_more), null, Modifier.padding(top = 22.dp)) }
                itemsIndexed(more, key = { _, v -> "row" + v.url }) { index, video ->
                    KieselRow(video, imageLoader, actions, Modifier.ytEnter(enter, "row" + video.url, index))
                }
            }
            item {
                KieselChannelPanel(channels, imageLoader, actions, Modifier.padding(top = 22.dp))
            }
        }
    }
}

@Composable
private fun KieselSection(title: String, onAll: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
    ) {
        Text(
            text = title,
            color = KieselInk,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp),
            modifier = Modifier.weight(1f),
        )
        if (onAll != null) {
            Text(
                text = stringResource(R.string.yt_kiesel_view_all),
                color = KieselInk,
                style = TextStyle(
                    fontFamily = YtFonts.jakarta,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp,
                    textDecoration = TextDecoration.Underline,
                ),
                modifier = Modifier.clickable(onClick = onAll),
            )
        }
    }
}

/**
 * Continue watching as the peach reminder lying on a lavender card. The stack fans out when it
 * appears and further when pressed, the front card lifts.
 */
@Composable
private fun KieselReminder(video: StreamItem, left: Long, actions: YtHomeActions, modifier: Modifier = Modifier) {
    val motion = LocalYtMotion.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val fan = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (motion) {
            delay(120)
            fan.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 220f))
        }
    }
    val lift by animateFloatAsState(if (pressed && motion) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "lift")
    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        // the lavender card behind, turned a little
        Box(
            Modifier
                .padding(start = 10.dp, end = 8.dp, top = 12.dp)
                .fillMaxWidth()
                .height(78.dp)
                .graphicsLayer {
                    val f = fan.value + 0.6f * lift
                    rotationZ = -4.5f * f
                    translationY = 4.dp.toPx() * f
                    translationX = 6.dp.toPx() * f
                }
                .clip(RoundedCornerShape(18.dp))
                .background(KieselLavender),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .height(78.dp)
                .graphicsLayer {
                    translationY = -6.dp.toPx() * lift - (1f - fan.value) * 16.dp.toPx()
                    alpha = (0.4f + fan.value).coerceAtMost(1f)
                }
                .clip(RoundedCornerShape(18.dp))
                .clickable(interactionSource = press, indication = null) { actions.onOpenVideo(video) }
                .background(KieselPeach)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (left > 0) stringResource(R.string.yt_kiesel_reminder)
                    else stringResource(R.string.yt_kiesel_new_from, video.uploaderName.orEmpty()),
                    color = KieselInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp),
                    modifier = Modifier.weight(1f),
                )
                val right = if (left > 0) stringResource(R.string.yt_kiesel_left, DateUtils.formatElapsedTime(left / 1000))
                else ytDuration(video)
                right?.let {
                    Text(
                        text = it,
                        color = KieselInk,
                        style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp),
                    )
                }
            }
            Text(
                text = video.title.orEmpty(),
                color = KieselInk.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 12.5.sp),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** New videos of the week: the number counts up, the bars grow one after the other. */
@Composable
private fun KieselBars(week: IntArray, total: Int) {
    val motion = LocalYtMotion.current
    val growth = remember { List(7) { Animatable(if (motion) 0f else 1f) } }
    LaunchedEffect(Unit) {
        if (!motion) return@LaunchedEffect
        growth.forEachIndexed { i, a ->
            launch {
                delay(80L * i + 150)
                a.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 200f))
            }
        }
    }
    val max = (week.maxOrNull() ?: 0).coerceAtLeast(1)
    val colors = listOf(KieselLavender, KieselBeige, KieselLavender, KieselBeige, KieselLavender, KieselBeige, KieselPeach)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(KieselCard)
            .padding(14.dp),
    ) {
        KieselNumber(total, stringResource(R.string.yt_kiesel_new_videos))
        Canvas(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(70.dp),
        ) {
            val gap = size.width * 0.04f
            val barWidth = (size.width - gap * 6) / 7
            for (i in 0 until 7) {
                // an empty day still shows a stub, as in the reference
                val share = (week[i].toFloat() / max).coerceAtLeast(0.18f)
                val h = size.height * share * growth[i].value
                drawRoundRect(
                    colors[i],
                    topLeft = Offset(i * (barWidth + gap), size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth * 0.3f),
                )
            }
        }
    }
}

@Composable
private fun KieselStat(value: Int, label: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(KieselCard)
            .padding(14.dp),
    ) {
        KieselNumber(value, label)
    }
}

@Composable
private fun KieselNumber(value: Int, label: String) {
    Text(
        text = ytCountUp(value).toString(),
        color = KieselInk,
        style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp),
    )
    Text(
        text = label,
        color = KieselGrey,
        style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 11.5.sp),
    )
}

/** Colored squares with a name and a count, separated by thin lines. */
@Composable
private fun KieselLegend(rows: List<Triple<Color, String, Int>>, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(KieselCard)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        rows.forEachIndexed { i, (color, label, count) ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(KieselBeige))
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 8.dp)) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(18.dp, 13.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color),
                )
                Column(Modifier.padding(start = 10.dp)) {
                    Text(label, color = KieselInk, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                    Text(ytCountUp(count).toString(), color = KieselGrey, style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 11.5.sp))
                }
            }
        }
    }
}

/** A large colored card: channel and bookmark on top, the picture, then title and channel. */
@Composable
private fun KieselCardItem(video: StreamItem, imageLoader: ImageLoader, color: Color, actions: YtHomeActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(212.dp)
            .ytPressable(RoundedCornerShape(22.dp), squish = true) { actions.onOpenVideo(video) }
            .background(color)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KieselAvatar(video.uploaderAvatar, imageLoader, 34.dp, Color.White)
            Spacer(Modifier.weight(1f))
            KieselBookmark { actions.onSave(video) }
        }
        Box(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(KieselInk.copy(alpha = 0.08f)),
        ) {
            YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize())
            ytDuration(video)?.let {
                Text(
                    text = it,
                    color = KieselCream,
                    style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 11.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(KieselInk)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            text = video.title.orEmpty(),
            color = KieselInk,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, lineHeight = 18.sp),
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = listOfNotNull(video.uploaderName, ytViews(video)).joinToString("  ·  "),
            color = KieselInk.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 11.5.sp),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** A white row: channel, title and channel name, bookmark. */
@Composable
private fun KieselRow(video: StreamItem, imageLoader: ImageLoader, actions: YtHomeActions, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 18.dp, vertical = 5.dp)
            .fillMaxWidth()
            .ytPressable(RoundedCornerShape(18.dp), squish = true) { actions.onOpenVideo(video) }
            .background(KieselCard)
            .padding(10.dp),
    ) {
        AsyncImage(
            model = video.thumbnail,
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(84.dp, 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(KieselBeige),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp)) {
            Text(
                text = video.title.orEmpty(),
                color = KieselInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.5.sp),
            )
            Text(
                text = listOfNotNull(video.uploaderName, ytDuration(video)).joinToString("  ·  "),
                color = KieselGrey,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 11.5.sp),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        KieselBookmark { actions.onSave(video) }
    }
}

/** The bookmark: pops and fills when tapped, then opens "add to playlist". */
@Composable
private fun KieselBookmark(onSave: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    Icon(
        painterResource(if (saved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outlined),
        contentDescription = stringResource(R.string.save),
        tint = KieselInk,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable {
                saved = true
                onSave()
            }
            .padding(7.dp)
            .ytJelly(saved, amount = 0.2f),
    )
}

@Composable
private fun KieselAvatar(url: String?, imageLoader: ImageLoader, size: Dp, background: Color) {
    AsyncImage(
        model = url,
        contentDescription = null,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
    )
}

/**
 * The black panel at the bottom with your channels as cream cards and a black "Öffnen" pill,
 * as the link cards of the reference. It rises when it comes into view.
 */
@Composable
private fun KieselChannelPanel(
    channels: List<Pair<StreamItem, Int>>,
    imageLoader: ImageLoader,
    actions: YtHomeActions,
    modifier: Modifier = Modifier,
) {
    val motion = LocalYtMotion.current
    val rise = remember { Animatable(if (motion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (motion) rise.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 180f))
    }
    val enter = rememberYtEnterState()
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = rise.value * 90.dp.toPx() }
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(KieselInk)
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 22.dp + ytNavPadding()),
    ) {
        Text(
            text = stringResource(R.string.yt_kiesel_channels),
            color = KieselCream,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp),
            modifier = Modifier.padding(start = 6.dp, bottom = 12.dp),
        )
        channels.forEachIndexed { index, (video, count) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .ytEnter(enter, video.uploaderUrl ?: index, index + 1)
                    .padding(bottom = 10.dp)
                    .fillMaxWidth()
                    .ytPressable(RoundedCornerShape(18.dp), squish = true) { actions.onOpenChannel(video) }
                    .background(KieselCream)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                KieselAvatar(video.uploaderAvatar, imageLoader, 40.dp, KieselInk)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = video.uploaderName.orEmpty(),
                        color = KieselInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp),
                    )
                    Text(
                        text = stringResource(R.string.yt_kiesel_channel_new, count.toString()),
                        color = KieselGrey,
                        style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 11.5.sp),
                    )
                }
                Text(
                    text = stringResource(R.string.yt_kiesel_open),
                    color = KieselCream,
                    style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(KieselInk)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        if (channels.isEmpty()) {
            Text(
                text = stringResource(R.string.yt_home_nothing),
                color = KieselCream.copy(alpha = 0.7f),
                style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 13.sp),
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
    }
}
