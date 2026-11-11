package com.github.libretube.ui.yt.news

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.ui.yt.YtQueue
import com.github.libretube.ui.yt.feed.YtFeed
import kotlinx.coroutines.flow.first

/**
 * The colours and font of the summary on a News card. The colours come from the card's ink
 * (YtInk for the card surface, P1), so they stay readable on every design.
 */
@Immutable
class YtNewsSummaryStyle(
    val text: Color,
    val secondary: Color,
    /** The second stamps and the button's words: readable over [accentFill] on the card. */
    val accent: Color,
    val accentFill: Color,
    val font: FontFamily,
)

/**
 * YT: the description area of a News card with the opt-in summary. Without a key it is
 * exactly [description] (the cleaned description), nothing else. With a key a small
 * "Kurzfassung" button sits under it; a tap asks for the cited key statements, which then take
 * the description's place, each with its second: a tap plays the video from there. A summary
 * cached from an earlier tap shows at once. Closing the overlay cancels a running request.
 */
@Composable
fun YtNewsSummaryBlock(
    item: YtNewsItem,
    video: StreamItem,
    style: YtNewsSummaryStyle,
    modifier: Modifier = Modifier,
    description: @Composable () -> Unit,
) {
    val hasKey by YtNewsSummaryKey.present.collectAsState()
    if (!hasKey) {
        Box(modifier) { description() }
        return
    }
    val key = YtNewsSummary.keyOf(item)
    val states by YtNewsSummary.states.collectAsState()
    val state = states[key] ?: YtNewsSummaryState.Idle
    // the summary replaces the description; "Beschreibung" shows the description again
    var showDescription by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { YtNewsSummary.cached(key) }
    // a request keeps running while the user swipes through the cards (its answer waits in the
    // states); closing the overlay stops it
    LaunchedEffect(key) {
        snapshotFlow { YtNews.shown }.first { !it }
        YtNewsSummary.cancel(key)
    }
    DisposableEffect(key) { onDispose { if (!YtNews.shown) YtNewsSummary.cancel(key) } }
    val context = LocalContext.current

    Column(modifier) {
        if (state is YtNewsSummaryState.Ready && !showDescription) {
            YtNewsStatements(
                digest = state.digest,
                style = style,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onOpen = { statement -> YtNewsSummaryUi.play(context, video, statement.seconds) },
            )
            SummaryButton(style, stringResource(R.string.yt_r28_newssum_back)) { showDescription = true }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) { description() }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                when (state) {
                    YtNewsSummaryState.Loading -> {
                        CircularProgressIndicator(color = style.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Note(style, stringResource(R.string.yt_r28_newssum_loading))
                    }
                    else -> {
                        SummaryButton(style, stringResource(R.string.yt_r28_newssum_button)) {
                            if (state is YtNewsSummaryState.Ready) showDescription = false else YtNewsSummary.request(item)
                        }
                        (state as? YtNewsSummaryState.Failed)?.reason?.let { reason ->
                            YtNewsSummaryUi.noteOf(reason)?.let { Note(style, stringResource(it)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The key statements of [digest] under the "Kurzfassung · Quellen-Sekunden" label, each with the
 * second it rests on. Shared with the story page, which passes its own [onOpen] (a statement of a
 * story names its video).
 */
@Composable
fun YtNewsStatements(
    digest: YtNewsDigest,
    style: YtNewsSummaryStyle,
    modifier: Modifier = Modifier,
    onOpen: (YtNewsStatement) -> Unit,
) {
    Column(modifier.testTag("yt_news_summary"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.yt_r28_newssum_label),
            color = style.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = style.font, fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
        )
        for (statement in digest.statements) Statement(statement, style, onOpen)
        if (digest.cut) {
            Text(
                text = stringResource(R.string.yt_r28_newssum_cut),
                color = style.secondary,
                maxLines = 2,
                style = TextStyle(fontFamily = style.font, fontSize = 11.5.sp),
            )
        }
    }
}

@Composable
private fun Statement(statement: YtNewsStatement, style: YtNewsSummaryStyle, onOpen: (YtNewsStatement) -> Unit) {
    val stamp = YtNewsTranscriptDoc.stamp(statement.seconds)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = stringResource(R.string.yt_r28_newssum_open, stamp)) { onOpen(statement) }
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = stamp,
            color = style.accent,
            maxLines = 1,
            style = TextStyle(fontFamily = style.font, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
            modifier = Modifier
                .padding(top = 1.dp, end = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(style.accentFill)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Text(
            text = statement.text,
            color = style.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = style.font, fontSize = 13.5.sp, lineHeight = 18.sp),
        )
    }
}

@Composable
private fun SummaryButton(style: YtNewsSummaryStyle, label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = style.accent,
        maxLines = 1,
        style = TextStyle(fontFamily = style.font, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp),
        modifier = Modifier
            .testTag("yt_news_summary_button")
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(style.accentFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun Note(style: YtNewsSummaryStyle, text: String) {
    Text(
        text = text,
        color = style.secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = style.font, fontSize = 12.sp),
        modifier = Modifier.padding(start = 8.dp),
    )
}

object YtNewsSummaryUi {
    /** The quiet note under the button for [reason]; null when nothing is said. */
    fun noteOf(reason: YtNewsSummaryFailure): Int? = when (reason) {
        YtNewsSummaryFailure.NO_KEY -> null
        YtNewsSummaryFailure.NO_TRANSCRIPT -> R.string.yt_r28_newssum_no_subtitles
        YtNewsSummaryFailure.INVALID_KEY -> R.string.yt_r28_newssum_invalid_key
        YtNewsSummaryFailure.RATE_LIMITED -> R.string.yt_r28_newssum_rate_limited
        YtNewsSummaryFailure.OFFLINE -> R.string.yt_r28_newssum_offline
        YtNewsSummaryFailure.REFUSED -> R.string.yt_r28_newssum_refused
        YtNewsSummaryFailure.SERVICE -> R.string.yt_r28_newssum_service
    }

    /** Plays [video] from [seconds], the same way the card's "Ansehen" does. */
    fun play(context: Context, video: StreamItem, seconds: Long) {
        YtNews.close()
        YtQueue.playKeepingQueue(context, video, seconds = seconds, audio = YtFeed.audioNow())
    }
}
