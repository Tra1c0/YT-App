package com.github.libretube.ui.yt.news

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.ui.activities.SettingsActivity
import androidx.compose.runtime.staticCompositionLocalOf

/** The id of the video whose page is shown (provided by the watch page; null when unknown). */
val LocalYtKurzVideo = staticCompositionLocalOf<String?> { null }

/**
 * YT: the "Kurzfassung" block of any video: one button first (nothing is requested before the
 * tap), then loading, then 4 to 8 statements with their seconds ([onSeek] gets the second), or
 * the reason there is none. [color] and [accent] are the ink the page chose for the surface the
 * block stands on (Pitfall P1: no palette text colour here). A cached summary shows at once.
 */
@Composable
fun YtKurzBlock(
    videoId: String,
    title: String,
    channel: String?,
    color: Color,
    accent: Color,
    font: FontFamily,
    modifier: Modifier = Modifier,
    sizeSp: Float = 14f,
    onSeek: (Long) -> Unit,
) {
    val key = YtKurzSummary.keyOf(videoId)
    val states by YtKurzSummary.states.collectAsState()
    val state = states[key] ?: YtNewsSummaryState.Idle
    LaunchedEffect(key) { YtKurzSummary.loadCached(key) }
    val style = remember(color, accent, font) { YtNewsSummaryStyle(color, color.copy(alpha = 0.72f), accent, accent.copy(alpha = 0.16f), font) }
    val context = LocalContext.current
    val small = TextStyle(fontFamily = font, fontSize = (sizeSp - 1.5f).sp, lineHeight = (sizeSp + 3f).sp)
    Column(modifier.fillMaxWidth().padding(top = 14.dp).testTag("yt_kurz_block")) {
        when (state) {
            is YtNewsSummaryState.Ready -> {
                YtNewsStatements(state.digest, style) { statement -> onSeek(statement.seconds) }
                if (state.digest.parts > 1) {
                    Text(
                        text = pluralStringResource(R.plurals.yt_r39_kurz_parts, state.digest.parts, state.digest.parts),
                        color = style.secondary,
                        style = small,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            YtNewsSummaryState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
                CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.yt_r39_kurz_loading), color = style.secondary, style = small, modifier = Modifier.padding(start = 8.dp))
            }
            else -> {
                Button(style, stringResource(R.string.yt_r39_kurz_button), (sizeSp - 1.5f)) { YtKurzSummary.request(videoId, title, channel) }
                val reason = (state as? YtNewsSummaryState.Failed)?.reason
                if (reason == YtNewsSummaryFailure.NO_KEY) {
                    Text(stringResource(R.string.yt_r39_kurz_no_key), color = style.secondary, style = small, modifier = Modifier.padding(top = 6.dp))
                    Button(style, stringResource(R.string.yt_r39_kurz_open_settings), (sizeSp - 1.5f), Modifier.padding(top = 4.dp)) {
                        runCatching { context.startActivity(Intent(context, SettingsActivity::class.java)) }
                    }
                } else if (reason != null) {
                    val note = if (reason == YtNewsSummaryFailure.NO_TRANSCRIPT) R.string.yt_r39_kurz_no_captions else YtNewsSummaryUi.noteOf(reason)
                    note?.let { Text(stringResource(it), color = style.secondary, style = small, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Button(style: YtNewsSummaryStyle, label: String, sizeSp: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = label,
        color = style.accent,
        maxLines = 1,
        style = TextStyle(fontFamily = style.font, fontWeight = FontWeight.SemiBold, fontSize = sizeSp.sp),
        modifier = modifier
            .testTag("yt_kurz_button")
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(style.accentFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
