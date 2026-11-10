package com.github.libretube.ui.yt

import android.os.Bundle
import com.github.libretube.ui.activities.MainActivity

/**
 * YT: the main activity's lifecycle for the features of round 12. Package D fills it (the intent
 * chooser on a cold start and after 30 minutes away); empty, it changes nothing.
 */
object YtActivityHooks {
    /** At the end of onCreate, once the screens are set up (not when it finished early). */
    fun onCreate(activity: MainActivity, savedInstanceState: Bundle?) {
        // the time box's end card, over every screen
        YtTimeUpLayer.attach(activity)
    }

    fun onStart(activity: MainActivity) {
        // "Worauf hast du Lust?" on a cold start and after 30 minutes away
        YtIntentTrigger.onStart(activity)
        // device-gated one-time startup lag (no-op unless armed)
        com.github.libretube.helpers.YtDeviceEgg.onActivityForeground()
    }

    fun onStop(activity: MainActivity) {
        YtIntentTrigger.onStop(activity)
    }
}
