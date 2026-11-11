package com.github.libretube.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.github.libretube.R
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.util.YtUpdateNotifyRules.Notice
import com.github.libretube.util.YtUpdateNotifyRules.Outcome
import com.github.libretube.workers.YtUpdateCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * YT: "tell me when there is a new build". A periodic WorkManager job ([YtUpdateCheckWorker])
 * asks GitHub for the latest release, the same call and rule as the in-app check
 * ([UpdateChecker.newerRelease]), and posts one notification per newer build. No server push:
 * the job runs only with a network and while the battery is not low, every 3 to 24 hours.
 *
 * WorkManager keeps the job over reboots (its own boot receiver) and app updates (JobScheduler
 * keeps jobs; every process start, also the one for MY_PACKAGE_REPLACED, calls [schedule]).
 *
 * Tapping the notification (or "Jetzt laden") opens [MainActivity], which runs the in-app check
 * again and shows the usual update dialog; download and install stay as they are.
 */
object YtUpdateNotifier {
    private const val TAG = "YtUpdateNotifier"
    private const val WORK_NAME = "YtUpdateCheck"
    private const val CHANNEL_ID = "yt_update_available"
    private const val NOTIFICATION_ID = 0x7956

    /** Extra of the notification's intent: the build it told about. */
    private const val EXTRA_BUILD = "yt_update_notify_build"

    private const val PREFS = "yt_update_notify"
    private const val KEY_LAST_RUN = "last_run"
    private const val KEY_LAST_RESULT = "last_result"
    private const val KEY_LAST_NOTIFIED = "last_notified"
    private const val KEY_SCHEDULED_HOURS = "scheduled_hours"
    private const val KEY_SCHEDULED_AT = "scheduled_at"
    private const val KEY_ASKED_PERMISSION = "asked_permission"

    /** Why a notification cannot be shown now. */
    enum class Block {
        NONE,

        /** Android 13+: the notification permission is not granted (yet). */
        PERMISSION,

        /** Notifications of YT are switched off in the system settings. */
        APP_OFF,

        /** Only the "App-Updates" category of this notification is switched off. */
        CHANNEL_OFF,
    }

    fun enabled(): Boolean = PreferenceHelper.getBoolean(
        YtUpdateNotifyRules.KEY_ENABLED, YtUpdateNotifyRules.DEFAULT_ENABLED
    )

    fun intervalHours(): Int = YtUpdateNotifyRules.intervalHours(
        PreferenceHelper.getString(
            YtUpdateNotifyRules.KEY_INTERVAL, YtUpdateNotifyRules.DEFAULT_HOURS.toString()
        )
    )

