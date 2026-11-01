package com.github.libretube.ui.yt.scene

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import com.github.libretube.R
import com.github.libretube.api.obj.Comment
import com.github.libretube.helpers.SceneMode
import com.github.libretube.helpers.SceneVerdict
import com.github.libretube.helpers.YtCommentMoments
import com.github.libretube.ui.models.CommentScene
import com.github.libretube.ui.models.SceneComment
import com.github.libretube.ui.yt.LocalYtMotion
import com.github.libretube.ui.yt.YtEaseOut
import com.github.libretube.ui.yt.rememberYtHaptics
import com.github.libretube.ui.yt.ytStateSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * YT (comments follow the scene): the pieces of the design pages that show comments (Side's
 * conversation, Movo's discussion), on the same state as the classic sheet (CommentScene).
 */

/** What a page reads of the scene; it changes only at its rare steps, not with every tick of the clock. */
class YtSceneUi(
    val scene: CommentScene?,
    val mode: SceneMode,
    val epoch: Int,
    val step: Long,
    val indexed: List<SceneComment>,
    val scanning: Boolean,
    val exhausted: Boolean,
) {
    /** What [comment] may show now (everything without a scene). */
    fun verdict(comment: Comment): SceneVerdict = scene?.verdict(comment) ?: SceneVerdict.SHOW
}

private val NO_MODE: StateFlow<SceneMode> = MutableStateFlow(SceneMode.OFF)
private val NO_INT: StateFlow<Int> = MutableStateFlow(0)
private val NO_LONG: StateFlow<Long> = MutableStateFlow(0L)
private val NO_ITEMS: StateFlow<List<SceneComment>> = MutableStateFlow(emptyList())
private val NO_FLAG: StateFlow<Boolean> = MutableStateFlow(false)

@Composable
fun rememberYtSceneUi(scene: CommentScene?): YtSceneUi {
    val mode by (scene?.mode ?: NO_MODE).collectAsState()
    val epoch by (scene?.epoch ?: NO_INT).collectAsState()
    val step by (scene?.sceneStep ?: NO_LONG).collectAsState()
    val indexed by (scene?.indexed ?: NO_ITEMS).collectAsState()
    val scanning by (scene?.scanning ?: NO_FLAG).collectAsState()
    val exhausted by (scene?.exhausted ?: NO_FLAG).collectAsState()
    return YtSceneUi(scene, mode, epoch, step, indexed, scanning, exhausted)
}

private val SCENE_CHIPS = listOf(
    SceneMode.OFF to R.string.yt_r12_e_chip_all,
    SceneMode.SCENE to R.string.yt_r12_e_mode_scene,
    SceneMode.SPOILER to R.string.yt_r12_e_mode_spoiler,
)

/**
 * Alle / Diese Stelle / Spoilerschutz as pills of the design: [active] behind the chosen one,
 * [idle] behind the others. The same mode as the setting and the classic sheet's chips.
 */
@Composable
fun YtSceneChips(
    selected: SceneMode,
    onSelect: (SceneMode) -> Unit,
    font: FontFamily,
    active: Color,
    onActive: Color,
    idle: Color,
    onIdle: Color,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYtHaptics()
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.selectableGroup(),
    ) {
        SCENE_CHIPS.forEach { (mode, label) ->
            val chosen = mode == selected
            val background by animateColorAsState(if (chosen) active else idle, ytStateSpec(), label = "sceneChip")
            Text(
                text = stringResource(label),
                color = if (chosen) onActive else onIdle,
                maxLines = 1,
                style = TextStyle(
                    fontFamily = font,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 13.sp,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .drawBehind { drawRect(background) }
                    .selectable(selected = chosen, role = Role.RadioButton) {
                        if (!chosen) {
                            haptics.tick()
                            onSelect(mode)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Makes a hidden or folded comment row open with a tap (and TalkBack hear only that it is
 * hidden); any other row stays as it is.
 */
@Composable
fun Modifier.ytSceneReveal(scene: CommentScene?, comment: Comment, verdict: SceneVerdict): Modifier {
    val haptics = rememberYtHaptics()
    val label = stringResource(R.string.yt_r12_e_reveal)
    val description = if (verdict.hidden) {
        stringResource(
            R.string.yt_r12_e_hidden_description,
            comment.author.removePrefix("@"),
            YtCommentMoments.formatTime(verdict.spoilerAtMs),
        )
    } else {
        null
    }
    if (scene == null || (!verdict.hidden && !verdict.folded)) return this
    val reveal = {
        haptics.confirm()
        scene.reveal(comment)
    }
    val tappable = clickable(onClickLabel = label, onClick = reveal)
    if (description == null) return tappable
    return tappable.clearAndSetSemantics {
        contentDescription = description
        onClick(label) {
            reveal()
            true
        }
    }
}

/**
 * A comment's text as the scene lets it show: blurred with a pill naming the time (Android 12
 * and later; before, the pill alone stands in for the text), a timeline up to what was seen with
 * "+3 weitere Zeitmarken · tippen", or plain. The blur clears with the design's ease when the
 * time is passed or the row tapped; the animation reads only in the draw phase.
 */
@Composable
fun YtSceneText(
    text: String,
    comment: Comment,
    verdict: SceneVerdict,
    scene: CommentScene?,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    pill: Color,
    onPill: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val motion = LocalYtMotion.current
    val blurPx = with(LocalDensity.current) { 7.dp.toPx() }
    val blur = remember(comment.commentId) { Animatable(if (verdict.hidden) 1f else 0f) }
    LaunchedEffect(comment.commentId, verdict.hidden, motion) {
        val target = if (verdict.hidden) 1f else 0f
        if (motion) {
            blur.animateTo(target, tween(if (verdict.hidden) 220 else 320, easing = YtEaseOut))
        } else {
            blur.snapTo(target)
        }
    }
    val at = YtCommentMoments.formatTime(verdict.spoilerAtMs)

    if (verdict.folded) {
        val shown = remember(comment.commentId, verdict.shownLines) {
            val html = scene?.let { YtCommentMoments.shownHtml(it.momentsOf(comment), verdict) }.orEmpty()
            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
        }
        val fold = pluralStringResource(R.plurals.yt_r12_e_more_times, verdict.foldedTimes, verdict.foldedTimes)
        Text(
            text = buildAnnotatedString {
                append(shown)
                if (shown.isNotEmpty()) append("\n")
                withStyle(SpanStyle(color = accent)) { append(fold) }
            },
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = style,
            modifier = modifier,
        )
        return
    }
    if (verdict.hidden && !canBlur) {
        Box(modifier) {
            ScenePill(stringResource(R.string.yt_r12_e_spoiler_at, at), pill, onPill, style)
        }
        return
    }
    Box(modifier) {
        Text(
            text = text,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = style,
            modifier = Modifier.graphicsLayer {
                val radius = blur.value * blurPx
                renderEffect = if (canBlur && radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
            },
        )
        if (verdict.hidden || blur.isRunning) {
            ScenePill(
                stringResource(R.string.yt_r12_e_spoiler_short, at),
                pill,
                onPill,
                style,
                Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer { alpha = blur.value },
            )
        }
    }
}

@Composable
private fun ScenePill(text: String, background: Color, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        maxLines = 1,
        style = style.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The line about the scene (which scene, or that nothing was found yet), for the design pages. */
@Composable
fun sceneCaption(ui: YtSceneUi, shown: Int): String? {
    val scene = ui.scene ?: return null
    val now = scene.playback()
    return when (ui.mode) {
        SceneMode.OFF -> null
        SceneMode.SPOILER -> stringResource(R.string.yt_r12_e_caption_spoiler, YtCommentMoments.formatTime(now.unlockedUpToMs))
        SceneMode.SCENE -> {
            val window = YtCommentMoments.sceneWindow(now)
            val end = now.durationMs.takeIf { it > 0 }?.let { minOf(window.last, it) } ?: window.last
            val from = YtCommentMoments.formatTime(window.first)
            val to = YtCommentMoments.formatTime(end)
            when {
                shown > 0 || ui.scanning -> scene.currentChapter()
                    ?.let { stringResource(R.string.yt_r12_e_caption_chapter, it.title, from, to) }
                    ?: stringResource(R.string.yt_r12_e_caption_around, YtCommentMoments.formatTime(now.positionMs), from, to)
                ui.exhausted -> stringResource(R.string.yt_r12_e_scene_empty_all, from, to)
                else -> pluralStringResource(R.plurals.yt_r12_e_scene_empty, ui.indexed.size, from, to, ui.indexed.size.toString())
            }
        }
    }
}
