package com.github.libretube.ui.yt

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.YtChannelPause
import com.github.libretube.helpers.YtHidden
import com.github.libretube.ui.yt.feed.YtPausedChannel
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the pill at the bottom of the home offers to take back. */
enum class YtUndoKind {
    /** A card was swiped away: "Rückgängig" and "Kanal 1 Woche pausieren". */
    HIDDEN,

    /** A channel was paused: "Rückgängig". */
    PAUSED,

    /** A pause was ended: "Rückgängig". */
    RESUMED,
}

/** One offer of the pill; a new one replaces the one shown. */
class YtUndoEntry internal constructor(
    val key: Long,
    val kind: YtUndoKind,
    val video: StreamItem?,
    val pause: YtPausedChannel?,
)

/**
 * YT: the offer to take back what a swipe or a menu just did on the home screens (hidden a
 * video, paused or resumed a channel). Shown by [YtHomePills] for a few seconds.
 */
object YtUndo {
    var entry by mutableStateOf<YtUndoEntry?>(null)
        private set

    private var keys = 0L

    fun hidden(video: StreamItem) {
        entry = YtUndoEntry(++keys, YtUndoKind.HIDDEN, video, null)
    }

    fun paused(pause: YtPausedChannel, video: StreamItem?) {
        entry = YtUndoEntry(++keys, YtUndoKind.PAUSED, video, pause)
    }

    fun resumed(pause: YtPausedChannel) {
        entry = YtUndoEntry(++keys, YtUndoKind.RESUMED, null, pause)
    }

    fun dismiss(shown: YtUndoEntry) {
        if (entry === shown) entry = null
    }

    /** "Rückgängig". */
    fun undo(shown: YtUndoEntry) {
        dismiss(shown)
        when (shown.kind) {
            YtUndoKind.HIDDEN -> shown.video?.url?.toID()?.let { id ->
                YtCardReturns.mark(id)
                YtHidden.unhide(id)
            }
            YtUndoKind.PAUSED -> shown.pause?.let(YtChannelPause::resume)
            YtUndoKind.RESUMED -> shown.pause?.let(YtChannelPause::restore)
        }
    }

    /** "Kanal 1 Woche pausieren" on the pill of a hidden video. */
    fun pauseChannel(shown: YtUndoEntry) {
        val video = shown.video ?: return dismiss(shown)
        val pause = YtChannelPause.pause(video) ?: return dismiss(shown)
        paused(pause, video)
    }
}

/**
 * Pauses the channel of [video] for a week, or ends its pause if it has one; either way the
 * pill offers to take it back.
 */
fun ytTogglePause(video: StreamItem) {
    val running = YtChannelPause.find(video)
    if (running != null) {
        YtChannelPause.resume(running)
        YtUndo.resumed(running)
    } else {
        YtChannelPause.pause(video)?.let { YtUndo.paused(it, video) }
    }
}

/** The same for a channel known by its id or URL ([channel]), [name] and [avatar]. */
fun ytTogglePause(channel: String?, name: String?, avatar: String?) {
    val id = YtChannelPause.channelId(channel)
    val running = YtChannelPause.find(id, name)
    if (running != null) {
        YtChannelPause.resume(running)
        YtUndo.resumed(running)
    } else {
        YtChannelPause.pause(id, name, avatar)?.let { YtUndo.paused(it, null) }
    }
}

/**
 * YT: the pills at the bottom of the home screens, above the navigation (and a docked mini
 * player): the undo pill and the draft queue "N in der Warteschlange · Abspielen". In the
 * design's own surface, type and colors; they spring up from below and can be swiped away.
 * Lay it over the whole screen: it takes touches only on the pills. With [timeBox] (the home
 * only: what it would plan from) the time box chip sits at the end above them.
 */
