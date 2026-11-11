package com.github.libretube.ui.yt.news

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.ui.yt.YtContrast
import com.github.libretube.ui.yt.YtInk
import com.github.libretube.ui.yt.YtMotionPrefs
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.readableOn
import com.github.libretube.ui.yt.rememberYtHaptics
import com.github.libretube.ui.yt.ytAgeText
import com.github.libretube.ui.yt.ytSurfaceTones
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * YT: the story part of a News card (YtNewsLayer's card page calls it between the text and the
 * buttons). For a [YtNewsCard.Story]: how many sources report it, a "neu" badge when videos came
 * in since the last look, "Alle anhängen" (the whole story to the end of the queue, oldest
 * first), and the timeline of its videos newest first (time · channel · title; new ones marked):
 * tap a row to watch that video, hold it for "Aus Geschichte lösen". The timeline takes at most
 * about the space of the text below the picture and scrolls when longer. For every card it
 * notes the videos as seen (YtNewsStories); a single card shows nothing more.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ColumnScope.YtNewsStoryBlock(
    palette: YtPalette,
    card: YtNewsCard,
    open: (StreamItem) -> Unit,
    queue: (StreamItem) -> Unit,
) {
    val ids = remember(card) { card.items.map { it.id } }
    val opens = YtNews.opens
    val shown = YtNews.shown
    // judged once per opening against the last look, then this card counts as seen; not while
    // a pinch only previews the window (the opening after it would find nothing new)
    val fresh = remember(opens, ids) { YtNewsStories.fresh(opens, ids) }
    LaunchedEffect(opens, ids, shown) { if (shown) YtNewsStories.markSeen(ids) }
    val story = card as? YtNewsCard.Story ?: return
    val look = remember(palette) { storyLook(palette) }
    val haptics = rememberYtHaptics()
    Column(
        Modifier
            .weight(1.5f, fill = false)
            .fillMaxWidth()
            .padding(top = 10.dp)
            .testTag("yt_news_story"),
    ) {
        StoryHeader(look, story, fresh.size) {
            // the story in the order it happened
            story.timeline.asReversed().forEach { item -> YtNewsStories.video(item.id)?.let(queue) }
        }
        val scroll = rememberScrollState()
        val timelineLabel = stringResource(R.string.yt_r28_news_story_timeline)
        Column(
            Modifier
                .weight(1f, fill = false)
                .padding(top = 4.dp)
                // scrolls only when longer than its room, so a swipe on a short one moves the card
                .verticalScroll(scroll, enabled = scroll.maxValue in 1 until Int.MAX_VALUE)
                .semantics { contentDescription = timelineLabel },
        ) {
            story.timeline.forEachIndexed { index, item ->
                TimelineRow(
                    look = look,
                    item = item,
                    first = index == 0,
                    last = index == story.timeline.lastIndex,
                    fresh = item.id in fresh,
                    lead = item.id == story.lead.id,
                    open = open,
                    queue = queue,
                    detach = {
                        haptics.confirm()
                        YtNewsStories.detach(item.id)
                    },
                    onHold = { haptics.grab() },
                )
            }
        }
    }
}

