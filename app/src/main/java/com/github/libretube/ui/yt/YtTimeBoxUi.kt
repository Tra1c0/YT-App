package com.github.libretube.ui.yt

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtChannelPause
import com.github.libretube.helpers.YtHidden
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.services.YtSessionModule
import com.github.libretube.ui.yt.feed.YtBoxCandidate
import com.github.libretube.ui.yt.feed.YtBoxItem
import com.github.libretube.ui.yt.feed.YtBoxPlan
import com.github.libretube.ui.yt.feed.YtSession
import com.github.libretube.ui.yt.feed.YtSessionState
import com.github.libretube.ui.yt.feed.YtTimeBox
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sign

/** What the time box plans from: the home's continue-watching row, then its videos in rank order. */
data class YtTimeBoxSource(val continueWatching: List<StreamItem>?, val ranked: List<StreamItem>)

/** What is left of the time box; [rememberYtTimeLeft] keeps it current. */
data class YtTimeLeft(val remainingMs: Long, val fraction: Float, val done: Boolean, val minutes: Int, val videos: Int) {
    /** Whole minutes left, rounded up, so it reads 1 until the time is up. */
    val minutesLeft: Int get() = ((remainingMs + 59_999) / 60_000).toInt()
}

/**
 * The running time box for a chip or the mini player, or null without one. While a video plays
 * it updates each time a second of the time runs out; paused, it stays as it is.
 */
@Composable
fun rememberYtTimeLeft(): YtTimeLeft? {
    val state by YtSession.state.collectAsState()
    val left by produceState<YtTimeLeft?>(null, state) {
        val session = state
        if (session == null) {
            value = null
            return@produceState
        }
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val remaining = session.remainingMs(now)
            value = YtTimeLeft(remaining, session.fraction(now), session.done, session.minutes, session.items.size)
            if (session.sinceMs < 0 || session.done || remaining <= 0) break
            val toNextSecond = remaining % 1000
            delay(if (toNextSecond == 0L) 1000 else toNextSecond)
        }
    }
    return left
}

/** YT: the settings of the time box. */
object YtTimeBoxPrefs {
    const val CHIP = "yt_timebox_chip"
    private const val CUSTOM = "yt_timebox_custom"

    val chip: Boolean get() = PreferenceHelper.getBoolean(CHIP, true)

    fun lastCustom(): Int = YtTimeBox.clampMinutes(PreferenceHelper.getInt(CUSTOM, 20))

    fun saveCustom(minutes: Int) = PreferenceHelper.putInt(CUSTOM, YtTimeBox.clampMinutes(minutes))
}

/**
 * YT: starts the time box ("Heute habe ich … Minuten"): plans from what the home shows, puts the
 * plan into the queue and plays its first video. A long video that would fill the rest is looked
 * up in the background (its chapters) and added with its end point once found, so the start
 * never waits for it.
 */
object YtTimeBoxStarter {
    private const val MAX_CONTINUE = 8
    private const val MAX_RANKED = 60
    private const val CHAPTER_TIMEOUT_MS = 12_000L

    private val scope = MainScope()
    private var job: Job? = null

    /** The videos the running time box may play, by id ("Noch ein Video" queues from them). */
    private val videos = HashMap<String, StreamItem>()

    /** True while a plan is being made (reading the watch positions). */
    var planning by mutableStateOf(false)
        private set

    /** The default playback speed: the plan holds more video at a higher one. */
    fun speed(): Float =
        PreferenceHelper.getString(PreferenceKeys.PLAYBACK_SPEED, "1").replace("F", "")
            .toFloatOrNull()?.takeIf { it > 0f }?.coerceIn(0.25f, 4f) ?: 1f

