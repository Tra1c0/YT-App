package com.github.libretube.ui.yt

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.method.BaseMovementMethod
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.FontRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.parseAsHtml
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.Streams
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.player.ytShareScrubSource
import com.github.libretube.util.HtmlParser
import com.github.libretube.util.LinkHandler
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * YT: the video page below the player, built differently for each design (Movo and Side have
 * their own pages in MovoWatch.kt and SideWatch.kt):
 * - YouTube neu: the familiar page, made calm: meta line with "more", pills, a comments card.
 * - After: a film poster: title in large capitals, "presented by", a synopsis, scenes, reviews.
 * - Clay: everything on puffy slabs; the actions are a grid of clay tiles.
 * - Editorial: a newspaper article: kicker, headline, byline, a numbered action table, the
 *   description as body text with a drop cap, contents with times, letters to the editor.
 * - Glas: glass panes over drifting light; round glass buttons with captions.
 * - Pop: the first word on a yellow marker, the channel as a tilted sticker, blobs in two rows,
 *   the description in a speech bubble.
 * - Formen: night page with a row of material shapes as buttons.
 * - Frequency: YouTube neu's page (meta with "more", creator, comments card) with Formen's shapes
 *   as the actions, breathing with the sound; Formen's wave under the tabs.
 * - Frost: frosted cards and icon-only frost circles.
 * - Kiesel: a dashboard: three stat pebbles, the black channel capsule, a grid of pebbles.
 * Nothing is cut at the screen edge: the action sets are laid out in even rows instead of a
 * sideways scroll. The description is drawn here, in the type of the design, with working links
 * and timestamps; the classic description stays hidden, so nothing shows twice.
 */

/** What every page shows. */
private class WatchBasics(
    val streams: Streams,
    val hasChannel: Boolean,
    val subscribed: Boolean,
    val toggle: () -> Unit,
    val meta: String,
    val like: WatchAction,
    val actions: List<WatchAction>,
    val rate: YtRateState,
)

@Composable
private fun rememberBasics(streams: Streams, videoId: String?, palette: YtPalette, actions: YtWatchActions): WatchBasics {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channelId = streams.uploaderUrl?.toID()
    var subscribed by remember(channelId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(channelId) {
        subscribed = channelId?.let {
            withContext(Dispatchers.IO) { runCatching { SubscriptionHelper.isSubscribed(it) }.getOrNull() }
        }
    }
    val toggle: () -> Unit = {
        val newState = subscribed != true
        subscribed = newState
        if (channelId != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    if (newState) {
                        SubscriptionHelper.subscribe(channelId, streams.uploader, streams.uploaderAvatar, streams.uploaderVerified)
                    } else {
                        SubscriptionHelper.unsubscribe(channelId)
                    }
                }
            }
        }
    }
    val like = WatchAction(
        R.drawable.ic_like,
        if (streams.likes > 0) streams.likes.formatShort() else stringResource(R.string.yt_watch_like),
    ) {}
    // held, it opens like and dislike (the tap stays as it was); the rating shows as a tiny mark
    val rate = rememberYtRateState(videoId)
    val markColor = Color(YtContrast.readable(palette.accent.toArgb(), palette.surface.toArgb(), 3.0))
    // Return YouTube Dislike: the count for the hover reveal, and the small green-red bar
    val dislikes = if (PlayerHelper.localRYD) streams.dislikes else -1L
    SideEffect { rate.dislikes = dislikes }
    val sentiment = rememberYtSentimentBar(streams.likes, streams.dislikes, palette)
    like.gesture = Modifier.ytRateHold(rate, markColor, sentiment)
    val shareGesture = Modifier.ytShareScrubSource(actions.shareScrub)
    val list = buildList {
        add(WatchAction(R.drawable.ic_playlist_add, stringResource(R.string.yt_watch_save), actions.onSave))
        // held and dragged onto the time bar, it shares from the second it is let go at
        add(WatchAction(R.drawable.ic_share, stringResource(R.string.share), actions.onShare).also { it.gesture = shareGesture })
        if (!streams.isLive) add(WatchAction(R.drawable.ic_download, stringResource(R.string.yt_watch_download), actions.onDownload))
        add(WatchAction(R.drawable.ic_headphones, stringResource(R.string.yt_watch_audio), actions.onAudioOnly))
    }
    return WatchBasics(
        streams = streams,
        hasChannel = channelId != null,
        subscribed = subscribed == true,
        toggle = toggle,
        meta = ytViewsAndAge(context, streams.views, streams.uploaded ?: 0L),
        like = like,
        actions = list,
        rate = rate,
    )
}