@Composable
private fun StoryHeader(look: StoryLook, story: YtNewsCard.Story, freshCount: Int, queueAll: () -> Unit) {
    val videos = story.items.size
    val sources = story.sources
    val sourcesText = pluralStringResource(R.plurals.yt_r28_news_story_sources, sources, sources)
    val label = if (sources == videos) {
        sourcesText
    } else {
        pluralStringResource(R.plurals.yt_r28_news_story_videos, videos, videos) + " · " + sourcesText
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (freshCount > 0) {
            Text(
                text = if (freshCount == videos) {
                    stringResource(R.string.yt_r28_news_story_new)
                } else {
                    stringResource(R.string.yt_r28_news_story_new_count, freshCount)
                }.uppercase(),
                color = look.badgeInk,
                maxLines = 1,
                style = TextStyle(fontFamily = look.font, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.6.sp),
                modifier = Modifier
                    .testTag("yt_news_story_new")
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(look.badgeFill)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Text(
            text = label.uppercase(),
            color = look.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = look.font, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.8.sp),
            modifier = Modifier.weight(1f),
        )
        val queueAllLabel = stringResource(R.string.yt_r28_news_story_queue_all)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .testTag("yt_news_story_queue_all")
                .heightIn(min = 40.dp)
                .widthIn(max = 160.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = queueAll)
                .padding(horizontal = 10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_queue), contentDescription = null, tint = look.accent, modifier = Modifier.size(16.dp))
            Text(
                text = queueAllLabel,
                color = look.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = look.font, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineRow(
    look: StoryLook,
    item: YtNewsItem,
    first: Boolean,
    last: Boolean,
    fresh: Boolean,
    lead: Boolean,
    open: (StreamItem) -> Unit,
    queue: (StreamItem) -> Unit,
    detach: () -> Unit,
    onHold: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val watchLabel = stringResource(R.string.yt_r27_news_watch)
    val queueLabel = stringResource(R.string.yt_r27_news_queue)
    val detachLabel = stringResource(R.string.yt_r28_news_story_detach)
    val newLabel = stringResource(R.string.yt_r28_news_story_new)
    val age = remember(item.uploadedMs) { ytAgeText(context, item.uploadedMs) }
    val meta = listOfNotNull(age, item.channelName?.takeIf { it.isNotBlank() }).joinToString(" · ")
    fun watch() {
        YtNewsStories.video(item.id)?.let(open)
    }
    fun toQueue() {
        YtNewsStories.video(item.id)?.let(queue)
    }
    Box {
        Row(
            Modifier
                .testTag("yt_news_story_row")
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .height(IntrinsicSize.Min)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(queueLabel) { toQueue(); true },
                        CustomAccessibilityAction(detachLabel) { detach(); true },
                    )
                }
                .combinedClickable(
                    onClickLabel = watchLabel,
                    onClick = ::watch,
                    onLongClick = {
                        onHold()
                        menu = true
                    },
                ),
        ) {
            // the timeline's rail: a line through the rows, a dot per video (new ones in the accent)
            val dot = if (fresh) look.accent else look.ink.textSecondary
            Canvas(Modifier.width(18.dp).fillMaxHeight()) {
                val x = size.width / 2f
                val y = 15.dp.toPx()
                if (!first) drawLine(look.rail, Offset(x, 0f), Offset(x, y), strokeWidth = 1.5.dp.toPx())
                if (!last) drawLine(look.rail, Offset(x, y), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
                drawCircle(dot, radius = (if (fresh || lead) 4.5f else 3.5f).dp.toPx(), center = Offset(x, y))
            }
            Column(Modifier.weight(1f).padding(start = 6.dp, top = 6.dp, bottom = 6.dp)) {
                Text(
                    text = buildAnnotatedString {
                        append(meta)
                        if (fresh) {
                            if (meta.isNotEmpty()) append(" · ")
                            withStyle(SpanStyle(color = look.accent, fontWeight = FontWeight.Bold)) { append(newLabel) }
                        }
                    },
                    color = look.ink.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = look.font, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
                )
                Text(
                    text = item.title,
                    color = look.ink.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = look.font,
                        fontWeight = if (lead) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                    ),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = look.menuBack) {
            MenuEntry(look.menuInk, watchLabel, null) { menu = false; watch() }
            MenuEntry(look.menuInk, queueLabel, null) { menu = false; toQueue() }
            MenuEntry(look.menuInk, detachLabel, null, "yt_news_story_detach") { menu = false; detach() }
        }
    }
}

/**
 * The overlay's header button for grouping by hand (YtNewsLayer's header calls it): merge the
 * card shown with the next or the previous one, or take a video out of its story. After an edit
 * the pager follows the card that holds what the user acted on.
 */
@Composable
internal fun YtNewsStoryMenu(pager: PagerState, ink: YtInk) {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberYtHaptics()
    val back = Color(ink.back)
    val label = stringResource(R.string.yt_r28_news_story_menu)

    /** After an edit: to the card that holds [anchor], once the pager has the new cards. */
    fun follow(anchor: String) {
        val cards = (YtNewsStories.showing ?: YtNews.deck).cards
        val index = cards.indexOfFirst { card -> card.items.any { it.id == anchor } }
        if (index < 0) return
        scope.launch {
            withTimeoutOrNull(600L) { snapshotFlow { pager.pageCount }.first { it == cards.size } }
            if (index == pager.currentPage) return@launch
            if (YtMotionPrefs.enabled) pager.animateScrollToPage(index) else pager.scrollToPage(index)
        }
    }

    // nothing to group on an empty deck
    if (pager.pageCount == 0) return
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .testTag("yt_news_story_menu")
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = label) { open = true },
        ) {
            Icon(
                painterResource(R.drawable.ic_yt_dots_vertical),
                contentDescription = label,
                tint = ink.text,
                modifier = Modifier.size(22.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = back) {
            val cards = (YtNewsStories.showing ?: YtNews.deck).cards
            val page = pager.currentPage
            val card = cards.getOrNull(page) ?: return@DropdownMenu
            val next = cards.getOrNull(page + 1)
            val previous = cards.getOrNull(page - 1)
            if (next != null) {
                MenuEntry(ink, stringResource(R.string.yt_r28_news_story_merge_next), next.lead.title, "yt_news_story_merge_next") {
                    open = false
                    haptics.confirm()
                    YtNewsStories.merge(card, next)
                    follow(card.lead.id)
                }
            }
            if (previous != null) {
                MenuEntry(ink, stringResource(R.string.yt_r28_news_story_merge_previous), previous.lead.title, "yt_news_story_merge_previous") {
                    open = false
                    haptics.confirm()
                    YtNewsStories.merge(previous, card)
                    follow(card.lead.id)
                }
            }
            if (card is YtNewsCard.Story) {
                for (item in card.timeline) {
                    val name = item.channelName?.takeIf { it.isNotBlank() } ?: item.title
                    MenuEntry(ink, stringResource(R.string.yt_r28_news_story_detach_named, name), item.title) {
                        open = false
                        haptics.confirm()
                        YtNewsStories.detach(item.id)
                        // stay with what is left of the story
                        card.items.firstOrNull { it.id != item.id }?.let { follow(it.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(ink: YtInk, label: String, detail: String?, tag: String? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        text = {
            Column(Modifier.widthIn(max = 280.dp)) {
                Text(label, color = ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium))
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(detail, color = ink.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 12.5.sp))
                }
            }
        },
        onClick = onClick,
    )
}

/** How the story part looks on the card's surface (words checked against both of its ends). */
private class StoryLook(
    val ink: YtInk,
    /** The sources line, "Alle anhängen" and the new marks: the accent, readable on the card. */
    val accent: Color,
    val badgeFill: Color,
    val badgeInk: Color,
    val rail: Color,
    val font: FontFamily,
    /** The hold menu of a row: the window's ground and its words. */
    val menuBack: Color,
    val menuInk: YtInk,
)

private fun storyLook(palette: YtPalette): StoryLook {
    val ground = palette.background.copy(alpha = 1f)
    val groundArgb = ground.toArgb()
    val tones = ytSurfaceTones(palette, groundArgb)
    val ink = YtInk.onAll(palette, tones)
    val badgeFill = palette.accent.copy(alpha = 1f)
    return StoryLook(
        ink = ink,
        accent = Color(YtContrast.readableOnAll(palette.accent.toArgb(), tones)),
        badgeFill = badgeFill,
        badgeInk = palette.onAccent.readableOn(badgeFill),
        rail = ink.textSecondary.copy(alpha = 0.45f),
        font = palette.font,
        menuBack = ground,
        menuInk = YtInk.on(palette, groundArgb),
    )
}