    /** Plans [minutes] from [source] and starts; [onEmpty] when nothing could be planned. */
    fun start(context: Context, minutes: Int, source: YtTimeBoxSource, onEmpty: () -> Unit) {
        job?.cancel()
        planning = true
        job = scope.launch {
            try {
                val found = HashMap<String, StreamItem>()
                val candidates = withContext(Dispatchers.IO) { candidates(source, found) }
                val speed = speed()
                val budget = YtTimeBox.budgetSec(minutes, speed)
                val plan = YtTimeBox.plan(candidates, budget)
                if (plan.items.isEmpty()) {
                    YtTestHooks.log("YtTimeBox") { "nothing to plan minutes=$minutes candidates=${candidates.size}" }
                    onEmpty()
                    return@launch
                }
                videos.clear()
                videos.putAll(found)
                val session = YtSession.start(minutes, speed, plan)
                YtTestHooks.log("YtTimeBox") {
                    "plan minutes=$minutes speed=$speed n=${plan.items.size} total=${plan.totalSec}s " +
                        "total<=${YtTimeBox.capMinutes(budget, speed)} cuts=${plan.cutCandidates.size}"
                }
                PlayingQueue.setStreams(plan.items.mapNotNull { found[it.id] })
                val first = plan.items.first()
                NavigationHelper.navigateVideo(context, PlayerData(first.id, keepQueue = true, timestamp = first.startSec))
                if (plan.cutCandidates.isNotEmpty()) findCut(session.key, plan)
            } finally {
                planning = false
            }
        }
    }

    private suspend fun candidates(source: YtTimeBoxSource, found: MutableMap<String, StreamItem>): List<YtBoxCandidate> {
        val hidden = YtHidden.hidden.value
        val paused = YtChannelPause.paused.value
        val now = System.currentTimeMillis()
        val result = ArrayList<YtBoxCandidate>()
        fun add(video: StreamItem, startSec: Long) {
            val id = video.url?.toID() ?: return
            if (id in found) return
            found[id] = video
            result += YtBoxCandidate(id, video.duration ?: 0L, startSec, video.isLive, video.isShort, video.isUpcoming)
        }
        // what the user began goes on first, from where it stopped
        ytVisible(source.continueWatching, hidden, paused, now).orEmpty().take(MAX_CONTINUE).forEach { video ->
            val id = video.url?.toID() ?: return@forEach
            add(video, (DatabaseHelper.getWatchPosition(id) ?: 0L) / 1000)
        }
        ytVisible(source.ranked, hidden, paused, now).orEmpty().take(MAX_RANKED).forEach { add(it, 0L) }
        return result
    }

    /** Fetches the chapters of at most two long videos and adds the best cut to the running time box. */
    private fun findCut(key: Long, plan: YtBoxPlan) = scope.launch {
        val cuts = plan.cutCandidates.map { candidate ->
            async(Dispatchers.IO) {
                val starts = withTimeoutOrNull(CHAPTER_TIMEOUT_MS) {
                    runCatching {
                        MediaServiceRepository.instance.getStreams(candidate.id).chapters.map { it.start }
                    }.getOrNull()
                }
                starts?.let { YtTimeBox.cut(plan, candidate, it) }
            }
        }.awaitAll()
        val cut = YtTimeBox.bestCut(plan, cuts)
        val video = cut?.let { videos[it.id] }
        if (cut == null || video == null) {
            YtTestHooks.log("YtTimeBox") { "no chapter cut" }
            return@launch
        }
        val added = YtSession.updateIf(key) { state ->
            if (state.done) state else state.copy(items = state.items.filter { it.id != cut.id } + cut, spare = state.spare.filter { it.id != cut.id })
        }
        if (!added || YtSession.state.value?.lastId != cut.id) return@launch
        PlayingQueue.ytAppend(video)
        YtSessionModule.rearm()
        YtTestHooks.log("YtTimeBox") { "cut id=${cut.id} end=${cut.endSec}s seconds=${cut.seconds}" }
    }

    /**
     * What "Noch ein Video" would play: the next video the user queued, else the best spare one
     * of the plan. Null when there is none.
     */
    fun nextFor(state: YtSessionState): Pair<StreamItem, YtBoxItem>? {
        val streams = PlayingQueue.getStreams()
        val queued = streams.drop(PlayingQueue.currentIndex() + 1)
            .firstOrNull { (it.duration ?: 0L) > 0L && it.url?.toID() != null }
        if (queued != null) return queued to YtBoxItem(queued.url!!.toID(), 0, null, queued.duration ?: 0L)
        val planned = state.items.mapTo(HashSet()) { it.id }
        val spare = YtTimeBox.oneMore(state.spare, planned) ?: return null
        val video = videos[spare.id] ?: return null
        return video to YtBoxItem(spare.id, spare.startSec, null, spare.remainingSec)
    }

