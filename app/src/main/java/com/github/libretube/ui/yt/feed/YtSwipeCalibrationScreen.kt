package com.github.libretube.ui.yt.feed

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.helpers.ContextHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.ui.yt.YtInk
import com.github.libretube.ui.yt.YtPalette
import com.github.libretube.ui.yt.YtPalettes
import com.github.libretube.ui.yt.YtTheme
import com.github.libretube.ui.yt.palette
import com.github.libretube.ui.yt.rememberYtHaptics
import com.github.libretube.ui.yt.ytCardShape
import com.github.libretube.ui.yt.ytPillShape
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * YT: "Wischgesten kalibrieren", laid over the settings: the user scrolls a test list a few
 * times as usual, then swipes a test card a few times on purpose. The paths are recorded
 * without taking the touches (the list keeps scrolling), [YtSwipeCalibration] proposes values,
 * and they are applied only on "Übernehmen".
 */
object YtSwipeCalibrationScreen {
    private const val TAG = "yt_swipe_calibration"

    /** Strokes asked for per step. */
    const val STROKES = 4

    fun show(context: Context, onApply: (YtSwipeTuning) -> Unit) {
        val activity = ContextHelper.tryUnwrapActivity<ComponentActivity>(context) ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (root.findViewWithTag<View>(TAG) != null) return
        val palette = YtDesign.current().palette ?: YtPalettes.YouTube
        val view = ComposeView(activity)
        view.tag = TAG
        view.isClickable = true
        val back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                remove()
                root.removeView(view)
            }
        }
        activity.onBackPressedDispatcher.addCallback(activity, back)
        fun close() {
            back.remove()
            view.post { root.removeView(view) }
        }
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            YtTheme(palette) {
                Calibration(palette, onClose = ::close) {
                    onApply(it)
                    close()
                }
            }
        }
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

private enum class Step { SCROLL, SWIPE, RESULT }

@Composable
private fun Calibration(palette: YtPalette, onClose: () -> Unit, onApply: (YtSwipeTuning) -> Unit) {
    var step by remember { mutableStateOf(Step.SCROLL) }
    val scrolls = remember { mutableStateListOf<List<YtSwipeCalibration.Point>>() }
    val swipes = remember { mutableStateListOf<List<YtSwipeCalibration.Point>>() }
    var slop by remember { mutableStateOf(YtSwipeCalibration.SLOP_DP) }
    val haptics = rememberYtHaptics()
    val current = remember { YtSwipeTuningPrefs.current() }
    val proposal = remember(step) {
        if (step == Step.RESULT) YtSwipeCalibration.propose(scrolls.toList(), swipes.toList(), current, slop) else null
    }
    val surfaceInk = remember(palette) { YtInk.on(palette, palette.surface.toArgb()) }

    fun restart() {
        scrolls.clear()
        swipes.clear()
        step = Step.SCROLL
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.yt_swipe_calibrate),
                color = palette.text,
                fontFamily = palette.font,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.cancel),
                color = palette.textSecondary,
                fontFamily = palette.font,
                fontSize = 15.sp,
                modifier = Modifier.clip(ytPillShape(palette)).clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        val (instruction, count) = when (step) {
            Step.SCROLL -> stringResource(R.string.yt_swipe_calibrate_scroll, YtSwipeCalibrationScreen.STROKES) to scrolls.size
            Step.SWIPE -> stringResource(R.string.yt_swipe_calibrate_swipe, YtSwipeCalibrationScreen.STROKES) to swipes.size
            Step.RESULT -> stringResource(R.string.yt_swipe_calibrate_result) to 0
        }
        Text(instruction, color = palette.text, fontFamily = palette.font, fontSize = 15.sp, lineHeight = 21.sp)
        if (step != Step.RESULT) {
            Spacer(Modifier.height(10.dp))
            Dots(palette, count)
        }
        Spacer(Modifier.height(16.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                Step.SCROLL -> Recorder(
                    onSlop = { slop = it },
                    onStroke = { stroke ->
                        if (YtSwipeCalibration.moved(stroke, slop)) {
                            scrolls += stroke
                            haptics.tick()
                            if (scrolls.size >= YtSwipeCalibrationScreen.STROKES) step = Step.SWIPE
                        }
                    },
                ) { _ ->
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(30) { TestCard(palette, offset = 0f) }
                    }
                }

                Step.SWIPE -> {
                    val scope = rememberCoroutineScope()
                    val offset = remember { Animatable(0f) }
                    Recorder(
                        onSlop = { slop = it },
                        onMove = { dx -> scope.launch { offset.snapTo(dx) } },
                        onStroke = { stroke ->
                            scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 380f)) }
                            val origin = stroke.firstOrNull()
                            if (origin != null && stroke.any { kotlin.math.abs(it.x - origin.x) > slop }) {
                                swipes += stroke
                                haptics.tick()
                                if (swipes.size >= YtSwipeCalibrationScreen.STROKES) step = Step.RESULT
                            }
                        },
                    ) { density ->
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                            TestCard(palette, offset = offset.value * density)
                        }
                    }
                }

                Step.RESULT -> Result(palette, surfaceInk, current, proposal)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (step == Step.RESULT || scrolls.isNotEmpty()) {
                Button(palette, stringResource(R.string.yt_swipe_calibrate_again), filled = false, modifier = Modifier.weight(1f)) { restart() }
            }
            if (step == Step.RESULT && proposal != null) {
                Button(palette, stringResource(R.string.yt_swipe_calibrate_apply), filled = true, modifier = Modifier.weight(1f)) {
                    haptics.confirm()
                    onApply(proposal.tuning)
                }
            }
        }
    }
}

