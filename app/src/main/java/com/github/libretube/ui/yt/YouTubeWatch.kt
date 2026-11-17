package com.github.libretube.ui.yt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.github.libretube.R
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.Streams
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toID
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.yt.player.YtShareScrubBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of the video page that the player fragment updates. [comments] (package E) gives the
 * page the comments of the video playing.
 */
class YtWatchController(
    val comments: CommentsViewModel? = null,
) {
    var videoId by mutableStateOf<String?>(null)
    var streams by mutableStateOf<Streams?>(null)
    var chapterIndex by mutableIntStateOf(-1)
}

/** Actions of the video page; most reuse the existing buttons of the classic layout. */
class YtWatchActions(
    val onSeek: (Long) -> Unit,
    val onSave: () -> Unit,
    val onShare: () -> Unit,
    val onDownload: () -> Unit,
    val onAudioOnly: () -> Unit,
    val onComments: () -> Unit,
    val onOpenChannel: () -> Unit,
    val onShowDescription: (Boolean) -> Unit,
    val currentPosition: () -> Long = { 0L },
    /** Opens a link or jumps to a timestamp of the description. */
    val onLink: (String) -> Unit = {},
    /** Dragging the share action onto the timeline (package C); null: share is a plain button. */
    val shareScrub: YtShareScrubBridge? = null,
)
