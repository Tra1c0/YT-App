package com.github.libretube.ui.yt

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtChannelPause
import com.github.libretube.ui.fragments.TrendsFragment
import com.github.libretube.ui.yt.news.YtNewsChannels
import com.github.libretube.ui.yt.feed.YtChannelRule
import com.github.libretube.ui.yt.feed.YtFeed
import com.github.libretube.ui.yt.feed.YtFeedInput
import com.github.libretube.ui.yt.feed.YtFeedOrder
import com.github.libretube.ui.yt.feed.YtFeedRules
import com.github.libretube.ui.yt.feed.YtFeedSource
import com.github.libretube.ui.yt.feed.YtIntent
import com.github.libretube.ui.yt.feed.YtReason
import com.github.libretube.ui.yt.feed.YtReasonKind
import com.github.libretube.ui.yt.feed.YtTopic
import com.github.libretube.ui.yt.feed.YtFeedTopic
import com.github.libretube.ui.yt.feed.YtFeedTopics
import com.github.libretube.ui.yt.feed.YtTopicExpiry
import com.github.libretube.ui.yt.feed.YtTopicSession
import com.github.libretube.ui.yt.feed.YtWatchedMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Words for the engine's values

@Composable
fun ytSourceName(source: YtFeedSource): String = stringResource(
    when (source) {
        YtFeedSource.SUBSCRIPTIONS -> R.string.yt_feed_source_subscriptions
        YtFeedSource.MUSIC -> R.string.yt_feed_source_music
        YtFeedSource.PODCASTS -> R.string.yt_feed_source_podcasts
        YtFeedSource.TRAILERS -> R.string.yt_feed_source_trailers
        YtFeedSource.GAMING -> R.string.yt_feed_source_gaming
        YtFeedSource.LIVE -> R.string.yt_feed_source_live
        YtFeedSource.TOPICS -> R.string.yt_feed_source_topics
        YtFeedSource.RELATED -> R.string.yt_feed_source_related
        YtFeedSource.CONTINUE -> R.string.yt_feed_source_continue
    },
)

/** A reason in words ("Warum sehe ich das?"). */
@Composable
fun ytReasonText(reason: YtReason): String {
    val channel = reason.detail?.takeIf { it.isNotBlank() } ?: stringResource(R.string.yt_feed_this_channel)
    return when (reason.kind) {
        YtReasonKind.SOURCE -> {
            val source = YtFeedSource.entries.firstOrNull { it.name == reason.detail }
            stringResource(R.string.yt_feed_reason_source, source?.let { ytSourceName(it) } ?: reason.detail.orEmpty())
        }
        YtReasonKind.TOPIC -> stringResource(R.string.yt_feed_reason_topic, reason.detail.orEmpty())
        YtReasonKind.RELATED -> if (reason.detail.isNullOrBlank()) {
            stringResource(R.string.yt_feed_reason_related_any)
        } else {
            stringResource(R.string.yt_feed_reason_related, reason.detail)
        }
        YtReasonKind.CHOSEN_CHART -> {
            val source = YtFeedSource.entries.firstOrNull { it.name == reason.detail }
            stringResource(R.string.yt_feed_reason_chosen_chart, source?.let { ytSourceName(it) } ?: reason.detail.orEmpty())
        }
        YtReasonKind.BOOSTED -> stringResource(R.string.yt_feed_reason_boosted, channel)
        YtReasonKind.LESS -> stringResource(R.string.yt_feed_reason_less, channel)
        YtReasonKind.WORD -> stringResource(R.string.yt_feed_reason_word, reason.detail.orEmpty())
        YtReasonKind.FRESH -> stringResource(R.string.yt_feed_reason_fresh)
        YtReasonKind.POPULAR -> stringResource(R.string.yt_feed_reason_popular)
        YtReasonKind.OFTEN_WATCHED -> stringResource(R.string.yt_feed_reason_often, channel)
        YtReasonKind.HEARTED -> stringResource(R.string.yt_feed_reason_hearted)
        YtReasonKind.LEARN -> stringResource(R.string.yt_feed_reason_learn)
        YtReasonKind.MUSIC -> stringResource(R.string.yt_feed_reason_music)
        YtReasonKind.STARTED -> stringResource(R.string.yt_feed_reason_started)
        YtReasonKind.WATCHED_LAST -> stringResource(R.string.yt_feed_reason_watched_last)
        YtReasonKind.SPACED -> stringResource(R.string.yt_feed_reason_spaced, channel)
        YtReasonKind.NEW_ON_TOP -> stringResource(R.string.yt_feed_reason_new_on_top)
        YtReasonKind.FOCUS -> stringResource(R.string.yt_feed_reason_focus, reason.detail.orEmpty(), reason.extra.orEmpty())
    }
}

/** The fill a sheet stands on: the surface, made solid where the design's surface is see-through. */
internal fun ytSheetFill(palette: YtPalette): Color =
    if (palette.surface.alpha < 0.99f) palette.surface.compositeOver(palette.background) else palette.surface

// ---------------------------------------------------------------------------------------------
// "Warum sehe ich das?"

/**
 * Why [video] is on the home, in words, with "Mehr von …" and "Weniger von …" for its channel.
 * Shown in the quick sheet and in [YtWhyCard].
 */
@Composable
fun YtWhyList(palette: YtPalette, video: StreamItem, modifier: Modifier = Modifier) {
    val ink = ytInk(palette)
    val reasons by YtFeed.reasons.collectAsState()
    val profile by YtFeed.profile.collectAsState()
    val haptics = rememberYtHaptics()
    val id = remember(video) { video.url?.toID() }
    val list = id?.let { reasons[it] }
    val key = remember(video) { YtFeed.channelKey(video) }
    val rule = key?.let { profile.personal.channels[it] }
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (list.isNullOrEmpty()) {
            Text(
                text = stringResource(R.string.yt_feed_why_none),
                color = ink.textSecondary,
                style = TextStyle(fontFamily = palette.font, fontSize = 13.sp),
            )
        } else {
            list.distinct().forEach { reason ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
                    Box(
                        Modifier
                            .padding(top = 7.dp, end = 10.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(palette.accent),
                    )
                    Text(
                        text = ytReasonText(reason),
                        color = ink.text,
                        style = TextStyle(fontFamily = palette.font, fontSize = 13.5.sp, lineHeight = 18.sp),
                    )
                }
            }
        }
        if (key != null) {
            val name = video.uploaderName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.yt_feed_this_channel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                FeedChip(palette, stringResource(R.string.yt_feed_more_of, name), rule == YtChannelRule.BOOST, "yt_why_more") {
                    haptics.tick()
                    YtFeed.setChannel(video, if (rule == YtChannelRule.BOOST) null else YtChannelRule.BOOST)
                }
                FeedChip(palette, stringResource(R.string.yt_feed_less_of, name), rule == YtChannelRule.LESS, "yt_why_less") {
                    haptics.tick()
                    YtFeed.setChannel(video, if (rule == YtChannelRule.LESS) null else YtChannelRule.LESS)
                }
            }
        }
    }
}

