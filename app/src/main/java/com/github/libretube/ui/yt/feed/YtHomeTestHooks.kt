package com.github.libretube.ui.yt.feed

import android.os.Bundle
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

    override val keys: List<String> = listOf(INTENT_TEST, FEED_TEST)

    override suspend fun test(host: AbstractPlayerHostActivity, extras: Bundle) {
        if (extras.getBoolean(INTENT_TEST, false)) {
            YtFeedUi.chooser = true
            YtTestHooks.log("YtIntent") { "chooser shown intent=${YtFeed.intent.value}" }
        }
        if (extras.getBoolean(FEED_TEST, false)) YtFeedUi.openSheet()
    }
}
