package com.github.libretube.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.libretube.R
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.util.YtPushRules.Event
import com.github.libretube.util.YtPushRules.Status
import com.github.libretube.workers.YtUpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor
import java.util.concurrent.TimeUnit

/**
 * YT: the instant push for new builds over UnifiedPush (a distributor app such as ntfy; no Google
 * services). The CI posts a tiny message to the push address when a build of main starts and
 * when its release is out; [com.github.libretube.services.YtPushService] hands it here.
 *
 * A push is only a trigger, never data: "published" runs the normal GitHub check at once
 * ([YtUpdateCheckWorker.MODE_PUSH]), and only a newer release that check confirms gets the usual
 * notification; "started" starts the attention mode ([YtUpdateWatch]) without any notification.
 * Everything else is ignored, and pushes are rate-limited ([YtPushRules.mayCheck]).
 *
 * The push address is a capability (whoever knows it can send pushes): it is never logged,
 * the crash text masks it ([YtCrashText.scrub]), the diagnosis says only "registered", and its
 * store (prefs [PREFS]) is left out of backups.
 */
object YtPush {
    private const val TAG = "YtPush"

    /** The UnifiedPush registration of YT's update push (one per app). */
    const val INSTANCE = "yt-updates"

    /** Where to get the distributor: ntfy on F-Droid (no Google services). */
    const val NTFY_URL = "https://f-droid.org/packages/io.heckel.ntfy/"

    private const val PUSH_WORK_NAME = "YtUpdatePushCheck"

    const val PREFS = "yt_push"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_CHANGED = "endpoint_changed"
    private const val KEY_FAILED = "failed"
    private const val KEY_LAST_EVENT = "last_event"
    private const val KEY_LAST_EVENT_AT = "last_event_at"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_WINDOW_START = "window_start"
    private const val KEY_WINDOW_COUNT = "window_count"
    private const val KEY_LAST_REFRESH = "last_refresh"

    /** A working registration is renewed at most this often. */
    private const val REFRESH_GAP_MS = 6 * 3_600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enabled(): Boolean = PreferenceHelper.getBoolean(YtPushRules.KEY_ENABLED, YtPushRules.DEFAULT_ENABLED)

    /** The installed UnifiedPush distributors (package names), without YT itself. */
    fun distributors(context: Context): List<String> =
        runCatching { UnifiedPush.getDistributors(context).filter { it != context.packageName } }
            .getOrDefault(emptyList())

    /** The push address, only for "copy the address"; never log or show it. */
    fun endpoint(context: Context): String? = prefs(context).getString(KEY_ENDPOINT, null)

    /** Whether the address changed after it was copied: the CI secret must be replaced. */
    fun endpointChanged(context: Context): Boolean = prefs(context).getBoolean(KEY_CHANGED, false)

    fun status(context: Context, on: Boolean = enabled()): Status = YtPushRules.status(
        on,
        distributors(context).isNotEmpty(),
        endpoint(context) != null,
        prefs(context).getString(KEY_FAILED, null) != null
    )

