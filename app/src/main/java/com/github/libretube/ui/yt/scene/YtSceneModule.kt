package com.github.libretube.ui.yt.scene

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.libretube.api.obj.Streams
import com.github.libretube.helpers.SceneMode
import com.github.libretube.ui.models.CommentScene
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.yt.YtPlaybackClock
import com.github.libretube.ui.yt.player.YtPlayerHost
import com.github.libretube.ui.yt.player.YtPlayerModule
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * YT (package E): the comments follow the scene that plays. Tells the activity's comment state
 * ([CommentScene]) which video plays, and while spoiler protection or the scene mode is on, feeds
 * it the shared playback clock and its seeks, from which it works out how far the viewer has
 * seen. With the mode off nothing is read, so the clock does not tick for this.
 */
object YtSceneModule : YtPlayerModule {
    private var feed: Job? = null

    private fun scene(host: YtPlayerHost): CommentScene? {
        val activity = host.fragment().activity ?: return null
        return ViewModelProvider(activity)[CommentsViewModel::class.java].scene
    }

    override fun onViewCreated(host: YtPlayerHost) {
        val scene = scene(host) ?: return
        // the settings screen may have changed the mode meanwhile
        scene.reloadMode()
        val owner = host.fragment().viewLifecycleOwner
        feed?.cancel()
        feed = owner.lifecycleScope.launch {
            // in the background no reading is needed: a gap of real playback counts when it comes back
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                scene.mode.collectLatest { mode ->
                    if (mode == SceneMode.OFF) return@collectLatest
                    coroutineScope {
                        launch {
                            YtPlaybackClock.seeks.collect { scene.onSeek(it, SystemClock.elapsedRealtime()) }
                        }
                        YtPlaybackClock.clock.collect { scene.onClock(it, SystemClock.elapsedRealtime()) }
                    }
                }
            }
        }
    }

    override fun onVideo(host: YtPlayerHost, videoId: String, streams: Streams) {
        scene(host)?.onVideo(videoId, streams)
    }

    override fun onDestroyView(host: YtPlayerHost) {
        feed?.cancel()
        feed = null
    }
}