/** The video page of [design] below the player. */
@Composable
fun YtWatchPage(
    design: YtDesign,
    palette: YtPalette,
    imageLoader: ImageLoader,
    controller: YtWatchController,
    actions: YtWatchActions,
) {
    val streams = controller.streams ?: return
    val basics = rememberBasics(streams, controller.videoId, palette, actions)
    Column(Modifier.fillMaxWidth()) {
    YtRateTiles(basics.rate)
    when (design) {
        YtDesign.AFTER -> AfterPage(palette, imageLoader, basics, controller, actions)
        YtDesign.CLAY -> ClayPage(palette, imageLoader, basics, controller, actions)
        YtDesign.EDITORIAL -> EditorialPage(palette, basics, controller, actions)
        YtDesign.GLASS -> GlassPage(palette, imageLoader, basics, controller, actions)
        YtDesign.POP -> PopPage(palette, imageLoader, basics, controller, actions)
        YtDesign.SHAPES -> ShapesPage(palette, imageLoader, basics, controller, actions)
        YtDesign.FREQUENCY -> FrequencyPage(palette, imageLoader, basics, controller, actions)
        YtDesign.FROST -> FrostPage(palette, imageLoader, basics, controller, actions)
        YtDesign.KIESEL -> KieselPage(palette, imageLoader, basics, controller, actions)
        else -> YouTubePage(palette, imageLoader, basics, controller, actions)
    }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared pieces

/**
 * The description with working links and timestamps, in the type of the design. Collapsed it
 * shows [collapsedLines] lines; [dropCap] sets the first letter large, as in print.
 */
@Composable
internal fun WatchDescription(
    streams: Streams,
    onLink: (String) -> Unit,
    color: Color,
    linkColor: Color,
    @FontRes font: Int,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    sizeSp: Float = 14f,
    collapsedLines: Int = 4,
    dropCap: Boolean = false,
) {
    val description = streams.description
    if (description.isBlank()) return
    // the size in the page's own units (scaled with the screen width, like the text around it)
    val textPx = with(LocalDensity.current) { sizeSp.sp.toPx() }
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                includeFontPadding = false
                movementMethod = TapLinks
            }
        },
        update = { view ->
            view.setTextColor(color.toArgb())
            view.setLinkTextColor(linkColor.toArgb())
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
            view.typeface = runCatching { ResourcesCompat.getFont(view.context, font) }.getOrNull() ?: Typeface.DEFAULT
            view.setLineSpacing(0f, 1.3f)
            view.maxLines = if (expanded) Int.MAX_VALUE else collapsedLines
            if (view.tag != description) {
                view.tag = description
                val text: CharSequence = if (description.contains("<") && description.contains(">")) {
                    description.replace("</a>", "</a> ").parseAsHtml(tagHandler = HtmlParser(LinkHandler(onLink)))
                } else {
                    plainLinks(description, onLink)
                }
                view.text = if (dropCap) withDropCap(text) else text
            }
            // collapsed, the text always starts at its first line
            if (!expanded) view.scrollTo(0, 0)
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Web addresses in a plain description as links that go the same way as the others. */
private fun plainLinks(description: String, onLink: (String) -> Unit): CharSequence {
    val text = SpannableString(description)
    if (!Linkify.addLinks(text, Linkify.WEB_URLS)) return description
    for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        text.removeSpan(span)
        text.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) = onLink(span.url)
            },
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return text
}

/**
 * Opens a link on a tap and does nothing else. The platform's link handling selects the tapped
 * link and then keeps scrolling the text to it, so a collapsed description would show its
 * middle; here nothing is selected and the text does not scroll. Only a tap on the link itself
 * counts, not one beside the end of its line.
 */
private object TapLinks : BaseMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return false
        val link = linkAt(widget, buffer, event) ?: return false
        if (action == MotionEvent.ACTION_UP) link.onClick(widget)
        return true
    }

    private fun linkAt(widget: TextView, buffer: Spannable, event: MotionEvent): ClickableSpan? {
        val layout = widget.layout ?: return null
        val x = event.x - widget.totalPaddingLeft + widget.scrollX
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
        if (y < 0 || y > layout.height) return null
        val line = layout.getLineForVertical(y)
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        return buffer.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }
}