    /** The distributor's name as Android shows it ("ntfy"), or its package name. */
    fun distributorName(context: Context): String? {
        val pkg = runCatching { UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context) }
            .getOrNull() ?: distributors(context).firstOrNull() ?: return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /**
     * The switch was turned on (or the status row tapped): picks the distributor (the user's
     * default, or Android's chooser when there are several) and registers. Without a
     * distributor nothing happens; the settings show the ntfy hint. [done] runs afterwards.
     */
    fun enable(activity: Activity, done: () -> Unit = {}) {
        val context = activity.applicationContext
        if (distributors(context).isEmpty()) return done()
        prefs(context).edit { remove(KEY_FAILED) }
        runCatching {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success ->
                if (success) register(context) else prefs(context).edit { putString(KEY_FAILED, "no distributor chosen") }
                done()
            }
        }.onFailure {
            Log.w(TAG, "could not pick a distributor: ${it.javaClass.simpleName}")
            done()
        }
    }

    private fun register(context: Context) {
        runCatching {
            UnifiedPush.register(context, INSTANCE, context.getString(R.string.yt_r30_push_registration_label))
        }.onFailure {
            Log.w(TAG, "could not register: ${it.javaClass.simpleName}")
            prefs(context).edit { putString(KEY_FAILED, it.javaClass.simpleName) }
        }
    }

    /** The switch was turned off: the registration goes, and the stored address with it. */
    fun disable(context: Context) {
        val app = context.applicationContext
        runCatching { UnifiedPush.unregister(app, INSTANCE) }
            .onFailure { Log.w(TAG, "could not unregister: ${it.javaClass.simpleName}") }
        prefs(app).edit { remove(KEY_ENDPOINT).remove(KEY_CHANGED).remove(KEY_FAILED) }
    }

    /**
     * App start: registers again, as UnifiedPush recommends (a distributor that was removed
     * reports the registration as gone; one that is there confirms or renews the address). A
     * single or default distributor is taken without asking; several without a default wait for
     * the settings. Off the main thread.
     */
    fun refresh(context: Context) {
        if (!enabled()) return
        val app = context.applicationContext
        val prefs = prefs(app)
        val now = System.currentTimeMillis()
        // every process start would be too often (each background job starts one)
        val last = prefs.getLong(KEY_LAST_REFRESH, 0)
        if (endpoint(app) != null && last in 1..now && now - last < REFRESH_GAP_MS) return
        prefs.edit { putLong(KEY_LAST_REFRESH, now) }
        scope.launch {
            runCatching {
                if (UnifiedPush.getAckDistributor(app) != null || UnifiedPush.getSavedDistributor(app) != null) {
                    register(app)
                } else {
                    val resolved = UnifiedPush.resolveDefaultDistributor(app)
                    if (resolved is ResolvedDistributor.Found && resolved.packageName != app.packageName) {
                        UnifiedPush.saveDistributor(app, resolved.packageName)
                        register(app)
                    }
                }
            }.onFailure { Log.w(TAG, "could not renew the registration: ${it.javaClass.simpleName}") }
        }
    }

    /** The distributor gave a (new) push address. */
    fun onNewEndpoint(context: Context, url: String, temporary: Boolean) {
        val prefs = prefs(context)
        val stored = prefs.getString(KEY_ENDPOINT, null)
        if (!YtPushRules.takesEndpoint(stored, url, temporary)) {
            prefs.edit { remove(KEY_FAILED) }
            return
        }
        prefs.edit {
            putString(KEY_ENDPOINT, url)
            // an address copied before no longer works: the CI secret needs the new one
            if (stored != null) putBoolean(KEY_CHANGED, true)
            remove(KEY_FAILED)
        }
        Log.i(TAG, if (stored == null) "registered" else "push address changed")
    }

    fun onRegistrationFailed(context: Context, reason: String) {
        Log.i(TAG, "registration failed: $reason")
        prefs(context).edit { putString(KEY_FAILED, reason) }
    }

    /** The distributor ended the registration (uninstalled, or the user removed it there). */
    fun onUnregistered(context: Context) {
        Log.i(TAG, "unregistered by the distributor")
        prefs(context).edit { remove(KEY_ENDPOINT).remove(KEY_CHANGED) }
    }

    /** The address was copied: a change before is dealt with. */
    fun markCopied(context: Context) = prefs(context).edit { remove(KEY_CHANGED) }

    /** A push arrived. Only a trigger: see the class comment. */
    @Synchronized
    fun onMessage(context: Context, content: ByteArray) {
        val app = context.applicationContext
        val event = if (content.size > YtPushRules.MAX_BODY_CHARS * 4) {
            Event.Ignored
        } else {
            YtPushRules.parse(runCatching { String(content, Charsets.UTF_8) }.getOrNull())
        }
        val now = System.currentTimeMillis()
        val prefs = prefs(app)
        prefs.edit {
            putString(KEY_LAST_EVENT, eventText(event))
            putLong(KEY_LAST_EVENT_AT, now)
        }
        if (event == Event.Ignored || !enabled() || !YtUpdateNotifier.enabled()) return
        val build = when (event) {
            is Event.Started -> event.build
            is Event.Published -> event.build
            Event.Ignored -> return
        }
        if (!YtPushRules.isNews(build, UpdateChecker.installedBuild(), YtUpdateNotifier.lastNotified(app))) return
        val windowStart = prefs.getLong(KEY_WINDOW_START, 0)
        val count = prefs.getInt(KEY_WINDOW_COUNT, 0)
        if (!YtPushRules.mayCheck(prefs.getLong(KEY_LAST_CHECK, 0), windowStart, count, now)) {
            Log.i(TAG, "push ignored: too many")
            return
        }
        val (start, inWindow) = YtPushRules.nextWindow(windowStart, count, now)
        prefs.edit {
            putLong(KEY_LAST_CHECK, now)
            putLong(KEY_WINDOW_START, start)
            putInt(KEY_WINDOW_COUNT, inWindow)
        }
        when (event) {
            is Event.Started -> YtUpdateWatch.start(app, build, now, "push")
            is Event.Published -> checkNow(app, build)
            Event.Ignored -> Unit
        }
    }

    /** The normal check, at once (expedited where Android has it), because a push said so. */
    private fun checkNow(context: Context, expected: Int?) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<YtUpdateCheckWorker>()
                .setInputData(
                    workDataOf(
                        YtUpdateCheckWorker.KEY_MODE to YtUpdateCheckWorker.MODE_PUSH,
                        YtUpdateCheckWorker.KEY_EXPECTED to (expected ?: 0)
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                // GitHub may answer from its cache for about a minute after the release
                .setBackoffCriteria(BackoffPolicy.LINEAR, 40, TimeUnit.SECONDS)
                .apply {
                    // before Android 12 an expedited job would need a foreground notification
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(PUSH_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "could not start the check", it) }
    }

    private fun eventText(event: Event): String = when (event) {
        is Event.Started -> "started ${event.build ?: "?"}"
        is Event.Published -> "published ${event.build ?: "?"}"
        Event.Ignored -> "ignored"
    }

    /** The diagnosis line: never the address, only whether there is one. */
    fun diagnosis(context: Context): String {
        if (!enabled()) return "off"
        val prefs = prefs(context)
        val state = when (status(context)) {
            Status.OFF -> "off"
            Status.NO_DISTRIBUTOR -> "no distributor"
            Status.PENDING -> "not registered"
            Status.REGISTERED -> "registered"
            Status.FAILED -> "not registered (failed: ${prefs.getString(KEY_FAILED, "?")})"
        }
        val changed = if (endpointChanged(context)) ", address changed since copied" else ""
        val distributor = distributorName(context)?.let { ", via $it" }.orEmpty()
        val last = prefs.getLong(KEY_LAST_EVENT_AT, 0).takeIf { it > 0 }?.let {
            ", last push ${YtCrashLog.formatTime(it)} (${prefs.getString(KEY_LAST_EVENT, "?")})"
        } ?: ", no push yet"
        return "$state$distributor$changed$last"
    }

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
