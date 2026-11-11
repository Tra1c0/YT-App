package com.github.libretube.ui.yt

import android.os.Bundle
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.yt.feed.YtTopicSession

/**
 * YT: the main activity's lifecycle for the features of round 12. Package D fills it (the intent
 * chooser on a cold start and after 30 minutes away); empty, it changes nothing.
 */
object YtActivityHooks {
    /** At the end of onCreate, once the screens are set up (not when it finished early). */
    fun onCreate(activity: MainActivity, savedInstanceState: Bundle?) {
        // the time box's end card, over every screen
        YtTimeUpLayer.attach(activity)
        // the queue kept over a restart goes back (with its resume pill) if nothing else plays
        YtQueueResume.onActivityCreated(activity, savedInstanceState)
    }

    fun onStart(activity: MainActivity) {
        // "Worauf hast du Lust?" on a cold start and after 30 minutes away
        YtIntentTrigger.onStart(activity)
        // a topic set to "Nur diese Sitzung" ends after half an hour away
        YtTopicSession.onStart()
    }

    fun onStop(activity: MainActivity) {
        YtIntentTrigger.onStop(activity)
        // the app may be killed in the background from now on: the queue goes to disk at once
        com.github.libretube.util.YtQueueStore.flush()
        if (!activity.isChangingConfigurations) YtTopicSession.onStop()
    }
}