/**
 * Records each touch's path (in dp) without consuming it, so the content below still scrolls.
 * [onMove] gets the current sideways travel in dp; [content] gets the px per dp.
 */
@Composable
private fun Recorder(
    onSlop: (Float) -> Unit,
    onStroke: (List<YtSwipeCalibration.Point>) -> Unit,
    onMove: (Float) -> Unit = {},
    content: @Composable (Float) -> Unit,
) {
    var pxPerDp by remember { mutableStateOf(1f) }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            pxPerDp = density
            // the same unit the card measures in (the design screens scale their dp)
            onSlop(viewConfiguration.touchSlop / density)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val points = mutableListOf(YtSwipeCalibration.Point(down.position.x / density, down.position.y / density))
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val point = YtSwipeCalibration.Point(change.position.x / density, change.position.y / density)
                    points += point
                    onMove(point.x - points.first().x)
                    if (!change.pressed) break
                }
                onStroke(points)
            }
        },
    ) { content(pxPerDp) }
}

@Composable
private fun TestCard(palette: YtPalette, offset: Float) {
    val shape = ytCardShape(palette)
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = offset }
            .clip(shape)
            .background(palette.surface)
            .padding(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(ytCardShape(palette, 10)).background(palette.raised))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(palette.raised))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(palette.raised))
    }
}

@Composable
private fun Dots(palette: YtPalette, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(YtSwipeCalibrationScreen.STROKES) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (it < count) palette.accent else palette.raised))
        }
    }
}

@Composable
private fun Result(palette: YtPalette, ink: YtInk, current: YtSwipeTuning, proposal: YtSwipeCalibration.Proposal?) {
    Column(
        Modifier.fillMaxWidth().clip(ytCardShape(palette)).background(palette.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (proposal == null) {
            Text(stringResource(R.string.yt_swipe_calibrate_too_few), color = ink.text, fontFamily = palette.font, fontSize = 15.sp)
            return@Column
        }
        val next = proposal.tuning
        ValueRow(palette, ink, stringResource(R.string.yt_swipe_angle), stringResource(R.string.yt_swipe_degrees, current.angleDeg.roundToInt()), stringResource(R.string.yt_swipe_degrees, next.angleDeg.roundToInt()))
        ValueRow(palette, ink, stringResource(R.string.yt_swipe_drift), stringResource(R.string.yt_swipe_dp, current.driftDp.roundToInt()), stringResource(R.string.yt_swipe_dp, next.driftDp.roundToInt()))
        ValueRow(palette, ink, stringResource(R.string.yt_swipe_distance), stringResource(R.string.yt_swipe_dp, current.distanceDp.roundToInt()), stringResource(R.string.yt_swipe_dp, next.distanceDp.roundToInt()))
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.yt_swipe_calibrate_score_now, proposal.before.swipesOk, proposal.before.swipes, proposal.before.scrollsOk, proposal.before.scrolls),
            color = ink.textSecondary,
            fontFamily = palette.font,
            fontSize = 14.sp,
        )
        Text(
            stringResource(R.string.yt_swipe_calibrate_score_new, proposal.after.swipesOk, proposal.after.swipes, proposal.after.scrollsOk, proposal.after.scrolls),
            color = ink.text,
            fontFamily = palette.font,
            fontSize = 14.sp,
        )
        if (proposal.conflict) {
            Text(stringResource(R.string.yt_swipe_calibrate_conflict), color = ink.textSecondary, fontFamily = palette.font, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun ValueRow(palette: YtPalette, ink: YtInk, label: String, now: String, next: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ink.text, fontFamily = palette.font, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(now, color = ink.textSecondary, fontFamily = palette.font, fontSize = 15.sp)
        Text("  →  ", color = ink.textSecondary, fontFamily = palette.font, fontSize = 15.sp)
        Text(next, color = ink.text, fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.width(64.dp))
    }
}

@Composable
private fun Button(palette: YtPalette, label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = ytPillShape(palette)
    val back = if (filled) palette.accent else palette.surface
    val text = if (filled) palette.onAccent else YtInk.on(palette, palette.surface.toArgb()).text
    Box(
        modifier.clip(shape).background(back).clickable(onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = text, fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

