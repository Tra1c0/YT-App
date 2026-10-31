package com.github.libretube.ui.yt.player

import androidx.fragment.app.Fragment
import androidx.media3.session.MediaController
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.databinding.ExoStyledPlayerControlViewBinding
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.ui.yt.YtSeekReason

/**
 * YT: what the video player page offers the features of round 12 (the modules in
 * [YtPlayerModules]). PlayerFragment implements it; every method does what the page's own
 * buttons and gestures already do, so a feature never needs the page's private parts.
 * Main thread only. The getters are null while the page has no view or no controller yet.
 * (Their names differ from PlayerFragment's own members on purpose: a function named like a
 * property makes the property's references, e.g. ::videoId.isInitialized, ambiguous.)
 */
interface YtPlayerHost {
    /** The page itself (lifecycle, fragment managers, context). */
    fun fragment(): Fragment

    /** The connected media controller, or null before it connects. */
    fun controller(): MediaController?

    /** The page's views, or null without a view. */
    fun pageBinding(): FragmentPlayerBinding?

    /** The player's controls (time bar, buttons), or null without a view. */
    fun controls(): ExoStyledPlayerControlViewBinding?

    /** The video playing now, or null before the first one is known. */
    fun currentVideoId(): String?

    /** The details of the video playing now, or null while they load. */
    fun currentStreams(): Streams?

    /** Plays another video of the same kind (online or offline) in this player. */
    fun playVideo(videoId: String)

    /** Plays the previous video of the queue; false if there is none. */
    fun playPrev(): Boolean

    /** Plays [nextCandidate]; false if there is none. */
    fun playNext(): Boolean

    /**
     * What plays next: the next video of the queue (as the next button and autoplay pick it),
     * else the first related video that is not live while autoplay of related videos is on;
     * null otherwise.
     */
    fun nextCandidate(): StreamItem?

    /** Minimizes the page into the mini player (leaving fullscreen first), as back does. */
    fun minimize()

    /** Opens the mini player into the page again, as a tap on it does. */
    fun maximize()

    /** Closes the player, as its close button does. */
    fun close()

    /** Enters or leaves fullscreen, as the fullscreen button does. */
    fun toggleFullscreen()

    /** Continues as audio only (the audio player), as the background button does. */
    fun audioMode()

    /**
     * Shares the video at [seconds] (the current second if null): with the share dialog, or with
     * [direct] straight with the system's share sheet and the time in the link (?t=).
     */
    fun share(seconds: Long?, direct: Boolean)

    /** Seeks to [positionMs], telling YtPlaybackClock the seek is made for [reason]. */
    fun seekTagged(positionMs: Long, reason: YtSeekReason)

    /** Shows the controls without hiding them after a while ([on]), or lets them hide again. */
    fun showControlsPermanently(on: Boolean)
}