private fun withDropCap(text: CharSequence): CharSequence {
    val start = text.indexOfFirst { it.isLetterOrDigit() }
    if (start < 0) return text
    return SpannableStringBuilder(text).apply {
        setSpan(RelativeSizeSpan(2.4f), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** "More" or "Less" below a description. */
@Composable
private fun MoreToggle(expanded: Boolean, color: Color, style: TextStyle, caps: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val text = stringResource(if (expanded) R.string.yt_watch_less else R.string.yt_watch_more)
    Text(
        text = if (caps) text.uppercase() else text,
        color = color,
        style = style,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun Avatar(imageLoader: ImageLoader, streams: Streams, size: Int, fallback: Color, shape: Shape = CircleShape) {
    AsyncImage(
        model = streams.uploaderAvatar,
        contentDescription = streams.uploader,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(fallback),
    )
}

/** [items] in rows of [perRow], each as wide as the others; nothing runs off the screen. */
@Composable
private fun <T> EvenRows(
    items: List<T>,
    perRow: Int,
    modifier: Modifier = Modifier,
    gap: Int = 8,
    cell: @Composable (T, Int, Modifier) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        items.chunked(perRow).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                row.forEachIndexed { c, item -> cell(item, r * perRow + c, Modifier.weight(1f)) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The chapters as a contents list: title and start time; the current one marked. */
@Composable
private fun ChapterList(
    streams: Streams,
    activeIndex: Int,
    onSeek: (Long) -> Unit,
    title: TextStyle,
    time: TextStyle,
    active: Color,
    modifier: Modifier = Modifier,
    leader: Boolean = false,
) {
    Column(modifier) {
        streams.chapters.forEachIndexed { i, chapter ->
            val titleStyle = if (i == activeIndex) title.copy(color = active) else title
            val timeStyle = if (i == activeIndex) time.copy(color = active) else time
            val row = Modifier
                .fillMaxWidth()
                .clickable { onSeek(chapter.start * 1000) }
                .padding(vertical = 7.dp)
            if (leader) {
                LeaderRow(chapter.title, DateUtils.formatElapsedTime(chapter.start), titleStyle, timeStyle, row)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = row) {
                    Text(chapter.title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(DateUtils.formatElapsedTime(chapter.start), style = timeStyle, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}

/**
 * A line of a printed contents list: the title, dots up to the time, the time at the right
 * edge. The time is measured first, the title gets the rest (and is cut only when it needs
 * all of it), the dots fill what is left between them.
 */
@Composable
private fun LeaderRow(title: String, time: String, titleStyle: TextStyle, timeStyle: TextStyle, modifier: Modifier) {
    Layout(
        content = {
            Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(" ".padEnd(120, '·'), style = timeStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            Text(time, style = timeStyle, maxLines = 1)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 10.dp.roundToPx()
        val timeText = measurables[2].measure(Constraints())
        val room = (width - timeText.width - gap).coerceAtLeast(0)
        val titleText = measurables[0].measure(Constraints(maxWidth = room))
        val dots = (room - titleText.width).coerceAtLeast(0)
        val dotsText = measurables[1].measure(Constraints(minWidth = dots, maxWidth = dots))
        val height = maxOf(titleText.height, timeText.height, dotsText.height)
        layout(width, height) {
            titleText.placeRelative(0, (height - titleText.height) / 2)
            dotsText.placeRelative(titleText.width, (height - dotsText.height) / 2)
            timeText.placeRelative(width - timeText.width, (height - timeText.height) / 2)
        }
    }
}

/** The glass designs see drifting light through their panes. */
@Composable
private fun GlassStage(palette: YtPalette, content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(backdrop)
                .background(Brush.verticalGradient(ytGlassScreenColors(palette))),
        ) {
            LiquidLight(ytGlassLightColors(palette), Modifier.fillMaxSize())
        }
        CompositionLocalProvider(LocalWatchBackdrop provides backdrop) { content() }
    }
}

@Composable
private fun Modifier.glassPane(shape: Shape, frost: Boolean): Modifier {
    val backdrop = LocalWatchBackdrop.current
    return if (frost) {
        this.frostGlass(backdrop, shape)
    } else if (backdrop != null) {
        this.liquidGlass(backdrop, shape, ytDayNight(Color(0x80FFFFFF), Color(0x33A8E0D6)), ytDayNight(Color(0x2E5C8C85), Color(0x66000000)))
    } else {
        this.ytGlass(shape)
    }
}

// ---------------------------------------------------------------------------------------------
// YouTube neu: the familiar page, calm

@Composable
private fun YouTubePage(palette: YtPalette, imageLoader: ImageLoader, b: WatchBasics, controller: YtWatchController, actions: YtWatchActions) {
    val streams = b.streams
    var open by remember(streams.title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = ytTitle(streams.title),
            color = palette.text,
            maxLines = if (open) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Bold, fontSize = 18.5.sp, lineHeight = 23.sp),
            modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 8.dp),
        )
        Text(
            text = buildAnnotatedString {
                append(b.meta)
                append("  ")
                withStyle(SpanStyle(color = palette.text, fontWeight = FontWeight.SemiBold)) {
                    append(stringResource(if (open) R.string.yt_watch_less else R.string.yt_watch_more))
                }
            },
            color = palette.textSecondary,
            style = TextStyle(fontFamily = palette.font, fontSize = 12.5.sp),
            modifier = Modifier
                .padding(start = 9.dp, end = 9.dp, top = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(4.dp),
        )
        if (open) {
            WatchDescription(
                streams, actions.onLink, palette.text, palette.accent, R.font.font_geist_400, expanded = true,
                modifier = Modifier
                    .padding(start = 13.dp, end = 13.dp, top = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surface)
                    .padding(14.dp),
            )
        }
        WatchCreator(palette, imageLoader, streams, b.hasChannel, b.subscribed, b.toggle, actions.onOpenChannel)
        EvenRows(listOf(b.like) + b.actions.take(3), perRow = 4, modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 14.dp)) { action, _, mod ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = mod
                    .then(action.gesture)
                    .ytPressable(RoundedCornerShape(16.dp)) { action.onClick() }
                    .background(palette.surface)
                    .padding(vertical = 10.dp),
            ) {
                Icon(painterResource(action.icon), contentDescription = null, tint = palette.text, modifier = Modifier.size(20.dp))
                Text(action.label, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = palette.font, fontSize = 11.5.sp), modifier = Modifier.padding(top = 4.dp))
            }
        }
        // the comments as a card, as in the app
        Column(
            Modifier
                .padding(start = 13.dp, end = 13.dp, top = 14.dp)
                .fillMaxWidth()
                .ytPressable(RoundedCornerShape(14.dp)) { actions.onComments() }
                .background(palette.surface)
                .padding(14.dp),
        ) {
            Text(stringResource(R.string.yt_watch_comments), color = palette.text, style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
            Text(stringResource(R.string.yt_watch_open_comments), color = palette.textSecondary, style = TextStyle(fontFamily = palette.font, fontSize = 12.5.sp), modifier = Modifier.padding(top = 3.dp))
        }
        if (streams.chapters.isNotEmpty()) WatchChapters(palette, imageLoader, streams, controller.chapterIndex, actions.onSeek)
        WatchUpNextTitle(palette)
    }
}

// ---------------------------------------------------------------------------------------------
// Frequency: YouTube neu's page, Formen's shapes, moving with the sound

@Composable
private fun FrequencyPage(palette: YtPalette, imageLoader: ImageLoader, b: WatchBasics, controller: YtWatchController, actions: YtWatchActions) {
    // the page holds the sound pulse while it is shown
    FrequencyPulseHost()
    val streams = b.streams
    var open by remember(streams.title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = ytTitle(streams.title),
            color = palette.text,
            maxLines = if (open) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp),
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp),
        )
        Text(
            text = buildAnnotatedString {
                append(b.meta)
                append("  ")
                withStyle(SpanStyle(color = palette.text, fontWeight = FontWeight.SemiBold)) {
                    append(stringResource(if (open) R.string.yt_watch_less else R.string.yt_watch_more))
                }
            },
            color = palette.textSecondary,
            style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.5.sp),
            modifier = Modifier
                .padding(start = 10.dp, end = 10.dp, top = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(4.dp),
        )
        if (open) {
            WatchDescription(
                streams, actions.onLink, palette.text, palette.lime, R.font.font_geist_400, expanded = true,
                modifier = Modifier
                    .padding(start = 13.dp, end = 13.dp, top = 8.dp)
                    .ytSurface(palette, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 8.dp))
                    .padding(16.dp),
            )
        }
        WatchCreator(palette, imageLoader, streams, b.hasChannel, b.subscribed, b.toggle, actions.onOpenChannel)
        WatchActionRow(palette, listOf(b.like) + b.actions, Modifier.padding(top = 16.dp))
        // the comments as a card, as on YouTube neu, in Formen's widget corners
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 13.dp, end = 13.dp, top = 16.dp)
                .fillMaxWidth()
                .ytPressable(RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomEnd = 22.dp, bottomStart = 22.dp)) { actions.onComments() }
                .ytSurface(palette, RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomEnd = 22.dp, bottomStart = 22.dp))
                .padding(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.yt_watch_comments), color = palette.text, style = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
                Text(stringResource(R.string.yt_watch_open_comments), color = palette.textSecondary, style = TextStyle(fontFamily = YtFonts.geist, fontSize = 12.5.sp), modifier = Modifier.padding(top = 3.dp))
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .frequencyCookie(palette.lavender, lobes = 6, rest = 0.1f),
            ) {
                Icon(painterResource(R.drawable.ic_yt_chevron_right), contentDescription = null, tint = palette.ink, modifier = Modifier.size(14.dp))
            }
        }
        if (streams.chapters.isNotEmpty()) WatchChapters(palette, imageLoader, streams, controller.chapterIndex, actions.onSeek)
        WatchUpNextTitle(palette)
    }
}

