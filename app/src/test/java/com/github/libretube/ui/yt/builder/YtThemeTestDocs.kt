package com.github.libretube.ui.yt.builder

/** A theme document for the tests, from the neutral seed. */
internal fun testDoc(id: String = "t1"): YtThemeDoc = YtThemeSeeds.create(
    YtThemeSeeds.YtSeed(design = "youtube", roles = emptyMap()),
    id = id,
    name = "Test",
)