/** "Warum sehe ich das?" as a small sheet of its own, for menus without a quick sheet (Side). */
@Composable
internal fun YtWhyCard(palette: YtPalette, video: StreamItem?, onDismiss: () -> Unit) {
    val shown = remember { arrayOfNulls<StreamItem>(1) }
    video?.let { shown[0] = it }
    YtBackHandler(video != null, onDismiss)
    AnimatedVisibility(visible = video != null, enter = ytEnterTransition(fadeIn(tween(160))), exit = ytExitTransition(fadeOut(tween(160)))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x80000000))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = video != null,
            enter = ytEnterTransition(slideInVertically(spring(dampingRatio = 0.8f, stiffness = 320f)) { it }),
            exit = ytExitTransition(slideOutVertically(tween(200, easing = YtEaseOut)) { it }),
        ) {
            val item = shown[0] ?: return@AnimatedVisibility
            val shape = if (palette.surfaceStyle == YtSurfaceStyle.EDITORIAL) RectangleShape else RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
            val back = remember(palette) { ytFillSheetBack(palette) }
            val ink = remember(palette, back) { YtInk.on(palette, back) }
            ProvideYtInk(palette, back) {
            Column(
                Modifier
                    .testTag("yt_why_card")
                    .fillMaxWidth()
                    .clip(shape)
                    .background(ytSheetFill(palette))
                    .clickable(interactionSource = null, indication = null) {}
                    .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 22.dp + ytNavPadding()),
            ) {
                Text(
                    text = stringResource(R.string.yt_feed_why),
                    color = ink.text,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 17.sp),
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = item.ytTitle,
                    color = ink.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = palette.font, fontSize = 13.sp),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                YtWhyList(palette, item)
            }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The Feed-Regler

/**
 * YT: "Feed-Regler", the detailed controls of the home feed for the current intent, in a sheet
 * that springs up from the bottom. Dragged by its head it follows the finger (rubber band
 * upwards) and a flick or a low release closes it. Every change ranks the live preview strip at
 * the top again at once; the sheet says plainly that nothing of it reaches a server.
 */
@Composable
internal fun YtFeedSheet(palette: YtPalette, visible: Boolean, preview: () -> YtFeedInput, onClose: () -> Unit) {
    YtBackHandler(visible, onClose)
    AnimatedVisibility(visible = visible, enter = ytEnterTransition(fadeIn(tween(180))), exit = ytExitTransition(fadeOut(tween(200)))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x8C000000))
                .clickable(interactionSource = null, indication = null, onClick = onClose),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = ytEnterTransition(slideInVertically(spring(dampingRatio = 0.82f, stiffness = 260f)) { it }),
            exit = ytExitTransition(slideOutVertically(tween(240, easing = YtEaseOut)) { it }),
        ) {
            SheetBody(palette, preview, onClose)
        }
    }
}

