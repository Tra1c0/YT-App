package com.github.libretube.ui.yt.ssi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtBackHandler
import com.github.libretube.ui.yt.YtContrast
import com.github.libretube.ui.yt.YtInk
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtPalettes
import com.github.libretube.ui.yt.YtQueue
import com.github.libretube.ui.yt.YtTheme
import com.github.libretube.ui.yt.archive.YtArchive
import com.github.libretube.ui.yt.archive.YtArchiveUi
import com.github.libretube.ui.yt.feed.YtFeed
import com.github.libretube.ui.yt.palette
import com.github.libretube.ui.yt.rememberYtHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * YT: SSI's screen, a layer of its own over the activity (like the news overlay): the field with
 * voice input, Instant/Deep, what SSI understood (constraint chips), the hits as quotes with the
 * matched words marked, the time ("≈" where it is not word-exact), a precision badge, −30 s /
 * +30 s, "Warum dieser Treffer?" and "Alle als Clip-Folge abspielen". Playing hides the layer;
 * the search stays and comes back when SSI is opened again. Opened from the design search's
 * field ([YtSsiUi.open]). Every text on the cards takes its colour from [YtInk] (Pitfall P1).
 */
object YtSsiUi {
    var shown by mutableStateOf(false)
        private set
    internal var field by mutableStateOf(TextFieldValue(""))
    internal var depth by mutableStateOf(YtSsiDepth.INSTANT)

    /** Extra context per hit (the −30 s / +30 s buttons), by the hit's key. */
    internal val before = mutableStateMapOf<String, Long>()
    internal val after = mutableStateMapOf<String, Long>()
    internal var openWhy by mutableStateOf<String?>(null)

    /** Opens SSI; a non-empty [query] goes into the field and is searched at once (Instant). */
    fun open(context: Context, query: String = "") {
        val text = query.trim()
        if (text.isNotEmpty() && text != field.text) {
            field = TextFieldValue(text, TextRange(text.length))
            search(context, text)
        }
        shown = true
    }

    /** Hides the screen, the search goes on (a hit is playing). */
    fun hide() {
        shown = false
    }

    /** Back: the screen goes and a running search stops. */
    fun close() {
        shown = false
        YtSsiSearch.cancel()
    }

    internal fun search(context: Context, text: String) {
        if (text.isBlank()) return
        before.clear()
        after.clear()
        openWhy = null
        YtSsiSearch.start(context, text, depth)
    }

    internal fun key(hit: YtSsiHit) = "${hit.videoId}:${hit.segmentId ?: "v"}"
}

object YtSsiLayer {
    private const val TAG = "yt_ssi_layer"

    /** Adds the layer to [activity]'s content, once per activity (every design, Standard too). */
    fun attach(activity: ComponentActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) != null) return
        val palette = YtDesign.current().palette ?: YtPalettes.YouTube
        val host = Host(activity).apply {
            tag = TAG
            // above the player container (20 dp) and the search layer (23 dp), below the drag layer (24 dp)
            elevation = 23.2f * resources.displayMetrics.density
            outlineProvider = null
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
        }
        val permission = runCatching {
            activity.activityResultRegistry.register("yt_ssi_mic", ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) YtSsiMic.start?.invoke() else Toast.makeText(activity, R.string.yt_r33_ssi_voice_denied, Toast.LENGTH_SHORT).show()
            }
        }.getOrNull()
        val compose = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                YtTheme(palette) {
                    AnimatedVisibility(
                        visible = YtSsiUi.shown,
                        enter = fadeIn() + slideInVertically { it / 12 },
                        exit = fadeOut() + slideOutVertically { it / 12 },
                    ) {
                        Screen(palette, permission)
                    }
                }
            }
        }
        host.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                permission?.unregister()
                YtSsiMic.start = null
            }
        })
        content.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Takes every touch while SSI shows, none otherwise. */
    private class Host(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (!YtSsiUi.shown) return false
            super.dispatchTouchEvent(ev)
            return true
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = if (YtSsiUi.shown) super.dispatchGenericMotionEvent(event) else false
    }
}

