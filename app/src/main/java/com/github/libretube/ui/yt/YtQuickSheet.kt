package com.github.libretube.ui.yt

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem

/**
 * YT: quick actions for a video (save, share, channel) in a sheet that springs up from the
 * bottom, follows the finger when dragged (with a rubber band upwards) and can be thrown away:
 * letting go low or with a flick downwards closes it, carrying the finger's velocity. The
 * sheet uses the surfaces of the design.
 */
@Composable
fun YtQuickSheet(
    palette: YtPalette,
    imageLoader: ImageLoader,
    video: StreamItem?,
    onDismiss: () -> Unit,
    onSave: (StreamItem) -> Unit,
    onShare: (StreamItem) -> Unit,
    onChannel: (StreamItem) -> Unit,
) {
    // the last video stays while the sheet slides away
    val shown = remember { arrayOfNulls<StreamItem>(1) }
    video?.let { shown[0] = it }
    val haptics = rememberYtHaptics()
    val swipe = rememberYtSwipe()
    var height by remember { mutableStateOf(IntSize.Zero) }
    val square = palette.surfaceStyle == YtSurfaceStyle.EDITORIAL
    val shape = if (square) RectangleShape else RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

    AnimatedVisibility(
        visible = video != null,
        enter = ytEnterTransition(fadeIn(tween(180))),
        exit = ytExitTransition(fadeOut(tween(180))),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x80000000))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = video != null,
            enter = ytEnterTransition(slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { it }),
            exit = ytExitTransition(slideOutVertically(tween(220, easing = YtEaseOut)) { it }),
        ) {
            val item = shown[0] ?: return@AnimatedVisibility
            Column(
                Modifier
                    .onSizeChanged { height = it }
                    .offset { IntOffset(0, swipe.offset.value.toInt().coerceAtLeast(-40)) }
                    .ytSwipe(swipe, thresholdPx = (height.height * 0.35f).coerceAtLeast(120f), direction = 1, haptics = haptics, vertical = true) {
                        onDismiss()
                    }
                    .fillMaxWidth()
                    .clip(shape)
                    .background(palette.surface)
                    .then(if (square) Modifier.background(palette.background) else Modifier)
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 18.dp + ytNavPadding()),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(36.dp, 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.textSecondary.copy(alpha = 0.5f)),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = null,
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp, 54.dp)
                            .clip(ytCardShape(palette, 10))
                            .background(palette.raised),
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            text = item.title.orEmpty(),
                            color = palette.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
                        )
                        Text(
                            text = item.uploaderName.orEmpty(),
                            color = palette.textSecondary,
                            maxLines = 1,
                            style = TextStyle(fontFamily = palette.font, fontSize = 12.sp),
                        )
                    }
                }
                QuickRow(palette, R.drawable.ic_playlist_add, stringResource(R.string.save)) { onSave(item); onDismiss() }
                QuickRow(palette, R.drawable.ic_share, stringResource(R.string.share)) { onShare(item); onDismiss() }
                QuickRow(palette, R.drawable.ic_yt_person, stringResource(R.string.yt_side_open_channel)) { onChannel(item); onDismiss() }
            }
        }
    }
}

@Composable
private fun QuickRow(palette: YtPalette, @DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .ytPressable(ytCardShape(palette, 14), squish = palette.surfaceStyle == YtSurfaceStyle.CLAY, onClick = onClick)
            .ytSurface(palette, ytCardShape(palette, 14))
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = palette.text, modifier = Modifier.size(22.dp))
        Box(Modifier.width(14.dp))
        Text(
            text = label,
            color = palette.text,
            style = TextStyle(fontFamily = palette.font, fontWeight = FontWeight.Medium, fontSize = 15.sp),
        )
    }
}