@Composable
private fun SheetBody(palette: YtPalette, preview: () -> YtFeedInput, onClose: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberYtHaptics()
    val swipe = rememberYtSwipe()
    var height by remember { mutableIntStateOf(0) }
    val profile by YtFeed.profile.collectAsState()
    val intentPicked by YtFeed.intent.collectAsState()
    val extras by YtFeed.extras.collectAsState()
    val signals by YtFeed.signals.collectAsState()
    val intent = intentPicked ?: YtIntent.BROWSE
    val rules = profile.rulesFor(intent)
    val personal = profile.personal
    val square = palette.surfaceStyle == YtSurfaceStyle.EDITORIAL
    val shape = if (square) RectangleShape else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    fun edit(transform: (YtFeedRules) -> YtFeedRules) = YtFeed.updateRules(intent, transform)
    // the region is no rule: the row reads it again after the dialog
    var regionTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { YtFeed.loadSignals() }
    // the input is read once per opening: the strip follows the rules, not the lists loading below
    val input = remember { preview() }
    val pools by YtFeed.topicPools.collectAsState()
    val session by YtTopicSession.current.collectAsState()
    val ranked = remember(input, rules, personal, extras, signals, pools, session) {
        YtFeed.rank(input, rules, personal, extras, signals, pools = pools, session = session)
    }
    // a topic added or changed here loads its videos at once, so the strip shows them
    val topicKey = personal.feedTopics.map { Triple(it.id, it.keywords, it.share > 0 && it.enabled) } to session
    LaunchedEffect(topicKey) { YtFeed.loadTopics() }

    // the sheet's own fill decides the colour of the words on it (Formen: a light sheet in a dark
    // design; Frost: pale frosted glass), not the background the palette's roles are made for
    val back = remember(palette) { ytFillSheetBack(palette) }
    val ink = remember(palette, back) { YtInk.on(palette, back) }
    val noteInk = remember(palette, back) { YtInk.on(palette, YtContrast.over(palette.accent.copy(alpha = 0.12f).toArgb(), back)) }

    ProvideYtInk(palette, back) {
    Column(
        Modifier
            .testTag("yt_feed_sheet")
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .onSizeChanged { height = it.height }
            .offset { IntOffset(0, swipe.offset.value.roundToInt().coerceAtLeast(-48)) }
            .clip(shape)
            .background(ytSheetFill(palette))
            .then(if (square) Modifier.border(1.dp, ink.text, shape) else Modifier)
            // taps on the sheet stay on it
            .clickable(interactionSource = null, indication = null) {},
    ) {
        // the head: drag it to move the sheet, the list below scrolls on its own
        Column(
            Modifier
                .fillMaxWidth()
                .ytSwipe(swipe, thresholdPx = (height * 0.25f).coerceAtLeast(140f), direction = 1, haptics = haptics, vertical = true) { onClose() }
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(36.dp, 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ink.textSecondary.copy(alpha = 0.5f)),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.yt_feed_title),
                        color = ink.text,
                        style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.4).sp),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.yt_feed_for_intent, stringResource(intent.label())),
                        color = ink.textSecondary,
                        style = TextStyle(fontFamily = palette.font, fontSize = 13.sp),
                    )
                }
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.yt_feed_close),
                    tint = ink.text,
                    modifier = Modifier
                        .testTag("yt_feed_close")
                        .clip(CircleShape)
                        .clickable(onClick = onClose)
                        .padding(14.dp)
                        .size(20.dp),
                )
            }
        }

        // the live preview: the first videos as the rules rank them now
        PreviewStrip(palette, ranked.videos.take(14))

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp + ytNavPadding()),
        ) {
            // honest about what this is
            Text(
                text = stringResource(R.string.yt_feed_honest),
                color = noteInk.text,
                style = TextStyle(fontFamily = palette.font, fontSize = 13.sp, lineHeight = 18.sp),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(ytCardShape(palette, 14))
                    .background(palette.accent.copy(alpha = 0.12f))
                    .padding(14.dp),
            )

            Section(palette, stringResource(R.string.yt_feed_presets))
            Chips {
                YtIntent.entries.forEach { preset ->
                    val name = stringResource(preset.shortLabel())
                    FeedChip(palette, name, false, "yt_feed_preset_" + preset.name.lowercase(), description = stringResource(R.string.yt_feed_preset_use, name)) {
                        haptics.confirm()
                        edit { YtFeedRules.preset(preset) }
                    }
                }
                FeedChip(palette, stringResource(R.string.yt_feed_reset), false, "yt_feed_reset", icon = R.drawable.ic_reset) {
                    haptics.confirm()
                    YtFeed.reset(intent)
                }
            }

            Section(palette, stringResource(R.string.yt_feed_sources))
            YtFeedSource.entries.forEach { source ->
                WeightRow(palette, ytSourceName(source), rules.weight(source), "yt_feed_weight_" + source.name.lowercase()) { weight ->
                    edit { it.withWeight(source, weight) }
                }
            }
            Note(palette, stringResource(R.string.yt_feed_sources_note))

            // YT: the user's own topics (keywords + share), for every intent
            Section(palette, stringResource(R.string.yt_topic_section))
            FeedTopics(palette)

            Section(palette, stringResource(R.string.yt_feed_region))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val region = remember(regionTick) { PreferenceHelper.getTrendingRegion(context) }
                Text(
                    text = java.util.Locale("", region).displayCountry.ifBlank { region },
                    color = ink.text,
                    style = TextStyle(fontFamily = palette.font, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                FeedChip(palette, stringResource(R.string.yt_feed_region_change), false, "yt_feed_region") {
                    TrendsFragment.showChangeRegionDialog(context) {
                        YtDiscovery.refresh(context)
                        YtFeed.loadExtras(context, force = true)
                        regionTick++
                    }
                }
            }

            Section(palette, stringResource(R.string.yt_feed_formats))
            // Shorts keep their own rows and players, and what is still to come cannot play yet
            Chips {
                Toggle(palette, stringResource(R.string.yt_feed_format_videos), rules.videos, "yt_feed_videos") { v -> edit { it.copy(videos = v) } }
                Toggle(palette, stringResource(R.string.yt_feed_format_live), rules.live, "yt_feed_live") { v -> edit { it.copy(live = v) } }
            }

            Section(palette, stringResource(R.string.yt_feed_length))
            Chips {
                LENGTHS.forEach { (label, range) ->
                    Choice(palette, stringResource(label), rules.minMinutes == range.first && rules.maxMinutes == range.second) {
                        edit { it.copy(minMinutes = range.first, maxMinutes = range.second) }
                    }
                }
            }

            Section(palette, stringResource(R.string.yt_feed_age))
            Chips {
                AGES.forEach { (label, days) ->
                    Choice(palette, stringResource(label), rules.maxAgeDays == days) { edit { it.copy(maxAgeDays = days) } }
                }
            }

            Section(palette, stringResource(R.string.yt_feed_order))
            Chips {
                Choice(palette, stringResource(R.string.yt_feed_order_mixed), rules.order == YtFeedOrder.MIXED) { edit { it.copy(order = YtFeedOrder.MIXED) } }
                Choice(palette, stringResource(R.string.yt_feed_order_newest), rules.order == YtFeedOrder.NEWEST) { edit { it.copy(order = YtFeedOrder.NEWEST) } }
                Choice(palette, stringResource(R.string.yt_feed_order_popular), rules.order == YtFeedOrder.POPULAR) { edit { it.copy(order = YtFeedOrder.POPULAR) } }
            }

            Section(palette, stringResource(R.string.yt_feed_diversity))
            Label(palette, stringResource(R.string.yt_feed_per_channel))
            Chips {
                listOf(0, 1, 2, 3, 5).forEach { n ->
                    Choice(palette, if (n == 0) stringResource(R.string.yt_feed_no_limit) else n.toString(), rules.maxPerChannel == n) { edit { it.copy(maxPerChannel = n) } }
                }
            }
            Label(palette, stringResource(R.string.yt_feed_gap))
            Chips {
                listOf(0, 1, 2, 3).forEach { n ->
                    Choice(palette, n.toString(), rules.channelGap == n) { edit { it.copy(channelGap = n) } }
                }
            }

            Section(palette, stringResource(R.string.yt_feed_watched))
            // YT: one switch for every home and the Abos tab; the choices below are per intent
            Chips {
                Toggle(palette, stringResource(R.string.yt_feed_hide_seen), personal.hideSeen, "yt_feed_hide_seen") { v ->
                    YtFeed.updatePersonal { it.copy(hideSeen = v) }
                }
            }
            Note(palette, stringResource(R.string.yt_feed_hide_seen_note))
            if (!personal.hideSeen) {
                Chips {
                    Choice(palette, stringResource(R.string.yt_feed_watched_hide), rules.watched == YtWatchedMode.HIDE) { edit { it.copy(watched = YtWatchedMode.HIDE) } }
                    Choice(palette, stringResource(R.string.yt_feed_watched_last), rules.watched == YtWatchedMode.LAST) { edit { it.copy(watched = YtWatchedMode.LAST) } }
                    Choice(palette, stringResource(R.string.yt_feed_watched_show), rules.watched == YtWatchedMode.SHOW) { edit { it.copy(watched = YtWatchedMode.SHOW) } }
                }
            }

            Section(palette, stringResource(R.string.yt_feed_channels))
            ChannelList(palette)

            Section(palette, stringResource(R.string.yt_feed_groups))
            Groups(palette, rules.groups) { groups -> edit { it.copy(groups = groups) } }

            Section(palette, stringResource(R.string.yt_feed_words))
            Label(palette, stringResource(R.string.yt_feed_include))
            WordsField(palette, rules.includeWords, "yt_feed_include") { words -> edit { it.copy(includeWords = words) } }
            Chips {
                Toggle(palette, stringResource(R.string.yt_feed_only_included), rules.onlyIncluded, "yt_feed_only_included") { v -> edit { it.copy(onlyIncluded = v) } }
            }
            Label(palette, stringResource(R.string.yt_feed_never))
            WordsField(palette, personal.neverWords, "yt_feed_never") { words -> YtFeed.updatePersonal { it.copy(neverWords = words) } }

            Section(palette, stringResource(R.string.yt_feed_topics))
            Topics(palette, rules.topics) { topics -> edit { it.copy(topics = topics) } }
            Note(palette, stringResource(R.string.yt_feed_topics_note))

            Section(palette, stringResource(R.string.yt_feed_learn))
            Chips {
                Toggle(palette, stringResource(R.string.yt_feed_only_learning), rules.onlyLearning, "yt_feed_only_learning") { v -> edit { it.copy(onlyLearning = v) } }
            }
            Label(palette, stringResource(R.string.yt_feed_learn_min))
            Chips {
                listOf(4, 8, 15, 30).forEach { n ->
                    Choice(palette, stringResource(R.string.yt_feed_minutes_value, n), rules.learnMinMinutes == n) { edit { it.copy(learnMinMinutes = n) } }
                }
            }
            Note(palette, stringResource(R.string.yt_feed_learn_note))

            Section(palette, stringResource(R.string.yt_feed_music))
            Chips {
                Toggle(palette, stringResource(R.string.yt_feed_only_music), rules.onlyMusic, "yt_feed_only_music") { v -> edit { it.copy(onlyMusic = v) } }
                Toggle(palette, stringResource(R.string.yt_feed_music_audio), rules.musicAsAudio, "yt_feed_music_audio") { v -> edit { it.copy(musicAsAudio = v) } }
            }
            Note(palette, stringResource(R.string.yt_feed_music_note))
        }
    }
    }
}