/** The microphone's start, set while the screen shows (the permission answer calls it). */
internal object YtSsiMic {
    var start: (() -> Unit)? = null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Screen(palette: YtPalette, permission: ActivityResultLauncher<String>?) {
    val context = LocalContext.current
    val run by YtSsiSearch.run.collectAsState()
    val haptics = rememberYtHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var voice by remember { mutableStateOf<YtSsiVoiceState>(YtSsiVoiceState.Idle) }
    val mic = remember {
        YtSsiVoice(context, onState = { voice = it }) { text ->
            YtSsiUi.field = TextFieldValue(text, TextRange(text.length))
            YtSsiUi.search(context, text)
        }
    }
    DisposableEffect(mic) {
        YtSsiMic.start = { mic.start() }
        onDispose {
            mic.destroy()
            YtSsiMic.start = null
        }
    }
    LaunchedEffect(Unit) {
        if (YtSsiUi.field.text.isEmpty()) runCatching { focus.requestFocus() }
    }
    YtBackHandler(enabled = YtSsiUi.shown) {
        mic.destroy()
        YtSsiUi.close()
    }
    val cardBack = remember(palette) { YtContrast.over(palette.surface.toArgb(), palette.background.toArgb()) }
    val ink = remember(palette, cardBack) { YtInk.on(palette, cardBack) }
    val accentInk = remember(palette) { YtInk.on(palette, palette.accent.toArgb()) }

    fun submit() {
        val text = YtSsiUi.field.text
        if (text.isBlank()) {
            haptics.reject()
            return
        }
        haptics.confirm()
        keyboard?.hide()
        YtSsiUi.search(context, text)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // the field
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(cardBack))
                .heightIn(min = 52.dp)
                .padding(horizontal = 4.dp),
        ) {
            IconSpot(R.drawable.ic_yt_chevron_left, stringResource(R.string.yt_r33_ssi_back), ink.text) {
                mic.destroy()
                YtSsiUi.close()
            }
            BasicTextField(
                value = YtSsiUi.field,
                onValueChange = { YtSsiUi.field = it },
                textStyle = TextStyle(color = ink.text, fontSize = 17.sp, fontFamily = palette.font),
                cursorBrush = SolidColor(palette.accent),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 12.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (YtSsiUi.field.text.isEmpty()) {
                            val listening = voice as? YtSsiVoiceState.Listening
                            Text(
                                listening?.partial?.ifEmpty { null } ?: stringResource(
                                    when {
                                        listening?.onDevice == true -> R.string.yt_r33_ssi_voice_on_device
                                        listening != null -> R.string.yt_r33_ssi_voice_listening
                                        else -> R.string.yt_r33_ssi_hint
                                    },
                                ),
                                color = ink.textSecondary,
                                fontSize = 17.sp,
                                fontFamily = palette.font,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (YtSsiUi.field.text.isNotEmpty()) {
                IconSpot(R.drawable.ic_close, stringResource(R.string.yt_r33_ssi_clear), ink.textSecondary) {
                    YtSsiUi.field = TextFieldValue("")
                    runCatching { focus.requestFocus() }
                }
            }
            val listening = voice is YtSsiVoiceState.Listening
            IconSpot(R.drawable.ic_yt_ssi_mic, stringResource(R.string.yt_r33_ssi_voice), if (listening) palette.accent else ink.text) {
                haptics.tick()
                if (listening) {
                    mic.stop()
                    return@IconSpot
                }
                if (!mic.available()) {
                    Toast.makeText(context, R.string.yt_r33_ssi_voice_missing, Toast.LENGTH_SHORT).show()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    mic.start()
                } else {
                    permission?.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            IconSpot(R.drawable.ic_yt_search, stringResource(R.string.yt_r33_ssi_search), ink.text) { submit() }
        }
        (voice as? YtSsiVoiceState.Failed)?.let { failed ->
            LaunchedEffect(failed) {
                Toast.makeText(context, context.getString(R.string.yt_r33_ssi_voice_failed, failed.code), Toast.LENGTH_SHORT).show()
                voice = YtSsiVoiceState.Idle
            }
        }

        // Instant / Deep
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        ) {
            for (option in YtSsiDepth.entries) {
                val on = YtSsiUi.depth == option
                Pill(
                    text = stringResource(if (option == YtSsiDepth.INSTANT) R.string.yt_r33_ssi_instant else R.string.yt_r33_ssi_deep),
                    fill = if (on) palette.accent else Color(cardBack),
                    textColor = if (on) accentInk.text else ink.text,
                ) {
                    if (YtSsiUi.depth != option) {
                        haptics.tick()
                        YtSsiUi.depth = option
                        if (YtSsiUi.field.text.isNotBlank() && run.query.isNotEmpty()) YtSsiUi.search(context, YtSsiUi.field.text)
                    }
                }
            }
            Text(
                if (YtSsiUi.depth == YtSsiDepth.INSTANT) stringResource(R.string.yt_r33_ssi_instant_hint)
                else stringResource(R.string.yt_r33_ssi_deep_hint, YtSsi.deepBudget()),
                color = palette.textSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
        }

        Status(run, palette)
        run.intent?.let { Chips(it, palette, Color(cardBack), ink) }

        val hits = run.hits
        if (hits.size > 1) {
            Pill(
                text = stringResource(R.string.yt_r33_ssi_play_all, hits.size),
                fill = palette.accent,
                textColor = accentInk.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                icon = R.drawable.ic_play,
            ) {
                haptics.confirm()
                val before = hits.withIndex().associate { (i, h) -> i to (YtSsiUi.before[YtSsiUi.key(h)] ?: 0L) }
                val after = hits.withIndex().associate { (i, h) -> i to (YtSsiUi.after[YtSsiUi.key(h)] ?: 0L) }
                scope.launch {
                    // a vanished original cannot be played in the service: its clips are left out
                    val clips = withContext(Dispatchers.IO) {
                        YtSsiPlayRules.clips(hits, segments = { hit -> hit.segmentId?.let { YtSsi.db.segment(it) } }, before = before, after = after)
                    }.filter { clip -> hits.none { it.videoId == clip.videoId && it.availability == YtSsiAvailability.VANISHED_ARCHIVED } }
                    if (clips.isNotEmpty()) {
                        YtSsiUi.hide()
                        YtSsiClipModule.start(context, clips)
                    }
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (hits.isEmpty() && run.phase == YtSsiPhase.DONE && run.query.isNotEmpty()) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.yt_r33_ssi_none), color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        if (run.depth == YtSsiDepth.INSTANT) {
                            Text(stringResource(R.string.yt_r33_ssi_none_deep), color = palette.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            itemsIndexed(hits, key = { _, hit -> YtSsiUi.key(hit) }) { _, hit ->
                HitCard(hit, run, palette, cardBack, ink) { play(context, hit) }
            }
        }
    }
}

/** Plays [hit] from its start (a little before, at a sentence); a vanished one from the archive's copy. */
private fun play(context: Context, hit: YtSsiHit) {
    YtSsiClipModule.stop()
    if (hit.availability == YtSsiAvailability.VANISHED_ARCHIVED) {
        kotlinx.coroutines.MainScope().launch {
            val kept = YtArchive.kept(hit.videoId)
            if (kept == null || !YtArchiveUi.playKept(context, hit.videoId, kept)) {
                kept?.meta?.let { YtArchiveUi.showText(context, it) }
            }
        }
        return
    }
    val extra = YtSsiUi.before[YtSsiUi.key(hit)] ?: 0L
    kotlinx.coroutines.MainScope().launch {
        val segment = withContext(Dispatchers.IO) { hit.segmentId?.let { YtSsi.db.segment(it) } }
        val start = (YtSsiPlayRules.start(segment, hit.hitMs) - extra).coerceAtLeast(0)
        YtSsiUi.hide()
        playFrom(context, hit, start)
    }
}

private fun playFrom(context: Context, hit: YtSsiHit, start: Long) {
    val video = StreamItem(
        url = "/watch?v=${hit.videoId}",
        type = StreamItem.TYPE_STREAM,
        title = hit.title,
        uploaderName = hit.channelName,
        thumbnail = "https://i.ytimg.com/vi/${hit.videoId}/mqdefault.jpg",
    )
    YtQueue.playKeepingQueue(context, video, seconds = start / 1000, audio = YtFeed.audioNow())
}

@Composable
private fun Status(run: YtSsiRun, palette: YtPalette) {
    if (run.query.isEmpty()) return
    val phase = when (run.phase) {
        YtSsiPhase.IDLE, YtSsiPhase.LOCAL -> stringResource(R.string.yt_r33_ssi_phase_local)
        YtSsiPhase.AI -> stringResource(R.string.yt_r33_ssi_phase_ai)
        YtSsiPhase.DISCOVER -> stringResource(R.string.yt_r33_ssi_phase_discover)
        YtSsiPhase.FETCH -> stringResource(R.string.yt_r33_ssi_phase_fetch, run.fetched + 1, run.budget.coerceAtLeast(run.fetched + 1))
        YtSsiPhase.DONE -> if (run.depth == YtSsiDepth.DEEP) {
            stringResource(R.string.yt_r33_ssi_phase_done_deep, run.hits.size, run.fetched, run.indexedVideos)
        } else {
            stringResource(R.string.yt_r33_ssi_phase_done, run.hits.size, run.indexedVideos)
        }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(phase, color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (run.phase != YtSsiPhase.DONE) {
                Text(
                    stringResource(R.string.yt_r33_ssi_stop),
                    color = palette.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(role = Role.Button) { YtSsiSearch.cancel() }.padding(6.dp),
                )
            }
        }
        for (note in run.notes.sortedBy { it.ordinal }) {
            val text = when (note) {
                YtSsiRunNote.LEXICAL_ONLY -> R.string.yt_r33_ssi_note_lexical_only
                YtSsiRunNote.SEMANTIC_UNSUPPORTED -> R.string.yt_r33_ssi_note_unsupported
                YtSsiRunNote.VECTORS_PENDING -> R.string.yt_r33_ssi_note_vectors_pending
                YtSsiRunNote.AI_USED -> R.string.yt_r33_ssi_note_ai_used
                YtSsiRunNote.AI_CACHED -> R.string.yt_r33_ssi_note_ai_cached
                YtSsiRunNote.AI_FAILED -> R.string.yt_r33_ssi_note_ai_failed
                YtSsiRunNote.BLOCKED -> R.string.yt_r33_ssi_note_blocked
                YtSsiRunNote.PAUSED -> R.string.yt_r33_ssi_note_paused
                YtSsiRunNote.SEARCH_FAILED -> R.string.yt_r33_ssi_note_failed
                YtSsiRunNote.EMPTY_INDEX -> R.string.yt_r33_ssi_note_empty_index
            }
            Text("· " + stringResource(text), color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(intent: YtSsiIntent, palette: YtPalette, back: Color, ink: YtInk) {
    val chips = buildList {
        if (intent.exact) add(stringResource(R.string.yt_r33_ssi_chip_exact))
        if (intent.kind == YtSsiIntentKind.QUESTION || intent.kind == YtSsiIntentKind.EXPLANATION) add(stringResource(R.string.yt_r33_ssi_chip_question))
        intent.time?.let { add(stringResource(R.string.yt_r33_ssi_chip_time, range(it))) }
        intent.channels.forEach { add(stringResource(R.string.yt_r33_ssi_chip_channel, it)) }
        intent.channelGuesses.forEach { add(stringResource(R.string.yt_r33_ssi_chip_channel_guess, it)) }
        intent.excludeTerms.forEach { add(stringResource(R.string.yt_r33_ssi_chip_without, it)) }
        intent.excludeChannels.forEach { add(stringResource(R.string.yt_r33_ssi_chip_not_channel, it)) }
        intent.language?.let { add(stringResource(R.string.yt_r33_ssi_chip_language, Locale(it).getDisplayLanguage(Locale.getDefault()))) }
    }
    if (chips.isEmpty() && intent.later.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (chip in chips) {
                Text(
                    chip,
                    color = ink.text,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(back).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        if (intent.later.isNotEmpty()) {
            Text(stringResource(R.string.yt_r33_ssi_chip_later), color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun range(range: YtSsiTimeRange): String {
    val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val from = range.fromMs?.let { format.format(Date(it)) }
    val to = range.toMs?.let { format.format(Date(it)) }
    return when {
        from != null && to != null -> "$from – $to"
        from != null -> "≥ $from"
        to != null -> "≤ $to"
        else -> ""
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HitCard(hit: YtSsiHit, run: YtSsiRun, palette: YtPalette, back: Int, ink: YtInk, onPlay: () -> Unit) {
    val key = YtSsiUi.key(hit)
    val extraBefore = YtSsiUi.before[key] ?: 0L
    val extraAfter = YtSsiUi.after[key] ?: 0L
    val haptics = rememberYtHaptics()
    val markBack = remember(palette, back) { Color(YtContrast.over(palette.accent.copy(alpha = 0.30f).toArgb(), back)) }
    val markInk = remember(palette, markBack) { YtInk.on(palette, markBack.toArgb()) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(back))
            .clickable(role = Role.Button) {
                haptics.confirm()
                onPlay()
            }
            .padding(14.dp),
    ) {
        Text(hit.title, color = ink.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = palette.font)
        val meta = listOfNotNull(hit.channelName, hit.uploaded?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, color = ink.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        if (hit.segmentId != null) {
            Text(quote(hit, markBack, markInk.text), color = ink.text, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
        } else {
            Text(stringResource(R.string.yt_r33_ssi_title_match), color = ink.textSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (hit.segmentId != null) {
                Text(YtSsiPlayRules.stamp(hit.hitMs, hit.timing), color = palette.accent.let { ink.read(it) }, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Badge(
                stringResource(
                    when (YtSsiPlayRules.label(hit.timing)) {
                        YtSsiPrecisionLabel.WORD -> R.string.yt_r33_ssi_precision_word
                        YtSsiPrecisionLabel.LINE -> R.string.yt_r33_ssi_precision_line
                        YtSsiPrecisionLabel.SPAN -> R.string.yt_r33_ssi_precision_span
                    },
                ),
                ink,
            )
            if (hit.availability == YtSsiAvailability.VANISHED_ARCHIVED) Badge(stringResource(R.string.yt_r33_ssi_vanished), ink)
            if (hit.segmentId != null) {
                SmallButton(stringResource(R.string.yt_r33_ssi_before), ink) {
                    haptics.tick()
                    YtSsiUi.before[key] = extraBefore + YtSsiPlayRules.CONTEXT_STEP_MS
                }
                SmallButton(stringResource(R.string.yt_r33_ssi_after), ink) {
                    haptics.tick()
                    YtSsiUi.after[key] = extraAfter + YtSsiPlayRules.CONTEXT_STEP_MS
                }
            }
            SmallButton(stringResource(R.string.yt_r33_ssi_why), ink) {
                haptics.tick()
                YtSsiUi.openWhy = if (YtSsiUi.openWhy == key) null else key
            }
        }
        if (hit.segmentId != null && (extraBefore > 0 || extraAfter > 0)) {
            val segment by produceState<YtSsiSegment?>(null, hit.segmentId) {
                value = withContext(Dispatchers.IO) { YtSsi.db.segment(hit.segmentId) }
            }
            val start = (YtSsiPlayRules.start(segment, hit.hitMs) - extraBefore).coerceAtLeast(0)
            val end = maxOf(hit.endMs, hit.hitMs) + YtSsiPlayRules.TAIL_MS + extraAfter
            Text(
                stringResource(R.string.yt_r33_ssi_window, YtSsiPlayRules.stamp(start, YtSsiTiming.WORD), YtSsiPlayRules.stamp(end, YtSsiTiming.WORD)),
                color = ink.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (YtSsiUi.openWhy == key) Why(hit, run, ink)
    }
}

/** The passage with the matched words on a mark. */
private fun quote(hit: YtSsiHit, markBack: Color, markText: Color): AnnotatedString = buildAnnotatedString {
    append(hit.text)
    for (range in hit.highlights) {
        if (range.first < 0 || range.last >= hit.text.length) continue
        addStyle(SpanStyle(background = markBack, color = markText, fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
    }
}

@Composable
private fun Why(hit: YtSsiHit, run: YtSsiRun, ink: YtInk) {
    val intent = run.intent ?: return
    val reasons = remember(hit, intent) { YtSsiPlayRules.reasons(hit, intent, YtSsiRanker.matchedWords(hit)) }
    Column(Modifier.padding(top = 10.dp)) {
        Line(stringResource(R.string.yt_r33_ssi_reason_rank, hit.rank, run.hits.size), ink)
        for (reason in reasons) {
            val text = when (reason) {
                is YtSsiReason.Lexical -> stringResource(R.string.yt_r33_ssi_reason_lexical, reason.rank, reason.words.joinToString(", ").ifEmpty { "–" })
                is YtSsiReason.Semantic -> stringResource(R.string.yt_r33_ssi_reason_semantic, reason.rank, reason.model ?: "–")
                is YtSsiReason.Title -> stringResource(R.string.yt_r33_ssi_reason_title, reason.rank)
                YtSsiReason.Chapter -> stringResource(R.string.yt_r33_ssi_reason_chapter)
                YtSsiReason.ExactPhrase -> stringResource(R.string.yt_r33_ssi_reason_exact)
                YtSsiReason.TimeFilter -> stringResource(R.string.yt_r33_ssi_reason_time)
                YtSsiReason.ChannelFilter -> stringResource(R.string.yt_r33_ssi_reason_channel)
                YtSsiReason.ChannelGuess -> stringResource(R.string.yt_r33_ssi_reason_channel_guess)
                YtSsiReason.LanguageUnknown -> stringResource(R.string.yt_r33_ssi_reason_language_unknown)
                YtSsiReason.Vanished -> stringResource(R.string.yt_r33_ssi_reason_vanished)
                YtSsiReason.AiRewrite -> stringResource(R.string.yt_r33_ssi_reason_ai)
                YtSsiReason.FromYouTubeSearch -> stringResource(R.string.yt_r33_ssi_reason_youtube)
                is YtSsiReason.Later -> stringResource(R.string.yt_r33_ssi_reason_later)
                is YtSsiReason.Timing -> stringResource(
                    when (reason.label) {
                        YtSsiPrecisionLabel.WORD -> R.string.yt_r33_ssi_reason_timing_word
                        YtSsiPrecisionLabel.LINE -> R.string.yt_r33_ssi_reason_timing_line
                        YtSsiPrecisionLabel.SPAN -> R.string.yt_r33_ssi_reason_timing_span
                    },
                )
            }
            Line(text, ink)
        }
    }
}

@Composable
private fun Line(text: String, ink: YtInk) {
    Text("· $text", color = ink.textSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun Badge(text: String, ink: YtInk) {
    Text(
        text,
        color = ink.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.border(1.dp, ink.textSecondary.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun SmallButton(text: String, ink: YtInk, onClick: () -> Unit) {
    Text(
        text,
        color = ink.text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .border(1.dp, ink.text.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun Pill(text: String, fill: Color, textColor: Color, modifier: Modifier = Modifier, icon: Int? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun IconSpot(icon: Int, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
