package com.github.libretube.ui.yt

import android.content.Intent
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.YtDesign
import com.github.libretube.helpers.YtGuard
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.yt.feed.YtIntentAsk

/**
 * YT: when "Worauf hast du Lust?" opens by itself: on a cold start and on coming back after 30
 * minutes away (setting yt_intent_ask: every time, after 30 minutes, never). Never over a link,
 * a search or a channel the app was opened with, never with an open player, never with
 * yt_quiet, only on the home of a design screen. The first start after the installation
 * belongs to the welcome sheet. In the emulator only the checks open it (yt_intent_test), so the
 * other checks' screenshots never meet it.
 */
object YtIntentTrigger {
    const val ASK_KEY = "yt_intent_ask"
    private const val LAST_STOP_KEY = "yt_intent_last_stop"

    // a delayed look, so a link that arrives with the start (onNewIntent) has opened its player
    private const val SETTLE_MS = 400L

    /** True between onStart and a real onStop: a screen recreated for a rotation asks nothing. */
    private var visible = false

    /** The intent already looked at; a link only blocks when it is new. */
    private var seenIntent: Intent? = null

    /** False until the first onStart of this process: a cold start asks after a minute away. */
    private var started = false

    fun mode(): YtIntentAsk.Mode = YtIntentAsk.Mode.of(PreferenceHelper.getString(ASK_KEY, YtIntentAsk.Mode.AFTER_30.key))

    fun onStart(activity: MainActivity) {
        if (visible) return
        visible = true
        val cold = !started
        started = true
        val lastStop = PreferenceHelper.getLong(LAST_STOP_KEY, 0L).takeIf { it > 0L }
        activity.window.decorView.postDelayed({ decide(activity, lastStop, cold) }, SETTLE_MS)
    }

    fun onStop(activity: MainActivity) {
        if (activity.isChangingConfigurations) return
        visible = false
        PreferenceHelper.putLong(LAST_STOP_KEY, System.currentTimeMillis())
        // what was not answered is not asked again later out of the blue
        YtFeedUi.chooser = false
    }

    private fun decide(activity: MainActivity, lastStop: Long?, cold: Boolean) {
        if (activity.isFinishing || activity.isDestroyed || !visible) return
        if (YtGuard.isEmulator) return
        val intent = activity.intent
        val fresh = intent != null && intent !== seenIntent
        seenIntent = intent
        val blocked = (fresh && opensSomething(intent)) || playerOpen(activity) || !onHome(activity)
        val ask = YtIntentAsk.shouldAsk(mode(), System.currentTimeMillis(), lastStop, blocked, coldStart = cold)
        YtTestHooks.log("YtIntent") { "decide ask=$ask blocked=$blocked cold=$cold mode=${mode()}" }
        if (ask) YtFeedUi.chooser = true
    }

    /** A link, a search, a channel, a shortcut or yt_quiet in the intent. */
    private fun opensSomething(intent: Intent): Boolean {
        if (intent.data != null) return true
        if (intent.getBooleanExtra("yt_quiet", false)) return true
        val keys = listOf(
            IntentData.videoId, IntentData.videoIds, IntentData.channelId, IntentData.channelName,
            IntentData.playlistId, IntentData.query, IntentData.maximizePlayer, IntentData.OPEN_DOWNLOADS,
            IntentData.fragmentToOpen,
        )
        return keys.any(intent::hasExtra)
    }

    /** A player open over the screen (a docked mini player leaves the home in view). */
    private fun playerOpen(activity: MainActivity): Boolean {
        val player = activity.runOnPlayerFragment { true } || activity.runOnAudioPlayerFragment { true }
        return player && !YtDock.docked
    }

    private fun onHome(activity: MainActivity): Boolean =
        YtDesign.current() != YtDesign.STANDARD &&
            runCatching { activity.navController.currentDestination?.id == R.id.homeFragment }.getOrDefault(false)
}
