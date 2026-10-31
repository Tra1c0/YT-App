package com.github.libretube.ui.yt.player

import androidx.media3.common.util.UnstableApi
import com.github.libretube.api.obj.Streams
import com.github.libretube.ui.fragments.PlayerFragment

/**
 * YT (package C): the gestures on the player: rewind memory, the time magnifier, the loop, hold
 * and drag in four directions, sharing onto the timeline. So far the rewind memory (its chip and
 * the time bar's ghost, [YtRewindChip]) and what the time magnifier needs from the page (whether
 * the video plays offline, for its live picture); the magnifier itself lives in the time bar.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object YtPlayerGestureModule : YtPlayerModule {
    // per page: a new page can come before the old one has let go of its views
    private val rewinds = HashMap<YtPlayerHost, YtRewindChip>()

    override fun onViewCreated(host: YtPlayerHost) {
        rewinds.remove(host)?.release()
        val page = host.pageBinding() ?: return
        // isOffline is known only after the view exists, so it is asked when a scrub begins
        page.player.binding.exoProgress.playsOffline = {
            (host.fragment() as? PlayerFragment)?.isOffline == true
        }
        rewinds[host] = YtRewindChip(host, page.player)
    }

    override fun onVideo(host: YtPlayerHost, videoId: String, streams: Streams) {
        rewinds[host]?.onVideo(videoId)
    }

    override fun onDestroyView(host: YtPlayerHost) {
        rewinds.remove(host)?.release()
        host.pageBinding()?.player?.binding?.exoProgress?.playsOffline = { false }
    }
}