    /** "Noch ein Video": one more video, and the time grows by its length. */
    fun oneMore(context: Context) {
        val state = YtSession.state.value ?: return
        val (video, item) = nextFor(state) ?: return
        YtSession.update { it.plusOne(item) }
        PlayingQueue.ytInsertNext(video)
        YtTestHooks.log("YtTimeBox") { "one more id=${item.id} seconds=${item.seconds}" }
        NavigationHelper.navigateVideo(context, PlayerData(item.id, keepQueue = true, timestamp = item.startSec))
    }

    /** "Weiterschauen": the video that stopped at its end point plays on to its end. */
    fun watchToEnd() {
        val state = YtSession.state.value ?: return
        val id = state.stoppedAt ?: return
        val duration = videos[id]?.duration ?: PlayingQueue.getCurrent()?.duration ?: return
        YtSession.update { it.watchToEnd(id, duration) }
        YtSessionModule.rearm()
        YtSessionModule.resume()
        YtTestHooks.log("YtTimeBox") { "watch to end id=$id" }
    }

    /** Ends the time box; what plays goes on as usual. */
    fun end() {
        job?.cancel()
        YtSession.end()
        YtTestHooks.log("YtTimeBox") { "ended" }
    }
}

// ---------------------------------------------------------------------------------------------
// The minutes row

/** A minutes count as the row shows it, "30 Min.". */
@Composable
private fun minutesLabel(minutes: Int): String = stringResource(R.string.yt_timebox_minutes, minutes)

/**
 * "Heute habe ich … Minuten": 15, 30, 45, 60, 90 and "Eigene" (a stepper in steps of five), in
 * the design's look. The home's time box chip shows it; the intent chooser can show it too.
 */
@Composable
fun YtMinutesRow(palette: YtPalette, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val look = remember(palette) { pillLook(palette) }
    MinutesGrid(look, onPick, modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MinutesGrid(look: PillLook, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    var custom by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberYtHaptics()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!custom) {
            // a flow, not two fixed rows of three: with a large font the buttons grow with
            // their words and move to the next line instead of being cut at the edge
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                YtTimeBox.MINUTES.forEach { minutes ->
                    MinuteButton(look, minutesLabel(minutes), "yt_timebox_$minutes") {
                        haptics.confirm()
                        onPick(minutes)
                    }
                }
                MinuteButton(look, stringResource(R.string.yt_timebox_custom), "yt_timebox_custom") {
                    haptics.tick()
                    custom = true
                }
            }
        } else {
            Stepper(look, onBack = { custom = false }) { minutes ->
                haptics.confirm()
                YtTimeBoxPrefs.saveCustom(minutes)
                onPick(minutes)
            }
        }
    }
}

@Composable
private fun MinuteButton(look: PillLook, label: String, tag: String, width: Dp = 76.dp, onClick: () -> Unit) {
    val fill = look.actionFill ?: look.action.copy(alpha = 0.14f)
    // on a card of known colour the words are made to read on the fill over it (4.5:1)
    val ink = if (look.tone != Color.Unspecified) look.action.readableOn(fill.compositeOver(look.tone)) else look.action
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .testTag(tag)
            // at least the design's size, larger when the words need it (large font scale)
            .widthIn(min = width)
            .heightIn(min = 44.dp)
            .ytPressable(look.actionShape, onClick = onClick)
            .background(fill)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = if (look.uppercase) label.uppercase() else label,
            color = ink,
            maxLines = 1,
            style = look.style(FontWeight.Bold).copy(textDecoration = look.actionDecoration),
        )
    }
}

/**
 * "Eigene": the minutes in steps of five, with − and + or a sideways drag on the number, which
 * follows the finger and gives a detent at each step (rubber band at the ends).
 */
