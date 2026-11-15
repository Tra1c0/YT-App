package com.github.libretube

import android.app.Application
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NewPipeExtractorInstance
import com.github.libretube.helpers.NotificationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ShortcutHelper
import com.github.libretube.util.ExceptionHandler

class LibreTubeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // YT: the archive's script part (dialogue script, audio analysis) registers itself
        com.github.libretube.ui.yt.archive.script.YtArchiveScripts.register()

        /**
         * Initialize the needed notification channels for DownloadService and BackgroundMode
         */
        initializeNotificationChannels()

        /**
         * Initialize the [PreferenceHelper]
         */
        PreferenceHelper.initialize(applicationContext)
        PreferenceHelper.migrate()
        // YT: the own design's theme (or, after a crash in it, back to the design before)
        com.github.libretube.ui.yt.builder.YtBuilderSafeMode.onAppStart(this)

        /**
         * Set the api and the auth api url
         */
        ImageHelper.initializeImageLoader(this)

        /**
         * Initialize the notification listener in the background
         */
        NotificationHelper.enqueueWork(
            context = this,
            existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
        )

        // YT: the background check for a new YT build (kept as scheduled; off cancels it)
        com.github.libretube.util.YtUpdateNotifier.schedule(this)

        /**
         * Handler for uncaught exceptions
         */
        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        val exceptionHandler = ExceptionHandler(defaultExceptionHandler)
        Thread.setDefaultUncaughtExceptionHandler(exceptionHandler)

        /**
         * Dynamically create App Shortcuts
         */
        ShortcutHelper.createShortcuts(this)

        NewPipeExtractorInstance.init()

        // YT: a sign-in window that was killed may have left Google cookies; they go now
        runCatching { com.github.libretube.ui.yt.account.YtAccount.wipeLeftovers() }

        // YT: the playing queue is saved on every change and put back after a restart
        com.github.libretube.util.YtQueueStore.start(this)

        // YT: the archive's risk estimator (learns from vanished videos; reads its file on first use)
        com.github.libretube.ui.yt.archive.risk.YtArchiveRisk.install()
        com.github.libretube.ui.yt.archive.YtArchive.schedule(this) // YT: archive jobs (round 31), only while switched on
    }

    /**
     * Initializes the required notification channels for the app.
     */
    private fun initializeNotificationChannels() {
        val downloadChannel = NotificationChannelCompat.Builder(
            PLAYLIST_DOWNLOAD_ENQUEUE_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.download_playlist))
            .setDescription(getString(R.string.enqueue_playlist_description))
            .build()
        val playlistDownloadEnqueueChannel = NotificationChannelCompat.Builder(
            DOWNLOAD_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.download_channel_name))
            .setDescription(getString(R.string.download_channel_description))
            .build()
        val playerChannel = NotificationChannelCompat.Builder(
            PLAYER_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.player_channel_name))
            .setDescription(getString(R.string.player_channel_description))
            .build()
        val pushChannel = NotificationChannelCompat.Builder(
            PUSH_CHANNEL_NAME,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            // YT: named "Neue Videos", apart from YT's own "YT-Updates" (YtNotifyChannels)
            .setName(getString(R.string.yt_r30_channel_videos_name))
            .setDescription(getString(R.string.yt_r30_channel_videos_description))
            .build()

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.createNotificationChannelsCompat(
            listOf(
                downloadChannel,
                playlistDownloadEnqueueChannel,
                pushChannel,
                playerChannel
            )
        )
    }

    companion object {
        lateinit var instance: LibreTubeApp

        const val DOWNLOAD_CHANNEL_NAME = "download_service"
        const val PLAYLIST_DOWNLOAD_ENQUEUE_CHANNEL_NAME = "playlist_download_enqueue"
        const val PLAYER_CHANNEL_NAME = "player_mode"
        const val PUSH_CHANNEL_NAME = "notification_worker"
    }
}
