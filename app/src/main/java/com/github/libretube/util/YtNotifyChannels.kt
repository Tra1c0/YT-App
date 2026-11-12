package com.github.libretube.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.github.libretube.LibreTubeApp
import com.github.libretube.R

/**
 * YT: the two kinds of notifications the user can tell apart and switch separately, each with
 * its own Android channel: "YT-Updates" (new YT builds, [YtUpdateNotifier]) and LibreTube's
 * "Neue Videos" (new videos of subscribed channels, NotificationWorker, channel id
 * [LibreTubeApp.PUSH_CHANNEL_NAME]). The ids stay as they were, so the user's choices in Android
 * are kept; only the names are clear now.
 */
object YtNotifyChannels {
    /** YT builds; distinct from every LibreTube channel. */
    const val UPDATES = "yt_update_available"

    /** LibreTube's new-video notifications (its id, unchanged). */
    const val VIDEOS = LibreTubeApp.PUSH_CHANNEL_NAME

    fun ensureUpdates(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(UPDATES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.yt_r27_updnotify_channel_name))
                .setDescription(context.getString(R.string.yt_r27_updnotify_channel_description))
                .build()
        )
    }

    /** Android's page of one channel of YT. */
    fun channelPage(context: Context, channel: String): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)

    /** Android's page of all of YT's notifications. */
    fun appPage(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /**
     * Whether the [channel] is switched off in Android (or all of YT's notifications are);
     * null when it does not exist yet.
     */
    fun isBlocked(context: Context, channel: String): Boolean? {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return true
        val found = manager.getNotificationChannelCompat(channel) ?: return null
        return found.importance == NotificationManagerCompat.IMPORTANCE_NONE
    }
}
