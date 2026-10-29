package com.github.libretube.ui.yt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID

/**
 * YT: the largest thumbnail YouTube has for a video, for full-bleed images like the After
 * poster or the Movo covers. Falls back to the regular thumbnail when there is none.
 */
@Composable
fun YtLargeThumbnail(video: StreamItem, imageLoader: ImageLoader, modifier: Modifier = Modifier) {
    val large = remember(video.url) {
        video.url?.toID()?.let { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" }
    }
    var model by remember(video.url) { mutableStateOf(large ?: video.thumbnail) }
    AsyncImage(
        model = model,
        contentDescription = null,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
        onError = { if (model != video.thumbnail) model = video.thumbnail },
        modifier = modifier,
    )
}