// ---------------------------------------------------------------------------------------------
// After: a film poster

@Composable
private fun AfterPage(palette: YtPalette, imageLoader: ImageLoader, b: WatchBasics, controller: YtWatchController, actions: YtWatchActions) {
    val streams = b.streams
    var open by remember(streams.title) { mutableStateOf(false) }
    val caps = TextStyle(fontFamily = YtFonts.geist, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 2.sp)
    val section = TextStyle(fontFamily = YtFonts.anton, fontSize = 15.sp, letterSpacing = 1.5.sp, color = palette.accent)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = ytTitle(streams.title).uppercase(),
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.anton, fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = 0.4.sp),
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(b.meta.uppercase(), color = palette.textSecondary, style = caps, modifier = Modifier.padding(top = 10.dp))
        Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(1.dp).background(palette.text.copy(alpha = 0.15f)))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = actions.onOpenChannel),
            ) {
                Text(stringResource(R.string.yt_after_presented).uppercase(), color = palette.textSecondary, style = caps)
                Text(
                    streams.uploader.uppercase(),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = YtFonts.anton, fontSize = 20.sp, letterSpacing = 0.6.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (b.hasChannel) WatchSubscribe(palette, b.subscribed, b.toggle)
        }
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(1.dp).background(palette.text.copy(alpha = 0.15f)))
    }
    WatchActionRow(palette, listOf(b.like) + b.actions, Modifier.padding(top = 16.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (streams.description.isNotBlank()) {
            Text(stringResource(R.string.yt_after_synopsis).uppercase(), style = section, modifier = Modifier.padding(top = 22.dp))
            WatchDescription(
                streams, actions.onLink, palette.text.copy(alpha = 0.85f), palette.accent, R.font.font_geist_400, open,
                modifier = Modifier.padding(top = 8.dp), collapsedLines = 4,
            )
            MoreToggle(open, palette.accent, caps, caps = true, modifier = Modifier.padding(top = 4.dp)) { open = !open }
        }
        if (streams.chapters.isNotEmpty()) {
            Text(stringResource(R.string.yt_after_scenes).uppercase(), style = section, modifier = Modifier.padding(top = 20.dp))
            ChapterList(
                streams, controller.chapterIndex, actions.onSeek,
                title = TextStyle(fontFamily = YtFonts.geist, fontSize = 14.sp, color = palette.text),
                time = caps.copy(color = palette.textSecondary),
                active = palette.accent,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 18.dp)
                .fillMaxWidth()
                .border(1.dp, palette.text.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                .clickable(onClick = actions.onComments)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.yt_after_reviews).uppercase(), color = palette.text, style = TextStyle(fontFamily = YtFonts.anton, fontSize = 17.sp, letterSpacing = 1.sp), modifier = Modifier.weight(1f))
            Text("→", color = palette.accent, style = TextStyle(fontFamily = YtFonts.geist, fontSize = 18.sp))
        }
    }
    WatchUpNextTitle(palette)
}

// ---------------------------------------------------------------------------------------------
// Clay: puffy slabs and a grid of clay tiles

private val ClayText: Color get() = ytDayNight(Color(0xFF5C5260), Color(0xFFEEDFD3))
private val ClayDim: Color get() = ytDayNight(Color(0xFF8C7880), Color(0xFFC9B3AA))
private val ClayTileColors = listOf(ytDayNight(Color(0xFFF3E1D2), Color(0xFF4A3830)), ytDayNight(Color(0xFFB7CBE4), Color(0xFF3C4A5C)), ytDayNight(Color(0xFFE9C9C1), Color(0xFF52383A)), ytDayNight(Color(0xFFD9E3C9), Color(0xFF3D4A36)), ytDayNight(Color(0xFFF3E1D2), Color(0xFF4A3830)), ytDayNight(Color(0xFFB7CBE4), Color(0xFF3C4A5C)))