private val LENGTHS = listOf(
    R.string.yt_feed_length_any to (0 to 0),
    R.string.yt_feed_length_short to (0 to 4),
    R.string.yt_feed_length_medium to (4 to 20),
    R.string.yt_feed_length_long to (20 to 0),
    R.string.yt_feed_length_film to (60 to 0),
)

private val AGES = listOf(
    R.string.yt_feed_age_any to 0,
    R.string.yt_feed_age_day to 1,
    R.string.yt_feed_age_week to 7,
    R.string.yt_feed_age_month to 31,
    R.string.yt_feed_age_year to 365,
)

/** The first videos as ranked now; tapped, one shows why it is there and what its channel gets. */
@Composable
private fun PreviewStrip(palette: YtPalette, videos: List<StreamItem>) {
    val ink = ytInk(palette)
    var picked by remember { mutableStateOf<StreamItem?>(null) }
    val loader = remember { ImageHelper.loader() }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.yt_feed_preview),
            color = ink.textSecondary,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.6.sp),
            modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
        )
        if (videos.isEmpty()) {
            Text(
                text = stringResource(R.string.yt_feed_preview_empty),
                color = ink.text,
                style = TextStyle(fontFamily = palette.font, fontSize = 13.sp),
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .height(64.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("yt_feed_preview"),
            ) {
                itemsIndexed(videos, key = { _, video -> video.url.orEmpty() }) { index, video ->
                    val active = picked?.url == video.url
                    Box(
                        Modifier
                            .animateItem(
                                fadeInSpec = null,
                                placementSpec = if (LocalYtMotion.current) spring<IntOffset>(dampingRatio = 0.85f, stiffness = 380f) else null,
                                fadeOutSpec = null,
                            )
                            .width(112.dp)
                            .height(64.dp)
                            .ytPressable(ytCardShape(palette, 10)) { picked = if (active) null else video }
                            .then(if (active) Modifier.border(2.dp, palette.accent, ytCardShape(palette, 10)) else Modifier)
                            .semantics {
                                contentDescription = "${index + 1}. ${video.title.orEmpty()}"
                                selected = active
                            },
                    ) {
                        AsyncImage(
                            model = video.thumbnail,
                            contentDescription = null,
                            imageLoader = loader,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(palette.raised),
                        )
                        Text(
                            text = (index + 1).toString(),
                            color = Color.White,
                            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            modifier = Modifier
                                .padding(4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x99000000))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        val shown = picked
        if (shown != null && videos.any { it.url == shown.url }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
                Text(
                    text = shown.ytTitle,
                    color = ink.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                YtWhyList(palette, shown)
            }
        }
    }
}

/**
 * A weight from 0 (off) to 10 as a track: drag along it and the fill follows the finger 1:1,
 * with a detent at each step and a spring onto the step on release; tap a place to set it, or
 * use − and + (the way for screen readers).
 */
@Composable
private fun WeightRow(
    palette: YtPalette,
    label: String,
    weight: Int,
    tag: String,
    max: Int = YtFeedRules.MAX_WEIGHT,
    /** The value as shown and spoken; the plain step number when null. */
    valueText: ((Int) -> String)? = null,
    /** The step under the finger while dragging, null when the drag ends (for a live preview). */
    onStep: ((Int?) -> Unit)? = null,
    onChange: (Int) -> Unit,
) {
    val ink = ytInk(palette)
    val haptics = rememberYtHaptics()
    val motion = LocalYtMotion.current
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(weight.toFloat()) }
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(weight) {
        if (dragging) return@LaunchedEffect
        if (motion) fill.animateTo(weight.toFloat(), spring(dampingRatio = 0.8f, stiffness = 420f)) else fill.snapTo(weight.toFloat())
    }
    // the gesture lambdas outlive a composition: they read the weight and the callback through these
    val current by rememberUpdatedState(weight)
    val change by rememberUpdatedState(onChange)
    val stepNow by rememberUpdatedState(onStep)
    fun set(next: Int) {
        val clamped = next.coerceIn(0, max)
        if (clamped != current) change(clamped)
    }
    val off = stringResource(R.string.yt_feed_weight_off)
    val shown = if (weight == 0) off else valueText?.invoke(weight) ?: weight.toString()
    val spoken = if (valueText == null) stringResource(R.string.yt_feed_weight, label, weight) else "$label, $shown"
    val accent = palette.accent
    val track = ink.text.copy(alpha = 0.12f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = ink.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = palette.font, fontSize = 14.sp),
            modifier = Modifier.width(118.dp),
        )
        StepButton(palette, R.drawable.ic_remove, stringResource(R.string.yt_feed_weight_less, label), tag + "_less") {
            if (weight > 0) haptics.detent() else haptics.reject()
            set(weight - 1)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .testTag(tag)
                .weight(1f)
                .height(36.dp)
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .semantics {
                    contentDescription = spoken
                    stateDescription = shown
                }
                .pointerInput(max) {
                    detectTapGestures { offset ->
                        val step = (offset.x / width * max).roundToInt()
                        haptics.detent()
                        set(step)
                    }
                }
                .pointerInput(max) {
                    var last = weight
                    // where the finger holds the knob, kept here: the snaps below land a frame
                    // later, so fill.value lags behind the events
                    var pos = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            last = current
                            pos = (offset.x / width * max).coerceIn(0f, max.toFloat())
                            stepNow?.invoke(pos.roundToInt().coerceIn(0, max))
                            haptics.grab()
                            scope.launch { fill.snapTo(pos) }
                        },
                        onDragEnd = {
                            dragging = false
                            stepNow?.invoke(null)
                            val step = pos.roundToInt().coerceIn(0, max)
                            haptics.release()
                            set(step)
                            scope.launch { if (motion) fill.animateTo(step.toFloat(), spring(dampingRatio = 0.7f, stiffness = 520f)) else fill.snapTo(step.toFloat()) }
                        },
                        onDragCancel = {
                            dragging = false
                            stepNow?.invoke(null)
                            scope.launch { fill.snapTo(current.toFloat()) }
                        },
                    ) { change, amount ->
                        change.consume()
                        pos += amount / width * max
                        val raw = pos
                        // past the ends it pulls like a rubber band
                        val shown = when {
                            raw < 0f -> -ytRubberBand(-raw, max.toFloat()) * 0.3f
                            raw > max -> max + ytRubberBand(raw - max, max.toFloat()) * 0.3f
                            else -> raw
                        }
                        scope.launch { fill.snapTo(shown) }
                        val step = raw.roundToInt().coerceIn(0, max)
                        if (step != last) {
                            last = step
                            haptics.detent()
                            stepNow?.invoke(step)
                        }
                    }
                }
                .drawBehind {
                    val h = 8.dp.toPx()
                    val top = (size.height - h) / 2
                    val radius = CornerRadius(h / 2)
                    drawRoundRect(track, Offset(0f, top), Size(size.width, h), radius)
                    val f = (fill.value / max).coerceIn(-0.05f, 1.05f)
                    if (f > 0f) drawRoundRect(accent, Offset(0f, top), Size(size.width * f.coerceAtMost(1f), h), radius)
                    // the steps as small marks
                    for (i in 1 until max) {
                        val x = size.width * i / max
                        drawCircle(ink.text.copy(alpha = 0.18f), radius = 1.5.dp.toPx(), center = Offset(x, size.height / 2))
                    }
                    // the knob, a little past the end while pulled
                    val knobX = (size.width * (fill.value / max)).coerceIn(-12f, size.width + 12f)
                    drawCircle(accent, radius = 9.dp.toPx(), center = Offset(knobX, size.height / 2))
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = 3.dp.toPx(), center = Offset(knobX, size.height / 2))
                },
        ) {}
        StepButton(palette, R.drawable.ic_add, stringResource(R.string.yt_feed_weight_more, label), tag + "_more") {
            if (weight < max) haptics.detent() else haptics.reject()
            set(weight + 1)
        }
        Text(
            text = shown,
            color = if (weight == 0) ink.textSecondary else ink.text,
            maxLines = 1,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 13.sp),
            modifier = Modifier.width(if (valueText == null) 30.dp else 44.dp).padding(start = 4.dp),
        )
    }
}

