package com.github.libretube.ui.yt.mini

import androidx.media3.session.MediaController
import com.github.libretube.api.obj.Streams
import com.github.libretube.ui.yt.player.YtPlayerHost
import com.github.libretube.ui.yt.player.YtPlayerModule

/**
 * YT (package A): the mini player of each design on the player page: its gestures (long-press
 * preview, double tap), the next video it shows and the drop target's feedback. The form itself
 * comes earlier, from YtMiniPlayer.attach.
 */
object YtMiniModule : YtPlayerModule {
    override fun onViewCreated(host: YtPlayerHost) {
        YtMiniHost.of(host)?.bind(host)
    }

    override fun onController(host: YtPlayerHost, controller: MediaController) {
        YtMiniHost.of(host)?.refreshNext()
    }

    override fun onVideo(host: YtPlayerHost, videoId: String, streams: Streams) {
        // the related videos arrived: autoplay's pick may have changed
        YtMiniHost.of(host)?.refreshNext()
    }

    override fun onDestroyView(host: YtPlayerHost) {
        YtMiniHost.of(host)?.unbind()
    }
}
