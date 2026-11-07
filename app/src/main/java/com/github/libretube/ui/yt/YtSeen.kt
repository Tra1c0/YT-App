package com.github.libretube.ui.yt

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.yt.feed.YtSeenMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** YT: "Gesehenes markieren" (Gesten & Interaktion → Startseite), on by default. */
object YtSeenPrefs {
    const val KEY = "yt_seen_marks"
    val enabled: Boolean get() = PreferenceHelper.getBoolean(KEY, true)
}

/**
 * YT: every watch position by video id, for the watched marks of the home cards. One query of
 * the whole table, shared by all cards and repeated only when the table changes (the player
 * saves on pause and at the end); it stops 5 s after the last card left the screen.
 */
object YtSeenStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val positions: StateFlow<Map<String, Long>> by lazy {
        Database.watchPositionDao().observeAll()
            .map { list -> list.associate { it.videoId to it.position } }
            .catch { emit(emptyMap()) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }
}

/** How the "Gesehen" badge looks in a design. */
internal class SeenBadge(
    val shape: Shape,
    val fill: Color,
    val ink: Color,
    val uppercase: Boolean,
    val iconOnly: Boolean,
    /** The corner for the View rows (search results); negative: fully round. */
    val cornerDp: Float,
)

internal fun seenBadge(palette: YtPalette): SeenBadge = when (palette.signature) {
    YtSignature.EDITORIAL -> SeenBadge(RectangleShape, Color.Black.copy(alpha = 0.66f), Color.White, uppercase = true, iconOnly = false, cornerDp = 0f)
    YtSignature.SHAPES -> SeenBadge(CircleShape, palette.accent, palette.onAccent, uppercase = false, iconOnly = true, cornerDp = -1f)
    YtSignature.POP -> SeenBadge(RoundedCornerShape(8.dp), palette.accent, palette.onAccent, uppercase = false, iconOnly = false, cornerDp = 8f)
    YtSignature.GLASS, YtSignature.FROST -> SeenBadge(RoundedCornerShape(50), Color.White.copy(alpha = 0.24f), Color.White, uppercase = false, iconOnly = false, cornerDp = -1f)
    YtSignature.CLAY -> SeenBadge(RoundedCornerShape(50), palette.surface.copy(alpha = 0.9f), palette.text, uppercase = false, iconOnly = false, cornerDp = -1f)
    else -> SeenBadge(RoundedCornerShape(4.dp), Color.Black.copy(alpha = 0.66f), Color.White, uppercase = false, iconOnly = false, cornerDp = 4f)
}

/**
 * YT: marks a thumbnail of [video] as watched, quietly: a thin bar in the design's accent along
 * the bottom while it is started, a thumbnail dimmed to [YtSeenMark.WATCHED_BRIGHTNESS] with a
 * small "Gesehen" badge in the top corner once it is finished. Put it after the thumbnail's clip,
 * so the bar follows its corners. Nothing for live streams, unwatched videos or with the
 * setting off.
 */
@Composable
fun Modifier.ytSeen(video: StreamItem, palette: YtPalette): Modifier {
    if (video.isLive || !remember { YtSeenPrefs.enabled }) return this
    val id = remember(video.url) { video.url?.toID() } ?: return this
    val positions by YtSeenStore.positions.collectAsState()
    val mark = YtSeenMark.of(positions[id], video.duration) ?: return this
    val measurer = rememberTextMeasurer()
    val badge = remember(palette) { seenBadge(palette) }
    val word = stringResource(R.string.yt_seen_badge).let { if (badge.uppercase) it.uppercase() else it }
    val style = TextStyle(
        color = badge.ink,
        fontFamily = palette.font,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = if (badge.uppercase) 0.8.sp else 0.sp,
    )
    return this.drawWithCache {
        val bar = 3.dp.toPx()
        val inset = 6.dp.toPx()
        val icon = 9.dp.toPx()
        val pad = 5.dp.toPx()
        val text = if (mark.watched && !badge.iconOnly) measurer.measure(word, style) else null
        val badgeHeight = maxOf(icon, text?.size?.height?.toFloat() ?: 0f) + 2 * 3.dp.toPx()
        val badgeWidth = if (badge.iconOnly) badgeHeight else pad + icon + 3.dp.toPx() + (text?.size?.width ?: 0) + pad
        val outline = badge.shape.createOutline(Size(badgeWidth, badgeHeight), layoutDirection, this)
        val check = Path().apply {
            moveTo(0f, icon * 0.55f)
            lineTo(icon * 0.38f, icon * 0.9f)
            lineTo(icon, icon * 0.15f)
        }
        val checkStroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        onDrawWithContent {
            drawContent()
            if (mark.watched) {
                // a dimmed picture: quiet, but the eye skips it
                drawRect(Color.Black, alpha = 1f - YtSeenMark.WATCHED_BRIGHTNESS)
                translate(inset, inset) {
                    drawBadge(outline, badge.fill)
                    val iconX = if (badge.iconOnly) (badgeWidth - icon) / 2f else pad
                    translate(iconX, (badgeHeight - icon) / 2f) { drawPath(check, badge.ink, style = checkStroke) }
                    if (text != null) {
                        drawText(text, topLeft = Offset(pad + icon + 3.dp.toPx(), (badgeHeight - text.size.height) / 2f))
                    }
                }
            } else if (mark.progress != null) {
                val top = size.height - bar
                drawRect(Color.White.copy(alpha = 0.32f), topLeft = Offset(0f, top), size = Size(size.width, bar))
                drawRoundRect(
                    palette.accent,
                    topLeft = Offset(0f, top),
                    size = Size(size.width * mark.progress, bar),
                    cornerRadius = CornerRadius(bar / 2f),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBadge(outline: Outline, fill: Color) = drawOutline(outline, fill)
