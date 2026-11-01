package com.github.libretube.ui.yt.player

import androidx.media3.common.util.UnstableApi
import com.github.libretube.api.obj.Streams
import com.github.libretube.ui.fragments.PlayerFragment

/**
 * YT (package C): the gestures on the player: rewind memory, the time magnifier, the loop, hold
 * and drag in four directions, sharing onto the timeline: the rewind memory (its chip and the
 * time bar's ghost, [YtRewindChip]), what the time magnifier needs from the page (whether the
 * video plays offline, for its live picture; the magnifier itself lives in the time bar), the
 * explanation loop's page side ([YtLoopController]; the loop runs in YtLoopModule), hold and
 * drag on the video ([YtHoldGesture]) and sharing onto the timeline ([YtShareScrub]: the bridge
 * the designs' pages get, and the classic share button).
 */
@androidx.annotation.OptIn(UnstableApi::class)
object YtPlayerGestureModule : YtPlayerModule {
    // per page: a new page can come before the old one has let go of its views
    private val rewinds = HashMap<YtPlayerHost, YtRewindChip>()
    private val loops = HashMap<YtPlayerHost, YtLoopController>()
    private val holds = HashMap<YtPlayerHost, YtHoldGesture>()
    private val shares = HashMap<YtPlayerHost, YtShareScrub>()

    override fun onViewCreated(host: YtPlayerHost) {
        rewinds.remove(host)?.release()
        loops.remove(host)?.release()
        holds.remove(host)?.release()
        shares.remove(host)?.release()
        val page = host.pageBinding() ?: return
        // isOffline is known only after the view exists, so it is asked when a scrub begins
        page.player.binding.exoProgress.playsOffline = {
            (host.fragment() as? PlayerFragment)?.isOffline == true
        }
        rewinds[host] = YtRewindChip(host, page.player)
        loops[host] = YtLoopController(host, page.player)
        holds[host] = YtHoldGesture(
            host,
            page.player,
            returnPoint = { rewinds[host]?.returnNow() == true },
            loop = { loops[host] },
        )
        // the page asks for the bridge (shareScrub) after this, for the same view; the setting
        // counts from the next page on
        if (YtPlayerGesturePrefs.shareDrag) {
            shares[host] = YtShareScrub(host).also { it.attachClassic(page.relPlayerShare) }
        }
    }

    override fun shareScrub(host: YtPlayerHost): YtShareScrubBridge? = shares[host]

    override fun onVideo(host: YtPlayerHost, videoId: String, streams: Streams) {
        rewinds[host]?.onVideo(videoId)
        loops[host]?.onVideo(videoId)
    }

    override fun onDestroyView(host: YtPlayerHost) {
        rewinds.remove(host)?.release()
        loops.remove(host)?.release()
        holds.remove(host)?.release()
        shares.remove(host)?.release()
        host.pageBinding()?.player?.binding?.exoProgress?.playsOffline = { false }
    }
}