@Composable
private fun ClayPage(palette: YtPalette, imageLoader: ImageLoader, b: WatchBasics, controller: YtWatchController, actions: YtWatchActions) {
    val streams = b.streams
    var open by remember(streams.title) { mutableStateOf(false) }
    val slab = RoundedCornerShape(30.dp)
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(start = 14.dp, end = 14.dp, top = 12.dp)
                .fillMaxWidth()
                .ytSurface(palette, slab)
                .padding(18.dp),
        ) {
            Text(ytTitle(streams.title), color = ClayText, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, lineHeight = 24.sp))
            Text(b.meta, color = ClayDim, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp), modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = actions.onOpenChannel),
                ) {
                    Avatar(imageLoader, streams, 42, ytDayNight(Color(0xFFF7E6D6), Color(0xFF3A2C26)))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(streams.uploader, color = ClayText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp))
                        if (streams.uploaderSubscriberCount >= 0) {
                            Text(stringResource(R.string.subscribers, streams.uploaderSubscriberCount.formatShort()), color = ClayDim, style = TextStyle(fontFamily = YtFonts.nunito, fontSize = 12.sp))
                        }
                    }
                }
                if (b.hasChannel) WatchSubscribe(palette, b.subscribed, b.toggle)
            }
        }
        // the actions as clay tiles, three in a row
        val tiles = listOf(b.like) + b.actions + WatchAction(R.drawable.ic_comment, stringResource(R.string.yt_watch_comments), actions.onComments)
        EvenRows(tiles, perRow = 3, gap = 10, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 16.dp)) { action, i, mod ->
            val shape = RoundedCornerShape(26.dp)
            val color = ClayTileColors[i % ClayTileColors.size]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = mod
                    .then(action.gesture)
                    .shadow(8.dp, shape, clip = false, ambientColor = ytDayNight(Color(0x66B07A66), Color(0x66000000)), spotColor = ytDayNight(Color(0x80B07A66), Color(0x80000000)))
                    .ytPressable(shape, squish = true) { action.onClick() }
                    .background(color)
                    .background(Brush.verticalGradient(listOf(ytDayNight(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.08f)), Color.Transparent)))
                    .border(1.5.dp, ytDayNight(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.12f)), shape)
                    .padding(vertical = 14.dp),
            ) {
                Icon(painterResource(action.icon), contentDescription = null, tint = ClayText, modifier = Modifier.size(24.dp))
                Text(action.label, color = ClayText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp), modifier = Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp))
            }
        }
        if (streams.description.isNotBlank()) {
            Column(
                Modifier
                    .padding(start = 14.dp, end = 14.dp, top = 18.dp)
                    .fillMaxWidth()
                    .ytSurface(palette, RoundedCornerShape(26.dp))
                    .padding(18.dp),
            ) {
                Text(stringResource(R.string.yt_watch_description), color = ClayText, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 15.sp))
                WatchDescription(streams, actions.onLink, ClayText, ytDayNight(Color(0xFF9D6A6C), Color(0xFFD9A5A6)), R.font.font_nunito_600, open, modifier = Modifier.padding(top = 8.dp))
                MoreToggle(open, ytDayNight(Color(0xFF9D6A6C), Color(0xFFD9A5A6)), TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp), caps = false, modifier = Modifier.padding(top = 4.dp)) { open = !open }
            }
        }
        if (streams.chapters.isNotEmpty()) WatchChapters(palette, imageLoader, streams, controller.chapterIndex, actions.onSeek)
        WatchUpNextTitle(palette)
    }
}

// ---------------------------------------------------------------------------------------------
// Editorial: a newspaper article

@Composable
private fun EditorialPage(palette: YtPalette, b: WatchBasics, controller: YtWatchController, actions: YtWatchActions) {
    val streams = b.streams
    var open by remember(streams.title) { mutableStateOf(false) }
    val mono = TextStyle(fontFamily = YtFonts.plexMono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 1.4.sp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "${stringResource(R.string.yt_editorial_video)} — ${streams.uploader}".uppercase(),
            color = palette.accentText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = mono,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            ytTitle(streams.title),
            color = palette.text,
            style = TextStyle(fontFamily = YtFonts.inter, fontWeight = FontWeight.Black, fontSize = 25.sp, lineHeight = 29.sp, letterSpacing = (-0.4).sp),
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Text(
                buildAnnotatedString {
                    append("${stringResource(R.string.yt_editorial_by)} ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(streams.uploader) }
                    if (streams.uploaderSubscriberCount >= 0) append(" · " + stringResource(R.string.subscribers, streams.uploaderSubscriberCount.formatShort()))
                    append("\n" + b.meta)
                },
                color = palette.text,
                style = TextStyle(fontFamily = YtFonts.inter, fontSize = 12.5.sp, lineHeight = 17.sp),
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = actions.onOpenChannel),
            )
            if (b.hasChannel) WatchSubscribe(palette, b.subscribed, b.toggle)
        }
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(2.dp).background(palette.text))
        // the actions as a numbered table, three to a row, with rules between the cells: wide
        // enough that every label shows in full
        val items = listOf(b.like) + b.actions
        val rule = palette.text.copy(alpha = 0.25f)
        items.chunked(3).forEachIndexed { r, row ->
            if (r > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(rule))
            Row(Modifier.fillMaxWidth().height(58.dp)) {
                row.forEachIndexed { c, action ->
                    if (c > 0) Box(Modifier.padding(vertical = 10.dp).width(1.dp).height(38.dp).background(rule))
                    Column(
                        Modifier
                            .then(action.gesture)
                            .weight(1f)
                            .clickable(onClick = action.onClick)
                            .padding(horizontal = 6.dp, vertical = 9.dp),
                    ) {
                        Text("%02d".format(r * 3 + c + 1), color = palette.accentText, style = mono.copy(fontSize = 9.sp))
                        Text(action.label.uppercase(), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = mono.copy(fontSize = 10.sp, letterSpacing = 0.6.sp), modifier = Modifier.padding(top = 3.dp))
                    }
                }
                repeat(3 - row.size) {
                    Box(Modifier.padding(vertical = 10.dp).width(1.dp).height(38.dp).background(rule))
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.text))
        if (streams.description.isNotBlank()) {
            WatchDescription(
                streams, actions.onLink, palette.text, palette.accentText, R.font.font_inter_400, open,
                modifier = Modifier.padding(top = 14.dp), sizeSp = 15f, collapsedLines = 6, dropCap = true,
            )
            MoreToggle(open, palette.text, mono, caps = true, modifier = Modifier.padding(top = 6.dp)) { open = !open }
        }
        if (streams.chapters.isNotEmpty()) {
            Text(stringResource(R.string.yt_editorial_contents).uppercase(), color = palette.text, style = mono, modifier = Modifier.padding(top = 18.dp))
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(1.dp).background(palette.text))
            ChapterList(
                streams, controller.chapterIndex, actions.onSeek,
                title = TextStyle(fontFamily = YtFonts.inter, fontSize = 14.sp, color = palette.text),
                time = mono.copy(color = palette.text),
                active = palette.accentText,
                leader = true,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .border(1.dp, palette.text)
                .clickable(onClick = actions.onComments)
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            Text(stringResource(R.string.yt_editorial_letters).uppercase(), color = palette.text, style = mono, modifier = Modifier.weight(1f))
            Text("→", color = palette.text, style = mono)
        }
    }
    WatchUpNextTitle(palette)
}

