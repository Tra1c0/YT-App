package com.github.libretube.ui.yt

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
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
import com.github.libretube.helpers.YtDesign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * YT: "Kiesel", home, after the dashboard of the inspiration image: a reminder card stacked on
 * a lavender one (continue watching), statistics of your week (new videos per day as bars, live
 * and Shorts), "Für dich" as large lavender and peach cards, more videos as white rows, and a
 * black panel with your channels at the bottom. Bars grow, numbers count, the stack fans out
 * when touched, the black panel rises.
 */

private val KieselCard: Color get() = ytDayNight(Color(0xFFF7F4F1), Color(0xFF2B2826))
private val KieselGrey: Color get() = ytDayNight(Color(0xFF7A7672), Color(0xFFABA59F))
private val KieselBeige: Color get() = ytDayNight(Color(0xFFE2DCD5), Color(0xFF4A4541))
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
    // the reminder stack: what you were watching, then the newest of your channels
    val reminders = remember(data.continueWatching, subs) {
        (data.continueWatching.orEmpty().filter { remaining(it) > 0 } + subs).filter { it.url != null }.distinctBy { it.url }.take(6)
    }
    val days = remember(now) {
        val format = SimpleDateFormat("EE", Locale.getDefault())
        List(7) { i -> format.format(Date(now - (6 - i) * DAY)) }
    }
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

    YtRefreshBox(
        palette = palette,
        isRefreshing = data.isLoading && data.forYou != null,
        onRefresh = actions.onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                if (reminders.isNotEmpty()) {
                    KieselReminder(palette, reminders, remaining, actions, Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp))
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
                        KieselBars(week, weekTotal, days)
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
                item { KieselSection(stringResource(R.string.yt_kiesel_for_you), actions.onShowSubscriptions, Modifier.padding(top = 24.dp), tune = true) }
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
                    KieselRow(palette, video, index, imageLoader, actions, ytItemMotion().ytEnter(enter, "row" + video.url, index))
                }
            }
            item {
                KieselChannelPanel(channels, imageLoader, actions, Modifier.padding(top = 22.dp))
            }
        }
    }
}

