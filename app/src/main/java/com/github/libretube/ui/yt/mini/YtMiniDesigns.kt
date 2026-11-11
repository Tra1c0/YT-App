package com.github.libretube.ui.yt.mini

import com.github.libretube.helpers.YtDesign

/** YT: the mini player of each design (one object per design, each in its own file). */
object YtMiniDesigns {
    fun of(design: YtDesign): YtMiniDesign = when (design) {
        YtDesign.STANDARD -> YtMiniStandard
        YtDesign.YOUTUBE -> YtMiniYouTube
        YtDesign.MOVO -> YtMiniMovo
        YtDesign.AFTER -> YtMiniAfter
        YtDesign.SIDE -> YtMiniSide
        YtDesign.CLAY -> YtMiniClay
        YtDesign.EDITORIAL -> YtMiniEditorial
        YtDesign.GLASS -> YtMiniGlass
        YtDesign.POP -> YtMiniPop
        YtDesign.SHAPES -> YtMiniShapes
        YtDesign.FROST -> YtMiniFrost
        YtDesign.KIESEL -> YtMiniKiesel
        YtDesign.FREQUENCY -> YtMiniFrequency
        YtDesign.SYNTHWAVE -> YtMiniSynthwave
    }

    /** The mini player of the selected design. */
    fun current(): YtMiniDesign = of(YtDesign.current())
}