@Composable
private fun StepButton(palette: YtPalette, icon: Int, description: String, tag: String, onClick: () -> Unit) {
    val ink = ytInk(palette)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .testTag(tag)
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = ink.textSecondary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Section(palette: YtPalette, title: String) {
    val ink = ytInk(palette)
    val editorial = palette.surfaceStyle == YtSurfaceStyle.EDITORIAL
    Text(
        text = if (editorial) title.uppercase() else title,
        color = ink.text,
        style = TextStyle(
            fontFamily = if (editorial) YtFonts.plexMono else palette.font,
            fontWeight = FontWeight.Bold,
            fontSize = if (editorial) 12.sp else 16.sp,
            letterSpacing = if (editorial) 1.sp else 0.sp,
        ),
        modifier = Modifier
            .padding(top = 22.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun Label(palette: YtPalette, text: String) {
    val ink = ytInk(palette)
    Text(
        text = text,
        color = ink.textSecondary,
        style = TextStyle(fontFamily = palette.font, fontSize = 13.sp),
        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
    )
}

@Composable
private fun Note(palette: YtPalette, text: String) {
    val ink = ytInk(palette)
    Text(
        text = text,
        color = ink.textSecondary,
        style = TextStyle(fontFamily = palette.font, fontSize = 12.sp, lineHeight = 16.sp),
        modifier = Modifier.padding(top = 6.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** A chip in the design's surfaces; [active] fills it with the accent. */
@Composable
internal fun FeedChip(
    palette: YtPalette,
    label: String,
    active: Boolean,
    tag: String? = null,
    icon: Int? = null,
    description: String? = null,
    onClick: () -> Unit,
) {
    val shape = ytPillShape(palette)
    val fill by animateColorAsState(if (active) palette.accent else palette.raised, ytStateSpec(), label = "feedChip")
    // on a sheet the chip's words are checked against the chip as it looks there (Frost's see-through raised)
    val rest = ytInkOnFill(palette, palette.raised)?.text ?: palette.text
    val ink by animateColorAsState(if (active) palette.onAccent else rest, ytStateSpec(), label = "feedChipInk")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .heightIn(min = 40.dp)
            .ytPressable(shape, squish = palette.surfaceStyle == YtSurfaceStyle.CLAY, onClick = onClick)
            .background(fill)
            .then(if (palette.surfaceStyle == YtSurfaceStyle.EDITORIAL) Modifier.border(1.dp, palette.text, shape) else Modifier)
            .semantics {
                selected = active
                if (description != null) contentDescription = description
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.padding(end = 6.dp).size(16.dp))
        Text(
            text = label,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
        )
    }
}

/** One of several choices: a tick as it is taken. */
@Composable
private fun Choice(palette: YtPalette, label: String, active: Boolean, onClick: () -> Unit) {
    val haptics = rememberYtHaptics()
    FeedChip(palette, label, active, icon = if (active) R.drawable.ic_yt_check else null) {
        if (!active) haptics.tick()
        onClick()
    }
}

/** On or off. */
@Composable
private fun Toggle(palette: YtPalette, label: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val haptics = rememberYtHaptics()
    FeedChip(palette, label, on, tag, icon = if (on) R.drawable.ic_yt_check else null) {
        haptics.threshold(!on)
        onChange(!on)
    }
}

/**
 * A text field in the design's look. [onDone] runs with what was typed on the keyboard's done
 * key (and on [addLabel]'s button, if given) and gives back what the field shows then; [onType]
 * runs on every change, so nothing typed is lost when the sheet closes.
 */
@Composable
private fun FeedField(
    palette: YtPalette,
    initial: String,
    hint: String,
    tag: String,
    addLabel: String? = null,
    onType: ((String) -> Unit)? = null,
    onDone: (String) -> String,
) {
    var text by remember { mutableStateOf(initial) }
    // a preset or reset from outside shows; what the user is typing (the same words) stays as typed
    LaunchedEffect(initial) {
        if (YtFeedRules.parseWords(text) != YtFeedRules.parseWords(initial)) text = initial
    }
    val shape = ytCardShape(palette, 12)
    val ink = ytInkOnFill(palette, palette.raised) ?: YtInk(0, palette.text, palette.textSecondary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onType?.invoke(it)
            },
            singleLine = true,
            textStyle = TextStyle(fontFamily = palette.font, fontSize = 14.sp, color = ink.text),
            cursorBrush = SolidColor(palette.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { text = onDone(text) }),
            modifier = Modifier
                .testTag(tag)
                .weight(1f)
                .clip(shape)
                .background(palette.raised)
                .then(if (palette.surfaceStyle == YtSurfaceStyle.EDITORIAL) Modifier.border(1.dp, palette.text, shape) else Modifier)
                .semantics { contentDescription = hint },
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (text.isEmpty()) {
                        Text(hint, color = ink.textSecondary, style = TextStyle(fontFamily = palette.font, fontSize = 14.sp))
                    }
                    inner()
                }
            },
        )
        if (addLabel != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .testTag(tag + "_add")
                    .padding(start = 8.dp)
                    .size(44.dp)
                    .ytPressable(CircleShape) { text = onDone(text) }
                    .background(palette.accent)
                    .semantics { role = Role.Button },
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = addLabel, tint = palette.onAccent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Words, separated by commas; stored as they are typed. */
@Composable
private fun WordsField(palette: YtPalette, words: List<String>, tag: String, onChange: (List<String>) -> Unit) {
    val haptics = rememberYtHaptics()
    FeedField(
        palette,
        words.joinToString(", "),
        stringResource(R.string.yt_feed_words_hint),
        tag,
        onType = { typed -> onChange(YtFeedRules.parseWords(typed)) },
    ) { typed ->
        val parsed = YtFeedRules.parseWords(typed)
        haptics.confirm()
        onChange(parsed)
        parsed.joinToString(", ")
    }
}

/** The topics as chips (tap: weight 1 to 5 in turn, ×: remove) and a field to add one. */
@Composable
private fun Topics(palette: YtPalette, topics: List<YtTopic>, onChange: (List<YtTopic>) -> Unit) {
    val ink = ytInk(palette)
    val haptics = rememberYtHaptics()
    if (topics.isNotEmpty()) {
        Chips {
            topics.forEach { topic ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FeedChip(
                        palette,
                        topic.query + " " + "•".repeat(topic.weight.coerceIn(1, 5)),
                        true,
                        description = stringResource(R.string.yt_feed_topic_weight, topic.query, topic.weight),
                    ) {
                        haptics.detent()
                        val next = if (topic.weight >= 5) 1 else topic.weight + 1
                        onChange(topics.map { if (it == topic) it.copy(weight = next) else it })
                    }
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.yt_feed_topic_remove, topic.query),
                        tint = ink.textSecondary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                haptics.tick()
                                onChange(topics - topic)
                            }
                            .padding(12.dp)
                            .size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (topics.size < YtFeedRules.MAX_TOPICS) {
        FeedField(palette, "", stringResource(R.string.yt_feed_topics_hint), "yt_feed_topic", addLabel = stringResource(R.string.yt_feed_topic_add)) { typed ->
            val query = typed.trim()
            if (query.isNotEmpty() && topics.none { it.query.equals(query, ignoreCase = true) }) {
                haptics.confirm()
                onChange(topics + YtTopic(query))
            }
            ""
        }
    }
}

/** The subscription groups as chips; none chosen means all subscriptions. */
@Composable
private fun Groups(palette: YtPalette, chosen: List<String>, onChange: (List<String>) -> Unit) {
    val groups by produceState<List<String>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { Database.subscriptionGroupsDao().getAll().map { it.name } }.getOrDefault(emptyList())
        }
    }
    val all = groups ?: return
    if (all.isEmpty()) {
        Note(palette, stringResource(R.string.yt_feed_groups_none))
        return
    }
    Chips {
        Choice(palette, stringResource(R.string.yt_feed_groups_all), chosen.isEmpty()) { onChange(emptyList()) }
        all.forEach { name ->
            Toggle(palette, name, name in chosen, "yt_feed_group") { on ->
                onChange(if (on) chosen + name else chosen - name)
            }
        }
    }
}

/**
 * The channels with a rule (more, less, music, learning) and the ones paused, each with its
 * choices: Mehr / Normal / Weniger, a week's pause, music or learning channel.
 */
@Composable
private fun ChannelList(palette: YtPalette) {
    val profile by YtFeed.profile.collectAsState()
    val pauses by YtChannelPause.paused.collectAsState()
    val news by YtNewsChannels.list.collectAsState()
    val personal = profile.personal
    val keys = remember(personal, pauses, news) {
        (
            personal.channels.keys + personal.musicChannels + personal.learnChannels +
                pauses.mapNotNull { it.id ?: it.name.takeIf { n -> n.isNotBlank() } } +
                news.channels.mapNotNull { it.id ?: it.name.takeIf { n -> n.isNotBlank() } }
            ).distinct()
    }
    if (keys.isEmpty()) {
        Note(palette, stringResource(R.string.yt_feed_channels_empty))
        return
    }
    val haptics = rememberYtHaptics()
    val cardFill = palette.raised.copy(alpha = 0.6f)
    val sheetBack = LocalYtInk.current?.back
    val cardBack = remember(palette, sheetBack) { YtContrast.over(cardFill.toArgb(), sheetBack ?: palette.background.toArgb()) }
    keys.forEach { key ->
        val pause = pauses.firstOrNull { it.id == key || (it.id == null && it.name == key) }
        val newsEntry = news.channels.firstOrNull { it.id == key || (it.id == null && it.name == key) }
        val name = personal.channelNames[key] ?: pause?.name?.takeIf { it.isNotBlank() } ?: newsEntry?.name?.takeIf { it.isNotBlank() } ?: key
        val rule = personal.channels[key]
        Column(
            Modifier
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .clip(ytCardShape(palette, 14))
                .background(cardFill)
                .padding(12.dp),
        ) {
            ProvideYtInk(palette, cardBack) {
            Text(
                text = name,
                color = ytInk(palette).text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Chips {
                Choice(palette, stringResource(R.string.yt_feed_channel_more), rule == YtChannelRule.BOOST) { YtFeed.setChannel(key, name, YtChannelRule.BOOST) }
                Choice(palette, stringResource(R.string.yt_feed_channel_normal), rule == null) { YtFeed.setChannel(key, name, null) }
                Choice(palette, stringResource(R.string.yt_feed_channel_less), rule == YtChannelRule.LESS) { YtFeed.setChannel(key, name, YtChannelRule.LESS) }
                Toggle(palette, stringResource(R.string.yt_feed_channel_pause), pause != null, "yt_feed_channel_pause") {
                    // YtChannelPause knows a channel by its id; a key that is only the name has none
                    val id = if (pause != null) pause.id else key.takeIf { it != name }
                    ytTogglePause(id, name, pause?.avatar)
                    haptics.confirm()
                }
                Toggle(palette, stringResource(R.string.yt_feed_channel_music), key in personal.musicChannels, "yt_feed_channel_music") { on ->
                    YtFeed.updatePersonal { it.copy(musicChannels = if (on) it.musicChannels + key else it.musicChannels - key) }
                }
                Toggle(palette, stringResource(R.string.yt_feed_channel_learn), key in personal.learnChannels, "yt_feed_channel_learn") { on ->
                    YtFeed.updatePersonal { it.copy(learnChannels = if (on) it.learnChannels + key else it.learnChannels - key) }
                }
                // YT: a news channel: its newest videos in the News overlay
                Toggle(palette, stringResource(R.string.yt_r27_news_feed_channel), newsEntry != null, "yt_feed_channel_news") {
                    if (newsEntry != null) {
                        YtNewsChannels.toggle(newsEntry.id, newsEntry.name)
                    } else {
                        YtNewsChannels.toggle(key.takeIf { it != name }, name)
                    }
                }
            }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// "Themen": the user's own topics (YtFeedTopic)

/**
 * The user's topics as cards (name, keywords, share, duration, on/off), one opened for editing
 * at a time, and a field to add one; a new topic opens at once with its name as first keyword.
 */
@Composable
private fun FeedTopics(palette: YtPalette) {
    val profile by YtFeed.profile.collectAsState()
    val session by YtTopicSession.current.collectAsState()
    val haptics = rememberYtHaptics()
    val topics = profile.personal.feedTopics
    var open by remember { mutableStateOf<String?>(null) }
    val now = remember(profile, session) { System.currentTimeMillis() }
    val active = topics.filter { YtFeedTopics.active(it, now, session) }
    val fractions = YtFeedTopics.fractions(active)
    val activeShare = active.map { it.id }.zip(fractions).toMap()
    val capped = active.sumOf { YtFeedTopics.snapShare(it.share) } > YtFeedTopics.MAX_TOTAL_SHARE
    topics.forEach { topic ->
        TopicCard(
            palette = palette,
            topic = topic,
            now = now,
            session = session,
            fraction = activeShare[topic.id],
            capped = capped,
            open = open == topic.id,
            onOpen = { open = if (open == topic.id) null else topic.id },
        )
    }
    if (topics.size < YtFeedTopics.MAX_TOPICS) {
        FeedField(palette, "", stringResource(R.string.yt_topic_add_hint), "yt_topic_new", addLabel = stringResource(R.string.yt_topic_add)) { typed ->
            val id = "t" + System.currentTimeMillis().toString(36) + kotlin.random.Random.nextInt(1296).toString(36)
            val created = YtFeedTopics.create(id, typed, System.currentTimeMillis(), YtTopicSession.current.value)
            if (created == null || topics.any { YtFeedTopics.same(it.name, created.name) }) {
                haptics.reject()
                typed
            } else {
                haptics.confirm()
                YtFeed.updatePersonal { it.copy(feedTopics = it.feedTopics + created) }
                open = created.id
                ""
            }
        }
    } else {
        Note(palette, stringResource(R.string.yt_topic_limit, YtFeedTopics.MAX_TOPICS))
    }
    Note(palette, stringResource(R.string.yt_topic_note))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicCard(
    palette: YtPalette,
    topic: YtFeedTopic,
    now: Long,
    session: Long,
    /** Its share of the home after the cap over all topics; null while off or expired. */
    fraction: Double?,
    capped: Boolean,
    open: Boolean,
    onOpen: () -> Unit,
) {
    val haptics = rememberYtHaptics()
    val cardFill = palette.raised.copy(alpha = 0.6f)
    val sheetBack = LocalYtInk.current?.back
    val cardBack = remember(palette, sheetBack) { YtContrast.over(cardFill.toArgb(), sheetBack ?: palette.background.toArgb()) }
    val expired = YtFeedTopics.expired(topic, now, session)
    val on = topic.enabled && !expired
    fun edit(transform: (YtFeedTopic) -> YtFeedTopic) = YtFeed.updateTopic(topic.id, transform)
    // the share under the finger while the slider is dragged, for the live count
    var dragging by remember { mutableStateOf<Int?>(null) }
    Column(
        Modifier
            .testTag("yt_topic_card")
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .clip(ytCardShape(palette, 14))
            .background(cardFill)
            .then(if (palette.surfaceStyle == YtSurfaceStyle.EDITORIAL) Modifier.border(1.dp, ytInk(palette).text, ytCardShape(palette, 14)) else Modifier),
    ) {
        ProvideYtInk(palette, cardBack) {
            val ink = ytInk(palette)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = stringResource(if (open) R.string.yt_topic_done else R.string.yt_topic_edit)) {
                        haptics.tick()
                        onOpen()
                    }
                    .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = topic.name,
                        color = ink.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp),
                    )
                    val share = if (topic.share == 0) stringResource(R.string.yt_feed_weight_off) else stringResource(R.string.yt_topic_share_value, topic.share)
                    Text(
                        text = listOf(
                            stringResource(R.string.yt_topic_keyword_count, topic.keywords.size),
                            share,
                            topicStatus(topic, now, expired),
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        color = ink.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = palette.font, fontSize = 12.sp, lineHeight = 16.sp),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                TopicSwitch(palette, on, stringResource(R.string.yt_topic_switch, topic.name)) { want ->
                    haptics.threshold(want)
                    edit { if (want) YtFeedTopics.switchedOn(it, System.currentTimeMillis(), YtTopicSession.current.value) else it.copy(enabled = false) }
                }
            }
            AnimatedVisibility(visible = open, enter = ytEnterTransition(fadeIn(tween(160))), exit = ytExitTransition(fadeOut(tween(120)))) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    FeedField(palette, topic.name, stringResource(R.string.yt_topic_name_hint), "yt_topic_name", onType = { typed ->
                        val name = YtFeedTopics.cleanName(typed)
                        if (name.isNotEmpty()) edit { it.copy(name = name) }
                    }) { typed ->
                        val name = YtFeedTopics.cleanName(typed)
                        if (name.isEmpty()) {
                            haptics.reject()
                            topic.name
                        } else {
                            haptics.confirm()
                            edit { it.copy(name = name) }
                            name
                        }
                    }

                    Label(palette, stringResource(R.string.yt_topic_keywords))
                    if (topic.keywords.isEmpty()) {
                        Note(palette, stringResource(R.string.yt_topic_keywords_none))
                    } else {
                        Chips {
                            topic.keywords.forEach { keyword ->
                                KeywordChip(palette, keyword) {
                                    haptics.tick()
                                    edit { YtFeedTopics.withoutKeyword(it, keyword) }
                                }
                            }
                        }
                    }
                    if (topic.keywords.size < YtFeedTopics.MAX_KEYWORDS) {
                        Spacer(Modifier.height(8.dp))
                        FeedField(
                            palette,
                            "",
                            stringResource(R.string.yt_topic_keywords_hint),
                            "yt_topic_keyword",
                            addLabel = stringResource(R.string.yt_topic_keyword_add),
                        ) { typed ->
                            if (YtFeedTopics.parseKeywords(typed).isEmpty()) {
                                ""
                            } else {
                                haptics.confirm()
                                edit { YtFeedTopics.withKeywords(it, typed) }
                                ""
                            }
                        }
                    }

                    Label(palette, stringResource(R.string.yt_topic_share))
                    val stepNow = YtFeedTopics.snapShare(topic.share) / YtFeedTopics.SHARE_STEP
                    WeightRow(
                        palette,
                        stringResource(R.string.yt_topic_share),
                        stepNow,
                        "yt_topic_share",
                        max = YtFeedTopics.MAX_SHARE / YtFeedTopics.SHARE_STEP,
                        valueText = { step -> "${step * YtFeedTopics.SHARE_STEP} %" },
                        onStep = { step -> dragging = step?.let { it * YtFeedTopics.SHARE_STEP } },
                    ) { step ->
                        edit { it.copy(share = YtFeedTopics.snapShare(step * YtFeedTopics.SHARE_STEP)) }
                    }
                    // the count follows the finger; with other topics on, the cap over all of them applies
                    val shownShare = dragging ?: topic.share
                    val shownFraction = if (dragging != null || fraction == null) {
                        YtFeedTopics.fractions(listOf(topic.copy(share = shownShare))).first()
                    } else {
                        fraction
                    }
                    Text(
                        text = if (shownShare == 0) {
                            stringResource(R.string.yt_topic_share_zero)
                        } else {
                            stringResource(R.string.yt_topic_share_preview, YtFeedTopics.perPage(shownFraction), YtFeedTopics.PAGE)
                        },
                        color = ink.text,
                        style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                        modifier = Modifier
                            .testTag("yt_topic_preview")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (capped && on && dragging == null) {
                        Note(palette, stringResource(R.string.yt_topic_share_capped, YtFeedTopics.MAX_TOTAL_SHARE))
                    }

                    Label(palette, stringResource(R.string.yt_topic_expiry))
                    Chips {
                        TOPIC_EXPIRIES.forEach { (label, expiry) ->
                            Choice(palette, stringResource(label), topic.expiry == expiry) {
                                edit { YtFeedTopics.withExpiry(it, expiry, System.currentTimeMillis(), YtTopicSession.current.value) }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    FeedChip(palette, stringResource(R.string.yt_topic_remove), false, "yt_topic_remove", icon = R.drawable.ic_close) {
                        haptics.confirm()
                        YtFeed.updatePersonal { personal -> personal.copy(feedTopics = personal.feedTopics.filterNot { it.id == topic.id }) }
                    }
                }
            }
        }
    }
}

private val TOPIC_EXPIRIES = listOf(
    R.string.yt_topic_expiry_session to YtTopicExpiry.SESSION,
    R.string.yt_topic_expiry_day to YtTopicExpiry.DAY,
    R.string.yt_topic_expiry_week to YtTopicExpiry.WEEK,
    R.string.yt_topic_expiry_never to YtTopicExpiry.NEVER,
)

/** How long [topic] still runs, in words; empty when it has no end and is on. */
@Composable
private fun topicStatus(topic: YtFeedTopic, now: Long, expired: Boolean): String {
    if (!topic.enabled) return stringResource(R.string.yt_topic_state_off)
    if (expired) return stringResource(R.string.yt_topic_expired)
    return when (topic.expiry) {
        YtTopicExpiry.NEVER -> ""
        YtTopicExpiry.SESSION -> stringResource(R.string.yt_topic_left_session)
        YtTopicExpiry.DAY, YtTopicExpiry.WEEK -> {
            val left = YtFeedTopics.remainingMs(topic, now) ?: return ""
            val hours = ((left + 3_599_999L) / 3_600_000L).toInt()
            if (hours > 48) stringResource(R.string.yt_topic_left_days, (hours + 23) / 24) else stringResource(R.string.yt_topic_left_hours, hours.coerceAtLeast(1))
        }
    }
}

/** A keyword as a chip with a cross that removes it. */
@Composable
private fun KeywordChip(palette: YtPalette, keyword: String, onRemove: () -> Unit) {
    val shape = ytPillShape(palette)
    val ink = ytInkOnFill(palette, palette.raised)?.text ?: palette.text
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .testTag("yt_topic_keyword_chip")
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(palette.raised)
            .then(if (palette.surfaceStyle == YtSurfaceStyle.EDITORIAL) Modifier.border(1.dp, palette.text, shape) else Modifier)
            .padding(start = 14.dp),
    ) {
        Text(
            text = keyword,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
            modifier = Modifier.widthIn(max = 220.dp),
        )
        Icon(
            painterResource(R.drawable.ic_close),
            contentDescription = stringResource(R.string.yt_topic_keyword_remove, keyword),
            tint = ink,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .padding(12.dp)
                .size(14.dp),
        )
    }
}

/**
 * On/off in the design's colours: a track that fills with the accent and a knob that slides;
 * a switch for screen readers. Editorial draws it square with a rule.
 */
@Composable
private fun TopicSwitch(palette: YtPalette, on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val ink = ytInk(palette)
    val square = palette.surfaceStyle == YtSurfaceStyle.EDITORIAL
    val track by animateColorAsState(if (on) palette.accent else ink.text.copy(alpha = 0.22f), ytStateSpec(), label = "topicTrack")
    val knob by animateColorAsState(if (on) palette.onAccent else Color.White, ytStateSpec(), label = "topicKnob")
    val shift = remember { Animatable(if (on) 1f else 0f) }
    val motion = LocalYtMotion.current
    LaunchedEffect(on) {
        if (motion) shift.animateTo(if (on) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 520f)) else shift.snapTo(if (on) 1f else 0f)
    }
    val stateOn = stringResource(R.string.yt_topic_state_on)
    val stateOff = stringResource(R.string.yt_topic_state_off)
    val shape = if (square) RectangleShape else CircleShape
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .testTag("yt_topic_switch")
            .size(56.dp, 48.dp)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .semantics {
                contentDescription = label
                stateDescription = if (on) stateOn else stateOff
            },
    ) {
        Box(
            Modifier
                .size(44.dp, 26.dp)
                .clip(shape)
                .background(track)
                .then(if (square) Modifier.border(1.dp, ink.text, shape) else Modifier)
                .drawBehind {
                    val r = size.height / 2f - 3.dp.toPx()
                    val x = size.height / 2f + (size.width - size.height) * shift.value
                    if (square) {
                        drawRect(knob, Offset(x - r, size.height / 2f - r), Size(2 * r, 2 * r))
                    } else {
                        drawCircle(knob, radius = r, center = Offset(x, size.height / 2f))
                    }
                },
        )
    }
}
