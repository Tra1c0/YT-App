package com.github.libretube.ui.yt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * YT: what the navigation bar knows of the mini player docked on it. The bar squares its top
 * corners while a player sits on it (for the designs where both form one surface) and dips
 * together with the player when it lands.
 */
object YtDock {
    /** True while a mini player sits on the navigation bar. */
    var docked by mutableStateOf(false)

    /** How far bar and player dip together as the player lands, in px. */
    var dip by mutableFloatStateOf(0f)

    /**
     * How far the player is minimized, 0 (the page) to 1 (docked), following the finger and the
     * transition frame by frame. Read it in draw or layout lambdas, not in composition.
     */
    var progress by mutableFloatStateOf(0f)

    /** How far the long-press preview of the docked player has grown, 0 to 1. */
    var peek by mutableFloatStateOf(0f)

    /** How far the bar's slab reaches up behind the docked player, in px (0: the bar alone). */
    var slabPx by mutableFloatStateOf(0f)

    /** True while something dragged (a video card) hovers over the docked player. */
    var hover by mutableStateOf(false)

    /** Counts the drops the docked player accepted; each new value plays the accept animation. */
    var accepted by mutableIntStateOf(0)

    /** What the last accepted drop did, for the label of the accept animation. */
    var lastDrop by mutableStateOf<YtDropResult?>(null)
}

/**
 * What a drop on the docked player did: [next] if the video plays next, else it was appended;
 * [position] is its place among the videos still to come (1 = next); [title] the video's title.
 */
data class YtDropResult(val next: Boolean, val position: Int, val title: String)