    /**
     * Schedules the periodic check, or cancels it when switched off. The existing schedule is
     * kept (KEEP) unless the interval changed (UPDATE keeps its timing but takes the new period).
     * Called on every app start and when a setting changes; [on] and [hours] are the new values
     * while a preference is still being written.
     */
    fun schedule(context: Context, on: Boolean = enabled(), hours: Int = intervalHours()) {
        val app = context.applicationContext
        runCatching {
            ensureChannel(app)
            clearOutdated(app)
            val work = WorkManager.getInstance(app)
            val prefs = prefs(app)
            if (!on) {
                work.cancelUniqueWork(WORK_NAME)
                NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
                prefs.edit { remove(KEY_SCHEDULED_HOURS).remove(KEY_SCHEDULED_AT) }
                return
            }
            val scheduled = prefs.getInt(KEY_SCHEDULED_HOURS, 0).takeIf { it > 0 }
            val replace = YtUpdateNotifyRules.mustReplace(scheduled, hours)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<YtUpdateCheckWorker>(
                YtUpdateNotifyRules.intervalMinutes(hours), TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            work.enqueueUniquePeriodicWork(
                WORK_NAME,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            if (scheduled != hours) {
                prefs.edit {
                    putInt(KEY_SCHEDULED_HOURS, hours)
                    putLong(KEY_SCHEDULED_AT, System.currentTimeMillis())
                }
            }
        }.onFailure { Log.w(TAG, "could not schedule the update check", it) }
    }

    private fun ensureChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.yt_r27_updnotify_channel_name))
                .setDescription(context.getString(R.string.yt_r27_updnotify_channel_description))
                .build()
        )
    }

    /** Why YT cannot show the notification now; [Block.NONE] when it can. */
    fun block(context: Context): Block {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Block.PERMISSION
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return Block.APP_OFF
        val channel = manager.getNotificationChannelCompat(CHANNEL_ID)
        if (channel != null && channel.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            return Block.CHANNEL_OFF
        }
        return Block.NONE
    }

    /** Whether the permission dialog was shown once; afterwards Android may refuse to show it. */
    fun askedForPermission(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_PERMISSION, false)

    fun markAskedForPermission(context: Context) =
        prefs(context).edit { putBoolean(KEY_ASKED_PERMISSION, true) }

    /** The system page for YT's notifications, or for the "App-Updates" category alone. */
    fun settingsIntent(context: Context, channel: Boolean): Intent =
        if (channel) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

    /**
     * A run found the newer [build] (installed: [installed]); posts its notification when this
     * build was not told yet and notifications can be shown. Only a posted notification counts
     * as told, so a later run tells it once the user allows notifications.
     */
    fun offer(context: Context, installed: Int?, build: Int, body: String?): Notice {
        if (!YtUpdateNotifyRules.shouldNotify(installed, build, lastNotified(context))) return Notice.ALREADY
        if (block(context) != Block.NONE) return Notice.BLOCKED
        ensureChannel(context)
        if (!post(context, build, body)) return Notice.BLOCKED
        prefs(context).edit { putInt(KEY_LAST_NOTIFIED, build) }
        return Notice.POSTED
    }

    @SuppressLint("MissingPermission") // checked in block() right before
    private fun post(context: Context, build: Int, body: String?): Boolean {
        val text = YtUpdateNotifyRules.notificationText(body)
        val short = text?.short ?: context.getString(R.string.yt_notes_fallback)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_BUILD, build)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, flags)
        // its own request code: one PendingIntent per entry point, both open the same dialog
        val action = PendingIntent.getActivity(context, NOTIFICATION_ID + 1, Intent(open), flags)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle(context.getString(R.string.yt_r27_updnotify_title, build))
            .setContentText(short)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text?.long ?: short))
            .setContentIntent(tap)
            .addAction(R.drawable.ic_download, context.getString(R.string.yt_r27_updnotify_action), action)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // the same id: a newer build replaces the notification of an older one
        return runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "could not post", it) }
            .isSuccess
    }

    /** The build that got a notification last (or was seen in the update dialog), if any. */
    fun lastNotified(context: Context): Int? =
        prefs(context).getInt(KEY_LAST_NOTIFIED, 0).takeIf { it > 0 }

    /**
     * The update dialog showed [build] in the app: no notification for it any more, and one
     * still showing for it (or an older build) goes away.
     */
    fun markSeen(context: Context, build: Int) {
        runCatching {
            val prefs = prefs(context)
            val last = prefs.getInt(KEY_LAST_NOTIFIED, 0)
            if (build > last) prefs.edit { putInt(KEY_LAST_NOTIFIED, build) }
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    /** After an update: a notification about the installed (or an older) build is gone. */
    private fun clearOutdated(context: Context) {
        if (YtUpdateNotifyRules.isOutdated(UpdateChecker.installedBuild(), lastNotified(context))) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    /** Called by the worker after every run, for the diagnosis page. */
    fun record(context: Context, at: Long, outcome: Outcome) {
        clearOutdated(context)
        prefs(context).edit {
            putLong(KEY_LAST_RUN, at)
            putString(KEY_LAST_RESULT, YtUpdateNotifyRules.outcomeText(outcome))
        }
    }

    /** Whether MainActivity was opened from the notification (the start check then waits). */
    fun isFromNotification(intent: Intent?): Boolean = intent != null &&
        intent.hasExtra(EXTRA_BUILD) &&
        // reopened from the recent apps after the process was gone: the old intent comes again
        intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0

    /**
     * MainActivity got an intent: when it comes from the notification, check again and show the
     * update dialog (the newest build, which includes the told one's notes). The extra is
     * removed so a recreated activity does not show it twice. True when it was handled.
     */
    fun handleIntent(activity: FragmentActivity, intent: Intent?): Boolean {
        if (!isFromNotification(intent)) return false
        intent?.removeExtra(EXTRA_BUILD)
        NotificationManagerCompat.from(activity).cancel(NOTIFICATION_ID)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            UpdateChecker(activity).checkUpdate(isManualCheck = true)
        }
        return true
    }

    /**
     * The diagnosis line: last run, its result, the next run (WorkManager's own time when it
     * answers within a moment, else an estimate) and why a notification could not show.
     */
    fun diagnosis(context: Context): String {
        if (!enabled()) return "off"
        val prefs = prefs(context)
        val lastRun = prefs.getLong(KEY_LAST_RUN, 0).takeIf { it > 0 }
        val result = prefs.getString(KEY_LAST_RESULT, null) ?: "-"
        val hours = intervalHours()
        val info = runCatching {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME)
                .get(300, TimeUnit.MILLISECONDS)
                .firstOrNull { !it.state.isFinished }
        }.getOrNull()
        val next = info?.nextScheduleTimeMillis?.takeIf { it in 1 until Long.MAX_VALUE }
            ?: YtUpdateNotifyRules.nextRunEstimate(
                lastRun, prefs.getLong(KEY_SCHEDULED_AT, 0), YtUpdateNotifyRules.intervalMinutes(hours)
            )
        val state = when {
            info == null -> ", not scheduled"
            info.state == WorkInfo.State.RUNNING -> ", running"
            else -> ""
        }
        val block = block(context).takeIf { it != Block.NONE }?.let { ", notifications: ${it.name.lowercase()}" }.orEmpty()
        return "last run ${lastRun?.let(YtCrashLog::formatTime) ?: "never"}, result $result, " +
            "next ≈ ${next?.let(YtCrashLog::formatTime) ?: "?"}, every ${hours} h$state$block"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