@Composable
fun YtHomePills(palette: YtPalette, modifier: Modifier = Modifier, timeBox: (() -> YtTimeBoxSource)? = null) {
    val revision by PlayingQueue.revision.collectAsState()
    val hasPlayer = remember(revision) { YtQueue.hasPlayer() }
    // a player started elsewhere (search, a channel) takes the draft into its queue
    LaunchedEffect(hasPlayer) { if (hasPlayer) YtQueue.mergeDraftIntoPlayer() }
    val look = remember(palette) { pillLook(palette) }
    val entry = YtUndo.entry
    val draft = YtQueue.draftShown()
    val enter = ytEnterTransition(slideInVertically(spring(dampingRatio = 0.75f, stiffness = 420f)) { it } + fadeIn(tween(140)))
    val exit = ytExitTransition(slideOutVertically(tween(200, easing = YtEaseOut)) { it / 2 } + fadeOut(tween(160)))

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = ytNavPadding() + 12.dp),
        ) {
            // the intent picked on opening, tap to change; then "Heute habe ich … Minuten", both
            // at the end, above the pills so they never cover them
            if (timeBox != null) YtIntentChip(look, Modifier.align(Alignment.End))
            if (timeBox != null) YtTimeBoxChip(look, timeBox, Modifier.align(Alignment.End))
            // the last entry stays while the pill slides away
            val shown = remember { arrayOfNulls<YtUndoEntry>(1) }
            entry?.let { shown[0] = it }
            AnimatedVisibility(visible = entry != null, enter = enter, exit = exit) {
                shown[0]?.let { UndoPill(look, it) }
            }
            AnimatedVisibility(visible = draft > 0 && !hasPlayer, enter = enter, exit = exit) {
                DraftPill(look, draft.coerceAtLeast(1))
            }
        }
    }
}

/**
 * [YtHomePills] over a screen that has its theme further down: the design's scaled density and
 * motion, without a second YtTheme (which would start another tilt sensor for the glass designs).
 */
@Composable
fun YtPillLayer(palette: YtPalette, timeBox: (() -> YtTimeBoxSource)? = null) {
    val density = ytDensity(LocalDensity.current, LocalConfiguration.current)
    val motion = rememberYtMotionAllowed()
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalYtMotion provides motion,
        LocalYtMotionStyle provides palette.motion,
    ) {
        YtHomePills(palette, timeBox = timeBox)
    }
}

@Composable
private fun UndoPill(look: PillLook, entry: YtUndoEntry) {
    val accessibility = LocalAccessibilityManager.current
    val timeout = remember(accessibility) {
        accessibility?.calculateRecommendedTimeoutMillis(5000L, containsIcons = true, containsText = true, containsControls = true) ?: 5000L
    }
    LaunchedEffect(entry.key) {
        delay(timeout)
        YtUndo.dismiss(entry)
    }
    val icon = if (entry.kind == YtUndoKind.HIDDEN) R.drawable.ic_yt_hide else R.drawable.ic_yt_pause_week
    val name = entry.pause?.name?.takeIf { it.isNotBlank() } ?: entry.video?.uploaderName.orEmpty()
    val message = when (entry.kind) {
        YtUndoKind.HIDDEN -> null
        YtUndoKind.PAUSED -> stringResource(R.string.yt_home_pause_done, name)
        YtUndoKind.RESUMED -> stringResource(R.string.yt_home_pause_ended, name)
    }
    val spoken = message ?: stringResource(R.string.yt_home_hidden_done)
    SwipeAway(onGone = { YtUndo.dismiss(entry) }) {
        PillRow(look, Modifier.testTag("yt_undo_pill")) {
            Icon(
                painterResource(icon),
                contentDescription = spoken,
                tint = look.text,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .size(18.dp),
            )
            if (message != null) {
                Text(
                    text = message,
                    color = look.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = look.style(FontWeight.Medium),
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .widthIn(max = 200.dp),
                )
                Dot(look)
            }
            PillAction(look, stringResource(R.string.yt_home_undo), "yt_undo_action", first = message == null) { YtUndo.undo(entry) }
            if (entry.kind == YtUndoKind.HIDDEN && entry.video?.uploaderName != null) {
                Dot(look)
                PillAction(look, stringResource(R.string.yt_home_pause_week), "yt_pause_action") { YtUndo.pauseChannel(entry) }
            }
        }
    }
}

