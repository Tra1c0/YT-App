package com.github.libretube.ui.yt.feed

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.ui.models.HomeViewModel
import com.github.libretube.ui.yt.YtDiscovery
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.yt.YtFeedUi
import com.github.libretube.ui.yt.YtTestHandler
import com.github.libretube.ui.yt.YtTestHooks

/**
 * YT (package D): the emulator start parameters of the home checks (scripts/gestures/d.sh),
 * read by YtTestHooks.
 *
 * - `--ez yt_intent_test true`: the intent chooser opens on the home ("Worauf hast du Lust?"),
 *   which in the emulator never opens by itself.
 * - `--ez yt_feed_test true`: the Feed-Regler opens.
 */
object YtHomeTestHooks : YtTestHandler {
    private const val INTENT_TEST = "yt_intent_test"
    private const val FEED_TEST = "yt_feed_test"
    private const val HOME_FIXTURE = "yt_home_fixture"

    override val keys: List<String> = listOf(INTENT_TEST, FEED_TEST, HOME_FIXTURE)

    override suspend fun test(host: AbstractPlayerHostActivity, extras: Bundle) {
        if (extras.getBoolean(HOME_FIXTURE, false)) {
            val videos = (1..12).map { index ->
                StreamItem(
                    url = "/watch?v=ytFeed%05d".format(java.util.Locale.ROOT, index),
                    type = StreamItem.TYPE_STREAM,
                    title = if (index == 2) "Ein langer deutscher Videotitel über kleine Ideen und große Entdeckungen im Alltag"
                        else "Entdeckungen im Alltag – Folge $index",
                    thumbnail = "",
                    uploaderName = "Kanal $index",
                    uploaderUrl = "/channel/UCytFixture$index",
                    duration = 180L + index * 60L,
                    views = index * 1234L,
                    uploaded = System.currentTimeMillis() - index * 86_400_000L,
                )
            }
            ViewModelProvider(host)[HomeViewModel::class.java].useEmulatorFixture(videos)
            YtDiscovery.useEmulatorFixture(videos)
            YtTestHooks.log("YtTest") { "home fixture n=${videos.size}" }
        }
        if (extras.getBoolean(INTENT_TEST, false)) {
            YtFeedUi.chooser = true
            YtTestHooks.log("YtIntent") { "chooser shown intent=${YtFeed.intent.value}" }
        }
        if (extras.getBoolean(FEED_TEST, false)) YtFeedUi.openSheet()
    }
}
