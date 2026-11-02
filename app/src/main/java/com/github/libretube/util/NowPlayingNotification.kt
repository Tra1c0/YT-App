package com.github.libretube.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.PendingIntentCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.github.libretube.LibreTubeApp.Companion.PLAYER_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.NotificationId
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.helpers.PlayerHelper
import com.google.common.collect.ImmutableList

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NowPlayingNotification(
    private val context: Context,
    var notificationIntent: Intent = Intent(),
    private val onDismissed: () -> Unit = {},
): MediaNotification.Provider {
    private val nProvider = DefaultMediaNotificationProvider.Builder(context)
        .setNotificationId(NotificationId.PLAYER_PLAYBACK.id)
        .setChannelId(PLAYER_CHANNEL_NAME)
        .setChannelName(R.string.player_channel_name)
        .build()

    private fun createCurrentContentIntent(): PendingIntent? {
        // starts a new MainActivity Intent when the player notification is clicked
        // it doesn't start a completely new MainActivity because the MainActivity's launchMode
        // is set to "singleTop" in the AndroidManifest (important!!!)
        // that's the only way to launch back into the previous activity (e.g. the player view)


        return PendingIntentCompat
            .getActivity(context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT, false)
    }

    /**
     * Forward the action to the responsible notification owner (e.g. PlayerFragment)
     */
    private fun handlePlayerAction(action: PlayerEvent) {
        val intent = Intent(PlayerHelper.getIntentActionName(context))
            .setPackage(context.packageName)
            .putExtra(PlayerHelper.CONTROL_TYPE, action)
        context.sendBroadcast(intent)
    }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        createCurrentContentIntent()?.let { mediaSession.setSessionActivity(it) }
        nProvider.setSmallIcon(R.drawable.ic_launcher_lockscreen)
        // SystemUI can deliver an old notification's dismissal after a new item has
        // started. Give each dismissal its own immutable action and media identity.
        val dismissalFactory = object : MediaNotification.ActionFactory by actionFactory {
            override fun createNotificationDismissalIntent(session: MediaSession): PendingIntent {
                val metadata = session.player.mediaMetadata
                val extras = Bundle().apply {
                    putString(IntentData.videoId, metadata.extras?.getString(IntentData.videoId))
                    metadata.trackNumber?.let { putInt(DISMISS_TRACK, it) }
                }
                return requireNotNull(actionFactory.createCustomAction(
                    session, IconCompat.createWithResource(context, R.drawable.ic_launcher_lockscreen),
                    "", DISMISS_ACTION, extras
                ).actionIntent)
            }
        }
        return nProvider.createNotification(mediaSession, customLayout, dismissalFactory, onNotificationChangedCallback)
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean {
        if (action == DISMISS_ACTION) {
            val metadata = session.player.mediaMetadata
            val capturedTrack = if (extras.containsKey(DISMISS_TRACK)) extras.getInt(DISMISS_TRACK) else null
            if (YtNotificationPlayback.matches(
                    extras.getString(IntentData.videoId), capturedTrack,
                    metadata.extras?.getString(IntentData.videoId), metadata.trackNumber
                )) onDismissed()
            return true
        }
        runCatching { handlePlayerAction(PlayerEvent.valueOf(action)) }
        return true
    }

    private companion object {
        const val DISMISS_ACTION = "yt.dismiss_playback_notification"
        const val DISMISS_TRACK = "yt.notification_track"
    }
}