@Composable
private fun DraftPill(look: PillLook, count: Int) {
    val context = LocalContext.current
    val motion = LocalYtMotion.current
    // each card that lands makes the pill take it in
    val gulp = remember { Animatable(0f) }
    val landed = YtQueue.draftLanded
    val seen = remember { intArrayOf(landed) }
    LaunchedEffect(landed) {
        if (landed == seen[0]) return@LaunchedEffect
        seen[0] = landed
        if (motion) {
            gulp.snapTo(1f)
            gulp.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 520f))
        }
    }
    DisposableEffect(Unit) { onDispose { YtQueue.draftPillBounds = null } }
    SwipeAway(onGone = { YtQueue.clearDraft() }) {
        PillRow(
            look,
            Modifier
                .testTag("yt_draft_pill")
                .onGloballyPositioned { YtQueue.draftPillBounds = it.boundsInWindow() }
                .graphicsLayer {
                    val s = 1f + 0.08f * gulp.value
                    scaleX = s
                    scaleY = s
                },
        ) {
            Icon(painterResource(R.drawable.ic_queue), contentDescription = null, tint = look.text, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.yt_home_draft_count, count),
                color = look.text,
                maxLines = 1,
                style = look.style(FontWeight.Medium),
                modifier = Modifier
                    .padding(start = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Dot(look)
            PillAction(look, stringResource(R.string.yt_home_draft_play), "yt_draft_play") { YtQueue.playDraft(context) }
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.yt_home_draft_discard),
                tint = look.text.copy(alpha = 0.7f),
                modifier = Modifier
                    .testTag("yt_draft_discard")
                    .padding(start = 2.dp)
                    .clip(CircleShape)
                    .clickable { YtQueue.clearDraft() }
                    .padding(10.dp)
                    .size(16.dp),
            )
        }
    }
}

/** A pill that follows a sideways swipe and, thrown far enough, flies off and is gone. */
@Composable
internal fun SwipeAway(onGone: () -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberYtSwipe()
    val haptics = rememberYtHaptics()
    val scope = rememberCoroutineScope()
    val motion = LocalYtMotion.current
    var width by remember { mutableStateOf(0) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    Box(
        Modifier
            .onSizeChanged { width = it.width }
            .graphicsLayer {
                translationX = swipe.offset.value
                alpha = 1f - (kotlin.math.abs(swipe.offset.value) / (width.coerceAtLeast(1) * 1.2f)).coerceIn(0f, 0.9f)
            }
            .ytSwipe(swipe, threshold, direction = 0, haptics = haptics, settle = false) { way ->
                scope.launch {
                    if (motion) swipe.offset.animateTo(way * width * 1.4f, spring(dampingRatio = 1f, stiffness = 500f))
                    onGone()
                    swipe.offset.snapTo(0f)
                }
            },
    ) { content() }
}

@Composable
internal fun PillRow(look: PillLook, modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(look.surface(Modifier))
            .padding(start = 16.dp, end = 6.dp),
    ) { content() }
}

@Composable
internal fun PillAction(look: PillLook, label: String, tag: String, first: Boolean = false, onClick: () -> Unit) {
    val text = if (look.uppercase) label.uppercase() else label
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .testTag(tag)
            .padding(start = if (first) 6.dp else 0.dp)
            .heightIn(min = 44.dp)
            .clip(look.actionShape)
            .clickable(onClick = onClick)
            .then(if (look.actionFill != null) Modifier.padding(vertical = 6.dp) else Modifier),
    ) {
        Text(
            text = text,
            color = look.action,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = look.style(FontWeight.Bold).copy(textDecoration = look.actionDecoration),
            modifier = Modifier
                .then(
                    if (look.actionFill != null) {
                        Modifier
                            .clip(look.actionShape)
                            .background(look.actionFill)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    } else {
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    },
                ),
        )
    }
}

@Composable
internal fun Dot(look: PillLook) {
    Text(text = "·", color = look.text.copy(alpha = 0.6f), style = look.style(FontWeight.Bold))
}

/** How a design draws its pills. */
internal class PillLook(
    val surface: (Modifier) -> Modifier,
    val text: Color,
    val action: Color,
    val font: FontFamily,
    val size: TextUnit = 13.5.sp,
    val uppercase: Boolean = false,
    val letterSpacing: TextUnit = 0.sp,
    val actionShape: Shape = RoundedCornerShape(50),
    /** Kiesel: the actions are capsules of their own. */
    val actionFill: Color? = null,
    val actionDecoration: TextDecoration? = null,
    /** A bigger card in the pill's look (the time box), where the pill's own shape does not fit. */
    val cardSurface: ((Modifier) -> Modifier)? = null,
) {
    /** The surface of a card: [cardSurface], else the pill's. */
    fun card(modifier: Modifier): Modifier = (cardSurface ?: surface)(modifier)

    fun style(weight: FontWeight) = TextStyle(fontFamily = font, fontWeight = weight, fontSize = size, letterSpacing = letterSpacing)
}