// ---------------------------------------------------------------------------------------------
// Glas and Frost: panes over drifting light

@Composable
private fun GlassPage(palette: YtPalette, imageLoader: ImageLoader, b: WatchBasics, controller: YtWatchController, actions: YtWatchActions) {
    GlassStage(palette) { PanePage(palette, imageLoader, b, controller, actions, frost = false) }
}

@Composable
private fun FrostPage(palette: YtPalette, imageLoader: ImageLoader, b: WatchBasics, controller: YtWatchController, actions: YtWatchActions) {
    GlassStage(palette) { PanePage(palette, imageLoader, b, controller, actions, frost = true) }
}

/**
 * Glas: a glass pane with the title, the channel on a glass pill, round glass buttons with
 * captions. Frost: frosted cards, icon-only frost circles, the title free above them.
 */
@Composable
private fun PanePage(
    palette: YtPalette,
    imageLoader: ImageLoader,
    b: WatchBasics,
    controller: YtWatchController,
    actions: YtWatchActions,
    frost: Boolean,
) {
    val streams = b.streams
    var tab by remember(streams.title) { mutableStateOf(0) }
    val font: FontFamily = if (frost) YtFonts.jakarta else YtFonts.googleSans
    val ink = if (frost) Color.White else palette.text
    val dim = if (frost) Color(0xB3FFFFFF) else palette.text.copy(alpha = 0.62f)
    Column(Modifier.fillMaxWidth()) {
        if (frost) {
            Text(ytTitle(streams.title), color = ink, style = TextStyle(fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
            Text(b.meta, color = dim, style = TextStyle(fontFamily = font, fontSize = 12.5.sp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
        } else {
            Column(
                Modifier
                    .padding(start = 13.dp, end = 13.dp, top = 12.dp)
                    .fillMaxWidth()
                    .glassPane(RoundedCornerShape(26.dp), frost = false)
                    .padding(16.dp),
            ) {
                Text(ytTitle(streams.title), color = ink, style = TextStyle(fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp))
                Text(b.meta, color = dim, style = TextStyle(fontFamily = font, fontSize = 12.5.sp), modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 13.dp, end = 13.dp, top = 12.dp)
                .fillMaxWidth()
                .glassPane(if (frost) RoundedCornerShape(24.dp) else RoundedCornerShape(50), frost)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = actions.onOpenChannel),
            ) {
                Avatar(imageLoader, streams, 40, Color(0x40FFFFFF))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(streams.uploader, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
                    if (streams.uploaderSubscriberCount >= 0) {
                        Text(stringResource(R.string.subscribers, streams.uploaderSubscriberCount.formatShort()), color = dim, style = TextStyle(fontFamily = font, fontSize = 11.5.sp))
                    }
                }
            }
            if (b.hasChannel) WatchSubscribe(palette, b.subscribed, b.toggle)
        }
        // round buttons: Glas with captions, Frost icons only
        val buttons = listOf(b.like) + b.actions
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, top = 16.dp)
                .fillMaxWidth(),
        ) {
            buttons.forEach { action ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .then(action.gesture)
                            .size(if (frost) 50.dp else 54.dp)
                            .glassPane(CircleShape, frost)
                            .clip(CircleShape)
                            .clickable(onClick = action.onClick),
                    ) {
                        Icon(painterResource(action.icon), contentDescription = action.label, tint = ink, modifier = Modifier.size(22.dp))
                    }
                    if (!frost) {
                        Text(action.label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = font, fontSize = 11.sp), modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        val labels = buildList {
            add(stringResource(R.string.yt_watch_description))
            if (streams.chapters.isNotEmpty()) add(stringResource(R.string.yt_watch_chapters))
            add(stringResource(R.string.yt_watch_comments))
        }
        WatchTabs(
            palette, labels, tab,
            onSelect = { i -> if (i == labels.lastIndex) actions.onComments() else tab = i },
            modifier = Modifier.padding(top = 18.dp),
        )
        if (tab == 0 && streams.description.isNotBlank()) {
            var open by remember(streams.title) { mutableStateOf(false) }
            Column(
                Modifier
                    .padding(start = 13.dp, end = 13.dp, top = 12.dp)
                    .fillMaxWidth()
                    .glassPane(RoundedCornerShape(24.dp), frost)
                    .padding(16.dp),
            ) {
                WatchDescription(
                    streams, actions.onLink, ink, if (frost) palette.accent else ytDayNight(Color(0xFF0A8F7F), Color(0xFF7FE0D2)),
                    if (frost) R.font.font_jakarta_500 else R.font.font_google_sans_400, open,
                )
                MoreToggle(open, ink, TextStyle(fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 13.sp), caps = false, modifier = Modifier.padding(top = 4.dp)) { open = !open }
            }
        }
        if (tab == 1 && streams.chapters.isNotEmpty()) WatchChapters(palette, imageLoader, streams, controller.chapterIndex, actions.onSeek)
        WatchUpNextTitle(palette)
    }
}

// ---------------------------------------------------------------------------------------------
// Pop: a marker, a sticker, blobs and a speech bubble

private val PopPlum: Color get() = ytDayNight(Color(0xFF3B2054), Color(0xFFF5ECFF))

/** Plum type on the pastel blobs, the sticker and the marker, in both modes. */
private val PopPlumInk = Color(0xFF3B2054)
private val PopMarker = Color(0xFFFDE682)
private val PopBlobs = listOf(Color(0xFFFDE682), Color(0xFFF7B3CE), Color(0xFFDFD6F7), Color(0xFFBFE8D2), Color(0xFFFFD8B5))

@Composable
private fun PopPage(palette: YtPalette, imageLoader: ImageLoader, b: WatchBasics, controller: YtWatchController, actions: YtWatchActions) {
    val streams = b.streams
    var open by remember(streams.title) { mutableStateOf(false) }
    val first = ytTitle(streams.title).substringBefore(' ')
    val rest = ytTitle(streams.title).removePrefix(first)
    Column(Modifier.fillMaxWidth()) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(background = PopMarker, color = PopPlumInk)) { append(first) }
                append(rest)
            },
            color = PopPlum,
            style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 23.sp, lineHeight = 29.sp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
        )
        Text(b.meta, color = PopPlum.copy(alpha = 0.65f), style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Bold, fontSize = 12.5.sp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
        // the channel as a tilted sticker
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .rotate(-1.5f)
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(22.dp), clip = false)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFFDFD6F7))
                .padding(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = actions.onOpenChannel),
            ) {
                Avatar(imageLoader, streams, 40, Color.White, BlobShape(0.55f, 0.45f, 0.5f, 0.6f))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(streams.uploader, color = PopPlumInk, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp))
                    if (streams.uploaderSubscriberCount >= 0) {
                        Text(stringResource(R.string.subscribers, streams.uploaderSubscriberCount.formatShort()), color = PopPlumInk.copy(alpha = 0.7f), style = TextStyle(fontFamily = YtFonts.nunito, fontSize = 12.sp))
                    }
                }
            }
            if (b.hasChannel) WatchSubscribe(palette, b.subscribed, b.toggle)
        }
        // blobs in rows, each its own color and a little tilted
        val blobs = listOf(b.like) + b.actions + WatchAction(R.drawable.ic_comment, stringResource(R.string.yt_watch_comments), actions.onComments)
        EvenRows(blobs, perRow = 3, gap = 10, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) { action, i, mod ->
            val shape = BlobShape(0.5f + 0.05f * (i % 3), 0.45f, 0.55f - 0.04f * (i % 2), 0.5f)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = mod
                    .then(action.gesture)
                    .rotate(if (i % 2 == 0) -2f else 2f)
                    .ytPressable(shape, squish = true) { action.onClick() }
                    .background(PopBlobs[i % PopBlobs.size])
                    .padding(vertical = 14.dp),
            ) {
                Icon(painterResource(action.icon), contentDescription = null, tint = PopPlumInk, modifier = Modifier.size(24.dp))
                Text(action.label, color = PopPlumInk, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp), modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp))
            }
        }
        if (streams.description.isNotBlank()) {
            // a speech bubble: one corner points back to the channel
            Column(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 26.dp))
                    .background(ytDayNight(Color.White, Color(0xFF2B1A38)))
                    .padding(16.dp),
            ) {
                WatchDescription(streams, actions.onLink, PopPlum, ytDayNight(Color(0xFFE0457F), Color(0xFFF27AA6)), R.font.font_nunito_700, open)
                MoreToggle(open, ytDayNight(Color(0xFFE0457F), Color(0xFFF27AA6)), TextStyle(fontFamily = YtFonts.nunito, fontWeight = FontWeight.Black, fontSize = 13.sp), caps = false, modifier = Modifier.padding(top = 4.dp)) { open = !open }
            }
        }
        if (streams.chapters.isNotEmpty()) WatchChapters(palette, imageLoader, streams, controller.chapterIndex, actions.onSeek)
        WatchUpNextTitle(palette)
    }
}

