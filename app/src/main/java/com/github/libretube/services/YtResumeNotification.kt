package com.github.libretube.services

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import com.github.libretube.LibreTubeApp.Companion.PLAYER_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.enums.NotificationId

/**
 * YT: the note "Wiedergabe pausiert – zum Fortsetzen tippen" for the case the player service could
 * not go into the foreground (Android 12+ forbids that while the app is in the background, see
 * YtForegroundHold). Without the foreground the system may stop the service at any time, so the
 * service pauses and this note brings the user back to the player with one tap: a tap on a
 * notification lets the app start its foreground service again.
 *
 * The tap opens the player screen rather than playing straight away: a service started for the
 * foreground has to reach it within seconds, and from a stale player state it might not.
 */
object YtResumeNotification {
    private val id get() = NotificationId.PLAYER_RESUME.id

    fun show(context: Context, playerIntent: Intent, title: CharSequence?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tap = PendingIntentCompat.getActivity(
            context, id, playerIntent, PendingIntent.FLAG_UPDATE_CURRENT, false
        )
        val notification = NotificationCompat.Builder(context, PLAYER_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle(context.getString(R.string.yt_play_resume_title))
            .setContentText(title ?: context.getString(R.string.yt_play_resume_text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /** True if [e] is Android's refusal to start a foreground service from the background. */
    fun isStartNotAllowed(e: Throwable): Boolean =
        Build.VERSION.SDK_INT >= 31 && Api31.isStartNotAllowed(e)

    @RequiresApi(31)
    private object Api31 {
        fun isStartNotAllowed(e: Throwable) = e is ForegroundServiceStartNotAllowedException
    }
}