@Composable
private fun Stepper(look: PillLook, onBack: () -> Unit, onGo: (Int) -> Unit) {
    var minutes by rememberSaveable { mutableIntStateOf(YtTimeBoxPrefs.lastCustom()) }
    val haptics = rememberYtHaptics()
    val motion = LocalYtMotion.current
    val scope = rememberCoroutineScope()
    val nudge = remember { Animatable(0f) }
    val stepPx = with(LocalDensity.current) { 28.dp.toPx() }

    fun step(by: Int): Boolean {
        val next = YtTimeBox.clampMinutes(minutes + by * YtTimeBox.STEP_MINUTES)
        if (next == minutes) return false
        minutes = next
        return true
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundButton(look, R.drawable.ic_remove, stringResource(R.string.yt_timebox_less), "yt_timebox_less") {
            if (step(-1)) haptics.detent() else haptics.reject()
        }
        val spoken = minutesLabel(minutes)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .widthIn(min = 84.dp)
                .heightIn(min = 44.dp)
                .semantics { contentDescription = spoken }
                .pointerInput(stepPx) {
                    var travel = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travel = 0f },
                        onDragEnd = {
                            travel = 0f
                            scope.launch { if (motion) nudge.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) else nudge.snapTo(0f) }
                        },
                        onDragCancel = {
                            travel = 0f
                            scope.launch { nudge.snapTo(0f) }
                        },
                    ) { pointer, dragAmount ->
                        pointer.consume()
                        travel += dragAmount
                        val steps = (travel / stepPx).toInt()
                        if (steps != 0) {
                            if (step(steps)) {
                                haptics.detent()
                                travel -= steps * stepPx
                            } else {
                                // at an end: hold the travel so the number pulls like a rubber band
                                travel = travel.coerceIn(-3 * stepPx, 3 * stepPx)
                            }
                        }
                        val pull = stepPx * 0.6f * (1f - 1f / (1f + abs(travel) / stepPx))
                        scope.launch { nudge.snapTo(sign(travel) * pull) }
                    }
                },
        ) {
            Text(
                text = spoken,
                color = look.text,
                maxLines = 1,
                style = look.style(FontWeight.Bold).copy(fontSize = look.size * 1.25f),
                modifier = Modifier.graphicsLayer { translationX = nudge.value },
            )
        }
        RoundButton(look, R.drawable.ic_add, stringResource(R.string.yt_timebox_more), "yt_timebox_more") {
            if (step(1)) haptics.detent() else haptics.reject()
        }
        MinuteButton(look, stringResource(R.string.yt_timebox_go), "yt_timebox_go", width = 60.dp) { onGo(minutes) }
    }
    Row(Modifier.padding(top = 2.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .testTag("yt_timebox_back")
                .heightIn(min = 36.dp)
                .ytPressable(look.actionShape, onClick = onBack)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.yt_timebox_back).let { if (look.uppercase) it.uppercase() else it },
                color = look.text.copy(alpha = 0.7f),
                style = look.style(FontWeight.Medium),
            )
        }
    }
}