// ---------------------------------------------------------------------------------------------
// Formen: a night page with material shapes as buttons

@Composable
private fun ShapesPage(palette: YtPalette, imageLoader: ImageLoader, b: WatchBasics, controller: YtWatchController, actions: YtWatchActions) {
    val streams = b.streams
    var tab by remember(streams.title) { mutableStateOf(0) }
    val shapes: List<Shape> = listOf(
        CookieShape(9, 0.07f),
        RoundedCornerShape(50),
        CookieShape(6, 0.1f),
        RoundedCornerShape(16.dp),
        BlobShape(0.55f, 0.45f, 0.5f, 0.6f),
    )
    val colors = listOf(palette.lime, Color(0xFFF6D365), palette.pink, palette.lavender, Color(0xFFF28B82))
    Column(Modifier.fillMaxWidth()) {
        Text(ytTitle(streams.title), color = palette.text, style = TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
        Text(b.meta, color = palette.text.copy(alpha = 0.65f), style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 12.5.sp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
        WatchCreator(palette, imageLoader, streams, b.hasChannel, b.subscribed, b.toggle, actions.onOpenChannel)
        val buttons = listOf(b.like) + b.actions
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, top = 18.dp)
                .fillMaxWidth(),
        ) {
            buttons.forEachIndexed { i, action ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .then(action.gesture)
                            .size(54.dp)
                            .ytPressable(shapes[i % shapes.size]) { action.onClick() }
                            .background(colors[i % colors.size]),
                    ) {
                        Icon(painterResource(action.icon), contentDescription = action.label, tint = palette.ink, modifier = Modifier.size(22.dp))
                    }
                    Text(action.label, color = palette.text.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.googleSans, fontSize = 11.sp), modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        val labels = buildList {
            add(stringResource(R.string.yt_watch_description))
            if (streams.chapters.isNotEmpty()) add(stringResource(R.string.yt_watch_chapters))
            add(stringResource(R.string.yt_watch_comments))
        }
        WatchTabs(
            palette, labels, tab,
            onSelect = { i -> if (i == labels.lastIndex) actions.onComments() else tab = i },
            modifier = Modifier.padding(top = 18.dp),
        )
        if (tab == 0 && streams.description.isNotBlank()) {
            var open by remember(streams.title) { mutableStateOf(false) }
            Column(
                Modifier
                    .padding(start = 13.dp, end = 13.dp, top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0x14FFFFFF))
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(28.dp))
                    .padding(18.dp),
            ) {
                WatchDescription(streams, actions.onLink, palette.text, palette.lime, R.font.font_google_sans_400, open)
                MoreToggle(open, palette.lime, TextStyle(fontFamily = YtFonts.googleSans, fontWeight = FontWeight.Medium, fontSize = 13.sp), caps = false, modifier = Modifier.padding(top = 4.dp)) { open = !open }
            }
        }
        if (tab == 1 && streams.chapters.isNotEmpty()) WatchChapters(palette, imageLoader, streams, controller.chapterIndex, actions.onSeek)
        WatchUpNextTitle(palette)
    }
}