internal fun pillLook(palette: YtPalette): PillLook {
    fun flat(shape: Shape, color: Color, shadow: Color = Color(0x66000000)) = { m: Modifier ->
        m.shadow(10.dp, shape, ambientColor = shadow, spotColor = shadow).clip(shape).background(color)
    }
    return when (palette.signature) {
        YtSignature.YOUTUBE -> PillLook(
            surface = flat(RoundedCornerShape(14.dp), palette.raised),
            text = palette.text, action = palette.text, font = YtFonts.geist,
        )
        YtSignature.MOVO -> PillLook(
            surface = flat(RoundedCornerShape(50), Color.White),
            text = palette.ink, action = palette.accent, font = YtFonts.geist,
            cardSurface = flat(RoundedCornerShape(24.dp), Color.White),
        )
        YtSignature.AFTER -> PillLook(
            surface = { m ->
                val shape = RoundedCornerShape(10.dp)
                flat(shape, palette.raised)(m).border(1.dp, palette.accent.copy(alpha = 0.55f), shape)
            },
            text = palette.text, action = palette.accent, font = YtFonts.archivoNarrow,
            uppercase = true, letterSpacing = 1.sp,
        )
        YtSignature.SIDE -> PillLook(
            surface = flat(RoundedCornerShape(50), palette.raised),
            text = palette.text, action = palette.accent, font = YtFonts.figtree,
            cardSurface = flat(RoundedCornerShape(24.dp), palette.raised),
        )
        YtSignature.CLAY -> PillLook(
            surface = { m -> m.ytSurface(palette, RoundedCornerShape(24.dp)) },
            text = palette.text, action = palette.accent, font = YtFonts.nunito,
        )
        YtSignature.EDITORIAL -> PillLook(
            surface = flat(RectangleShape, Color.Black, Color(0x40000000)),
            text = Color.White, action = Color.White, font = YtFonts.plexMono,
            size = 11.sp, uppercase = true, letterSpacing = 1.sp,
            actionShape = RectangleShape, actionDecoration = TextDecoration.Underline,
        )
        YtSignature.GLASS -> PillLook(
            surface = { m -> m.ytGlass(RoundedCornerShape(50), strong = true) },
            text = palette.text, action = Color(0xFF0A8F7F), font = YtFonts.googleSans,
            cardSurface = { m -> m.ytGlass(RoundedCornerShape(26.dp), strong = true) },
        )
        YtSignature.POP -> PillLook(
            surface = flat(BlobShape(0.56f, 0.5f, 0.5f, 0.6f), palette.accent, Color(0x553B2054)),
            text = Color.White, action = palette.lime, font = YtFonts.nunito, size = 14.sp,
            cardSurface = flat(RoundedCornerShape(30.dp), palette.accent, Color(0x553B2054)),
        )
        YtSignature.SHAPES -> PillLook(
            surface = flat(RoundedCornerShape(50), palette.surface),
            text = palette.ink, action = Color(0xFF6656C4), font = YtFonts.googleSans,
            cardSurface = flat(RoundedCornerShape(28.dp), palette.surface),
        )
        YtSignature.FROST -> PillLook(
            surface = flat(RoundedCornerShape(50), palette.ink, Color(0x661F2A26)),
            text = palette.text, action = palette.accent, font = YtFonts.jakarta,
            cardSurface = flat(RoundedCornerShape(24.dp), palette.ink, Color(0x661F2A26)),
        )
        YtSignature.KIESEL -> PillLook(
            surface = flat(RoundedCornerShape(50), KieselInk, Color(0x400E0E0E)),
            text = KieselCream, action = KieselInk, font = YtFonts.jakarta, size = 12.5.sp,
            actionFill = KieselLavender,
            cardSurface = flat(RoundedCornerShape(26.dp), KieselInk, Color(0x400E0E0E)),
        )
    }
}

/** For the icons of rows that pause: whether [video]'s channel is paused right now, and the days left. */
@Composable
fun rememberPauseOf(video: StreamItem?): YtPausedChannel? {
    val paused by YtChannelPause.paused.collectAsState()
    return remember(paused, video) { video?.let { YtChannelPause.find(it) } }
}