@Composable
private fun RoundButton(look: PillLook, icon: Int, description: String, tag: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .testTag(tag)
            .size(44.dp)
            .ytPressable(CircleShape, onClick = onClick)
            .background(look.actionFill ?: look.action.copy(alpha = 0.14f))
            .semantics { role = Role.Button },
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = look.action, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// The chip on the home

/**
 * The time box on the home: a small button in the pill look; tapped, it opens into the minutes
 * row. While a time box runs it shows a ring with what is left, tapped it offers to end it.
 * Hidden once the time is up (the end card speaks then) and when switched off in the settings.
 */
@Composable
internal fun YtTimeBoxChip(look: PillLook, source: () -> YtTimeBoxSource, modifier: Modifier = Modifier) {
    if (!remember { YtTimeBoxPrefs.chip }) return
    val left = rememberYtTimeLeft()
    if (left?.done == true) return
    val context = LocalContext.current
    val motion = LocalYtMotion.current
    val haptics = rememberYtHaptics()
    var open by rememberSaveable { mutableStateOf(false) }
    var empty by remember { mutableStateOf(false) }
    val planning = YtTimeBoxStarter.planning

    AnimatedContent(
        targetState = open,
        modifier = modifier,
        contentAlignment = Alignment.BottomEnd,
        transitionSpec = {
            if (!motion) {
                EnterTransition.None togetherWith ExitTransition.None using null
            } else {
                val grow = scaleIn(spring<Float>(dampingRatio = 0.78f, stiffness = 520f), initialScale = 0.88f, transformOrigin = TransformOrigin(1f, 1f))
                (fadeIn(tween(140)) + grow) togetherWith fadeOut(tween(90)) using
                    SizeTransform(clip = false) { _, _ -> spring<IntSize>(dampingRatio = 0.82f, stiffness = 520f) }
            }
        },
        label = "timeBox",
    ) { expanded ->
        when {
            !expanded -> ChipButton(look, left) {
                haptics.tick()
                empty = false
                open = true
            }
            left == null -> PickCard(look, planning, empty, onClose = { open = false }) { minutes ->
                empty = false
                YtTimeBoxStarter.start(context, minutes, source(), onEmpty = { empty = true })
                // the card closes once the time box runs (left turns non-null); a failed plan keeps it open
            }
            else -> RunningCard(look, left, onClose = { open = false }) {
                YtTimeBoxStarter.end()
                open = false
            }
        }
    }
    // a time box that started closes the card
    val running = left != null
    LaunchedEffect(running) { if (running) open = false }
}

@Composable
private fun ChipButton(look: PillLook, left: YtTimeLeft?, onClick: () -> Unit) {
    val description = if (left == null) {
        stringResource(R.string.yt_timebox_title)
    } else {
        stringResource(R.string.yt_timebox_running, left.minutesLeft)
    }
    // the press dips the whole chip, its shadow too
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .testTag("yt_timebox_chip")
            .ytPressScale(interaction)
            .then(look.surface(Modifier))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = if (left == null) 13.dp else 14.dp, vertical = 13.dp),
    ) {
        if (left == null) {
            Icon(painterResource(R.drawable.ic_yt_timer), contentDescription = null, tint = look.text, modifier = Modifier.size(22.dp))
        } else {
            Ring(look, left.fraction, Modifier.size(22.dp))
            Text(
                text = stringResource(R.string.yt_timebox_left, left.minutesLeft),
                color = look.text,
                maxLines = 1,
                style = look.style(FontWeight.Medium),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** What is left as a ring that closes as the time runs out. */
@Composable
private fun Ring(look: PillLook, fraction: Float, modifier: Modifier) {
    val motion = LocalYtMotion.current
    val spec: AnimationSpec<Float> = if (motion) spring(dampingRatio = 1f, stiffness = 120f) else snap()
    val sweep by animateFloatAsState(fraction, spec, label = "timeRing")
    val track = look.text.copy(alpha = 0.22f)
    val arc = look.actionFill ?: look.action
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2
        drawCircle(track, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
        drawArc(
            color = arc,
            startAngle = -90f,
            sweepAngle = 360f * sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun CardHeader(look: PillLook, title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (look.uppercase) title.uppercase() else title,
            color = look.text,
            maxLines = 1,
            style = look.style(FontWeight.Bold),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            painterResource(R.drawable.ic_close),
            contentDescription = stringResource(R.string.yt_timebox_close),
            tint = look.text.copy(alpha = 0.7f),
            modifier = Modifier
                .testTag("yt_timebox_close")
                .ytPressable(CircleShape, onClick = onClose)
                .padding(12.dp)
                .size(16.dp),
        )
    }
}

@Composable
private fun PickCard(look: PillLook, planning: Boolean, empty: Boolean, onClose: () -> Unit, onPick: (Int) -> Unit) {
    Column(
        Modifier
            .testTag("yt_timebox_card")
            .then(look.card(Modifier))
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 14.dp),
    ) {
        CardHeader(look, stringResource(R.string.yt_timebox_title), onClose)
        val note = when {
            planning -> stringResource(R.string.yt_timebox_planning)
            empty -> stringResource(R.string.yt_timebox_nothing)
            else -> null
        }
        if (note != null) {
            Text(
                text = note,
                color = look.text.copy(alpha = 0.75f),
                style = look.style(FontWeight.Normal),
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        MinutesGrid(look, onPick, Modifier.padding(end = 12.dp))
    }
}

@Composable
private fun RunningCard(look: PillLook, left: YtTimeLeft, onClose: () -> Unit, onEnd: () -> Unit) {
    Column(
        Modifier
            .testTag("yt_timebox_card")
            .then(look.card(Modifier))
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 12.dp),
    ) {
        CardHeader(look, stringResource(R.string.yt_timebox_title_short, left.minutes), onClose)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
            Ring(look, left.fraction, Modifier.size(30.dp))
            Column(Modifier.padding(start = 12.dp, end = 16.dp)) {
                Text(
                    text = stringResource(R.string.yt_timebox_left, left.minutesLeft),
                    color = look.text,
                    style = look.style(FontWeight.Bold),
                )
                Text(
                    text = pluralStringResource(R.plurals.yt_timebox_videos, left.videos, left.videos),
                    color = look.text.copy(alpha = 0.7f),
                    style = look.style(FontWeight.Normal),
                )
            }
            MinuteButton(look, stringResource(R.string.yt_timebox_end), "yt_timebox_end", width = 88.dp, onClick = onEnd)
        }
    }
}