@Composable
private fun KieselSection(title: String, onAll: (() -> Unit)?, modifier: Modifier = Modifier, tune: Boolean = false) {
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
        if (tune) {
            // the Feed-Regler: a lavender capsule before "Alle ansehen", black while open
            val active = YtFeedUi.sheet
            val fill by animateColorAsState(if (active) KieselPanel else KieselLavender, ytStateSpec(), label = "kieselTune")
            YtTuneWord(
                text = stringResource(R.string.yt_feed_open_short),
                style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                color = KieselBlack,
                active = KieselCream,
                glyph = true,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(fill)
                    .padding(horizontal = 8.dp),
            )
        }
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
 * Continue watching and what is new, as a stack of cards: a peach reminder lying on a lavender
 * one. The stack fans out when it appears and further when pressed; swiping the front card
 * away (1:1, the finger's flick decides) brings the next card forward.
 */
@Composable
private fun KieselReminder(
    palette: YtPalette,
    videos: List<StreamItem>,
    remaining: (StreamItem) -> Long,
    actions: YtHomeActions,
    modifier: Modifier = Modifier,
) {
    val motion = LocalYtMotion.current
    val haptics = rememberYtHaptics()
    val scope = rememberCoroutineScope()
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
    var index by remember { mutableIntStateOf(0) }
    var width by remember { mutableIntStateOf(0) }
    val swipe = rememberYtSwipe()
    val front = videos[index % videos.size]
    val back = videos[(index + 1) % videos.size]
    val colors = listOf(KieselPeach, KieselLavender)
    val leaveSpec = ytFinite(spring<Float>(dampingRatio = 0.9f, stiffness = 320f))
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val queueLabel = stringResource(R.string.yt_home_action_queue)
    val hideLabel = stringResource(R.string.yt_home_action_hide)
    // the swipe has a meaning now: to the left the card is hidden, to the right it goes to the
    // end of the queue; either way the next card comes forward
    fun leave(way: Int, velocity: Float) {
        val card = front
        val from = coords?.takeIf { it.isAttached }?.boundsInWindow()?.translate(swipe.offset.value, 0f)
        scope.launch {
            if (way > 0) ytQueueCard(palette, card, from, velocity, haptics, motion, ytSwipeTexture(palette, queue = true))
            swipe.offset.animateTo(way * width * 1.3f, leaveSpec)
            // a hidden card leaves the stack by itself, the next one takes its place
            if (way < 0) ytHideVideo(card) else index++
            swipe.offset.snapTo(0f)
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .onSizeChanged { width = it.width }
            .onPlaced { coords = it }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(queueLabel) {
                        ytQueueVideo(front)
                        index++
                        true
                    },
                    CustomAccessibilityAction(hideLabel) {
                        ytHideVideo(front)
                        true
                    },
                )
            },
    ) {
        // the next card behind, turned a little; it straightens and comes forward with the swipe
        KieselReminderFace(
            back, remaining(back), colors[(index + 1) % 2],
            Modifier
                .padding(start = 10.dp, end = 8.dp, top = 12.dp)
                .fillMaxWidth()
                .height(78.dp)
                .graphicsLayer {
                    val p = if (width > 0) (abs(swipe.offset.value) / width).coerceIn(0f, 1f) else 0f
                    val f = (fan.value + 0.6f * lift) * (1f - p)
                    rotationZ = -4.5f * f
                    translationY = 4.dp.toPx() * f - 12.dp.toPx() * p
                    translationX = 6.dp.toPx() * f
                    val s = 0.96f + 0.04f * p
                    scaleX = s
                    scaleY = s
                    alpha = (0.4f + fan.value).coerceAtMost(1f)
                },
        )
        KieselReminderFace(
            front, remaining(front), colors[index % 2],
            Modifier
                .fillMaxWidth()
                .height(78.dp)
                .graphicsLayer {
                    translationX = swipe.offset.value
                    rotationZ = if (width > 0) 9f * swipe.offset.value / width else 0f
                    translationY = -6.dp.toPx() * lift - (1f - fan.value) * 16.dp.toPx()
                    alpha = (0.4f + fan.value).coerceAtMost(1f)
                }
                .ytSwipe(swipe, (width * 0.3f).coerceAtLeast(120f), direction = 0, haptics = haptics, enabled = videos.isNotEmpty(), settle = false) { way ->
                    leave(way, if (way > 0) 1600f else -1600f)
                }
                .clickable(interactionSource = press, indication = null) { actions.onOpenVideo(front) },
        )
    }
}

@Composable
private fun KieselReminderFace(video: StreamItem, left: Long, color: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(color)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (left > 0) stringResource(R.string.yt_kiesel_reminder)
                else stringResource(R.string.yt_kiesel_new_from, video.uploaderName.orEmpty()),
                color = KieselBlack,
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
                    color = KieselBlack,
                    style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp),
                )
            }
        }
        Text(
            text = video.title.orEmpty(),
            color = KieselBlack.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 12.5.sp),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * New videos of the week: the number counts up, the bars grow one after the other. A tap on a
 * bar lifts it and shows its day and count in a dark bubble; tapping again lets it down.
 */
