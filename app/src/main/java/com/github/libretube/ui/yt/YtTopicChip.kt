package com.github.libretube.ui.yt

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.ui.yt.feed.YtFeed
import com.github.libretube.ui.yt.feed.YtSeenMark

/** How a design draws the topic chip on a thumbnail. */
internal class TopicChipLook(val shape: Shape, val fill: Color, val ink: Color, val font: FontFamily?, val uppercase: Boolean)

/**
 * The chip's look: the accent with words made readable on it (YtContrast), Editorial black
 * and white in its mono type, Glas and Frost a dark glass over the picture (white words read
 * on it over any picture).
 */
internal fun topicChipLook(palette: YtPalette): TopicChipLook {
    fun accent(shape: Shape) = TopicChipLook(
        shape,
        palette.accent.copy(alpha = 1f),
        Color(YtContrast.readable(palette.onAccent.toArgb(), palette.accent.copy(alpha = 1f).toArgb())),
        palette.font,
        uppercase = false,
    )
    return when (palette.signature) {
        YtSignature.EDITORIAL -> TopicChipLook(RectangleShape, Color.Black.copy(alpha = 0.8f), Color.White, YtFonts.plexMono, uppercase = true)
        YtSignature.GLASS, YtSignature.FROST -> TopicChipLook(RoundedCornerShape(50), Color.Black.copy(alpha = 0.62f), Color.White, palette.font, uppercase = false)
        YtSignature.YOUTUBE, YtSignature.SIDE, YtSignature.MOVO, YtSignature.AFTER -> accent(RoundedCornerShape(4.dp))
        // Synthwave: a magenta tag like the seen badge, dark ink
        YtSignature.SYNTHWAVE -> accent(RoundedCornerShape(5.dp))
        else -> accent(RoundedCornerShape(50))
    }
}

/**
 * YT: a small chip with the name of the user's topic on a thumbnail of [video], when the home
 * feed counts it for one (YtFeed.focus). Top start, below the "Gesehen" badge when there is
 * one; centred in the upper part of a round thumbnail ([shape] CircleShape). Nothing on a
 * thumbnail too small to carry it.
 */
@Composable
fun Modifier.ytTopicChip(video: StreamItem, palette: YtPalette, shape: Shape = RectangleShape): Modifier {
    val focus by YtFeed.focus.collectAsState()
    if (focus.isEmpty()) return this
    val id = remember(video.url) { video.url?.toID() } ?: return this
    val mark = focus[id] ?: return this
    val positions by YtSeenStore.positions.collectAsState()
    val badged = remember { YtSeenPrefs.enabled } && !video.isLive && YtSeenMark.of(positions[id], video.duration)?.watched == true
    val measurer = rememberTextMeasurer()
    val look = remember(palette) { topicChipLook(palette) }
    val name = if (look.uppercase) mark.name.uppercase() else mark.name
    val style = TextStyle(
        color = look.ink,
        fontFamily = look.font,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.5.sp,
        letterSpacing = if (look.uppercase) 0.6.sp else 0.sp,
    )
    val round = shape == CircleShape
    return this.drawWithCache {
        val inset = 8.dp.toPx()
        val padH = 7.dp.toPx()
        val padV = 2.5.dp.toPx()
        val roomW = if (round) size.width * 0.66f else size.width * 0.72f - inset
        val maxText = (roomW - 2 * padH).toInt()
        val text = if (size.width < 96.dp.toPx() || size.height < 48.dp.toPx() || maxText <= 0) {
            null
        } else {
            measurer.measure(name, style, overflow = TextOverflow.Ellipsis, maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = maxText))
        }
        val w = (text?.size?.width ?: 0) + 2 * padH
        val h = (text?.size?.height ?: 0) + 2 * padV
        val x = if (round) (size.width - w) / 2f else if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - inset - w else inset
        val y = if (round) size.height * 0.16f else inset + if (badged) 24.dp.toPx() else 0f
        val outline = look.shape.createOutline(Size(w, h), layoutDirection, this)
        val fits = text != null && y + h <= size.height - 12.dp.toPx()
        onDrawWithContent {
            drawContent()
            if (!fits || text == null) return@onDrawWithContent
            translate(x, y) {
                drawOutline(outline, look.fill)
                drawText(text, topLeft = Offset(padH, padV))
            }
        }
    }
}
