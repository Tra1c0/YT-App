package com.github.libretube.ui.models

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import com.github.libretube.api.obj.Segment
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.ui.yt.player.YtLoop

@UnstableApi
class PlayerViewModel : ViewModel() {

    var segments = MutableLiveData<List<Segment>>()
    // this is only used to restore the subtitle after leaving PiP, the actual caption state
    // should always be read from the player's selected tracks!
    var currentCaptionId: String? = null
    var sponsorBlockConfig = PlayerHelper.getSponsorBlockCategories()

    /**
     * Whether an orientation change is in progress, so that the current player should be continued to use
     *
     * Set to true if the activity will be recreated due to an orientation change
     */
    var isOrientationChangeInProgress = false

    // YT: whether the page last came to rest docked; a page created again for the same player
    // (rotation, a new look) starts where this one was instead of opening
    var ytDocked = false

    // YT: the page is rebuilt because fullscreen was left in landscape: it stays windowed even
    // with auto fullscreen (read once by the rebuilt page)
    var ytLeftFullscreen = false

    // YT: the explanation loop as the page last saw it (the service runs it), and an A set in
    // its sheet that waits for its B (-1: none)
    var ytLoop: YtLoop? = null
    var ytLoopDraftAMs: Long = -1L
}