// ---------------------------------------------------------------------------------------------
// Kiesel: a dashboard of pebbles

private val PebbleBeige: Color get() = ytDayNight(Color(0xFFE2DCD5), Color(0xFF4A4541))
private val PebbleCard: Color get() = ytDayNight(Color(0xFFF7F4F1), Color(0xFF2B2826))
private val PebbleGrey: Color get() = ytDayNight(Color(0xFF7A7672), Color(0xFFABA59F))

@Composable
private fun KieselPage(palette: YtPalette, imageLoader: ImageLoader, b: WatchBasics, controller: YtWatchController, actions: YtWatchActions) {
    val streams = b.streams
    var tab by remember(streams.title) { mutableStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        Text(ytTitle(streams.title), color = KieselInk, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, lineHeight = 26.sp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        // three stat pebbles, as on the start page
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 14.dp)) {
            Pebble(streams.views.formatShort(), stringResource(R.string.yt_watch_views_label), KieselLavender, Modifier.weight(1f))
            Pebble(if (streams.likes > 0) streams.likes.formatShort() else "–", stringResource(R.string.yt_watch_likes_label), KieselPeach, Modifier.weight(1f).then(b.like.gesture))
            Pebble(streams.uploaded?.let { ytAgeText(LocalContext.current, it) } ?: "–", stringResource(R.string.yt_watch_uploaded_label), PebbleBeige, Modifier.weight(1f))
        }
        WatchCreator(palette, imageLoader, streams, b.hasChannel, b.subscribed, b.toggle, actions.onOpenChannel)
        // the actions as a grid of capsules: two in a row, nothing cut off
        val colors = listOf(KieselLavender, KieselPeach, PebbleBeige, PebbleCard)
        EvenRows(b.actions, perRow = 2, modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 12.dp)) { action, i, mod ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = mod
                    .then(action.gesture)
                    .ytPressable(RoundedCornerShape(50), squish = true) { action.onClick() }
                    .background(colors[i % colors.size])
                    .padding(vertical = 12.dp, horizontal = 10.dp),
            ) {
                Icon(painterResource(action.icon), contentDescription = null, tint = kieselInkOn(colors[i % colors.size]), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(action.label, color = kieselInkOn(colors[i % colors.size]), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.sp))
            }
        }
        val labels = buildList {
            add(stringResource(R.string.yt_watch_description))
            if (streams.chapters.isNotEmpty()) add(stringResource(R.string.yt_watch_chapters))
            add(stringResource(R.string.yt_watch_comments))
        }
        WatchTabs(
            palette, labels, tab,
            onSelect = { i -> if (i == labels.lastIndex) actions.onComments() else tab = i },
            modifier = Modifier.padding(top = 16.dp),
        )
        if (tab == 0 && streams.description.isNotBlank()) {
            var open by remember(streams.title) { mutableStateOf(false) }
            Column(
                Modifier
                    .padding(start = 13.dp, end = 13.dp, top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(PebbleCard)
                    .padding(18.dp),
            ) {
                WatchDescription(streams, actions.onLink, KieselInk, ytDayNight(Color(0xFF6B5BD2), KieselLavender), R.font.font_jakarta_500, open)
                MoreToggle(open, PebbleGrey, TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Bold, fontSize = 13.sp), caps = false, modifier = Modifier.padding(top = 4.dp)) { open = !open }
            }
        }
        if (tab == 1 && streams.chapters.isNotEmpty()) WatchChapters(palette, imageLoader, streams, controller.chapterIndex, actions.onSeek)
        WatchUpNextTitle(palette)
    }
}

@Composable
private fun Pebble(value: String, label: String, color: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(color)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(value, color = kieselInkOn(color), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp))
        Text(label, color = kieselInkOn(color).copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = YtFonts.jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp), modifier = Modifier.padding(top = 2.dp))
    }
}