@Composable
private fun KieselBars(week: IntArray, total: Int, days: List<String>) {
    val motion = LocalYtMotion.current
    val haptics = rememberYtHaptics()
    val growth = remember { List(7) { Animatable(if (motion) 0f else 1f) } }
    var picked by remember { mutableIntStateOf(-1) }
    val lifts = List(7) { i -> animateFloatAsState(if (picked == i) 1f else 0f, ytSpec(spring(dampingRatio = 0.5f, stiffness = 380f)), label = "bar$i") }
    val measurer = rememberTextMeasurer()
    val bubbleStyle = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = KieselCream)
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
                .height(70.dp)
                .pointerInput(Unit) {
                    detectTapGestures { at ->
                        val gap = size.width * 0.04f
                        val barWidth = (size.width - gap * 6) / 7
                        val i = (at.x / (barWidth + gap)).toInt().coerceIn(0, 6)
                        picked = if (picked == i) -1 else i
                        haptics.tick()
                    }
                },
        ) {
            val gap = size.width * 0.04f
            val barWidth = (size.width - gap * 6) / 7
            for (i in 0 until 7) {
                // an empty day still shows a stub, as in the reference
                val share = (week[i].toFloat() / max).coerceAtLeast(0.18f)
                val h = size.height * share * growth[i].value
                val l = lifts[i].value
                val raised = 6.dp.toPx() * l
                drawRoundRect(
                    colors[i],
                    topLeft = Offset(i * (barWidth + gap), size.height - h - raised),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth * 0.3f),
                )
                if (l > 0.01f) {
                    // the day and its count in a dark bubble above the lifted bar (inside it when tall)
                    val text = measurer.measure(days[i] + " " + week[i], bubbleStyle)
                    val w = text.size.width + 12.dp.toPx()
                    val bh = text.size.height + 6.dp.toPx()
                    val cx = (i * (barWidth + gap) + barWidth / 2).coerceIn(w / 2, size.width - w / 2)
                    var top = size.height - h - raised - bh - 4.dp.toPx()
                    if (top < 0f) top = size.height - h - raised + 6.dp.toPx()
                    scale(l, l, pivot = Offset(cx, top + bh)) {
                        drawRoundRect(KieselPanel, Offset(cx - w / 2, top), Size(w, bh), CornerRadius(bh / 2))
                        drawText(text, topLeft = Offset(cx - text.size.width / 2f, top + 3.dp.toPx()))
                    }
                }
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
            KieselBookmark(KieselBlack) { actions.onSave(video) }
        }
        Box(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(KieselInk.copy(alpha = 0.08f)),
        ) {
            YtLargeThumbnail(video, imageLoader, Modifier.fillMaxSize().ytSeen(video, YtDesign.KIESEL.palette ?: YtPalettes.Kiesel))
            ytDuration(video)?.let {
                Text(
                    text = it,
                    color = KieselCream,
                    style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 11.sp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(KieselBlack)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            text = video.title.orEmpty(),
            color = KieselBlack,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, lineHeight = 18.sp),
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = listOfNotNull(video.uploaderName, ytViews(video)).joinToString("  ·  "),
            color = KieselBlack.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.jakarta, fontSize = 11.5.sp),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** A white row: channel, title and channel name, bookmark. */
@Composable
private fun KieselRow(
    palette: YtPalette,
    video: StreamItem,
    index: Int,
    imageLoader: ImageLoader,
    actions: YtHomeActions,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 18.dp, vertical = 5.dp)
            .fillMaxWidth()
            // left hides, right queues: a lavender or peach capsule grows out of the gap
            .ytCardSwipe(palette, video, RoundedCornerShape(18.dp), testTag = "yt_card_$index")
            // held: the pebble lifts and drags onto the mini player; let go unmoved: quick actions
            .ytHomeCard(palette, video, RoundedCornerShape(18.dp), onRelease = { actions.onQuick(video) })
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
                .background(KieselBeige)
                .ytSeen(video, palette),
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
private fun KieselBookmark(tint: Color = KieselInk, onSave: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    Icon(
        painterResource(if (saved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_outlined),
        contentDescription = stringResource(R.string.save),
        tint = tint,
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
            .background(KieselPanel)
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
                    // a long press pauses the channel for a week (or ends its pause)
                    .ytPressable(RoundedCornerShape(18.dp), squish = true, onLongClick = { ytTogglePause(video) }) { actions.onOpenChannel(video) }
                    .background(KieselPanelCard)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                KieselAvatar(video.uploaderAvatar, imageLoader, 40.dp, KieselPanel)
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
                        .background(KieselPanel)
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
