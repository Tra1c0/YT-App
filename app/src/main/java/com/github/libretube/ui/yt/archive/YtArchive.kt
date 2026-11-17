package com.github.libretube.ui.yt.archive

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.libretube.LibreTubeApp
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.enums.FileType
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.util.YtCrashLog
import com.github.libretube.util.YtNotifyChannels
import com.github.libretube.util.YtStreamError
import com.github.libretube.workers.YtArchiveWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * YT: the archive of vanished videos, the Android side: settings, the background jobs, the hook
 * for a video that failed as gone ([onVanished], from YtStreamFailure), what the watch page can
 * offer instead ([kept]), the "could vanish" notification and the diagnosis line.
 *
 * Jobs (WorkManager, [YtArchiveWorker], run by [YtArchiveRunner]):
 * - [WORK_TEXT] every 6 h on any network, battery not low: text for new feed videos, light
 *   checks, window labels;
 * - [WORK_MEDIA] every 6 h on an unmetered network while charging, battery not low: audio and
 *   144p by the budget plan;
 * - [WORK_URGENT] once, unmetered network: high-risk audio right away, not waiting for charging;
 * - [WORK_CHECK] once, any network: confirms a video the player found gone;
 * - [WORK_NOW] once, any network: "Im Archiv sichern" from the hold menu.
 * All off (and cancelled) while the master switch is off.
 */
object YtArchive {
    private const val TAG = "YtArchive"
    const val WORK_TEXT = "YtArchiveText"
    const val WORK_MEDIA = "YtArchiveMedia"
    const val WORK_URGENT = "YtArchiveUrgent"
    const val WORK_CHECK = "YtArchiveCheck"
    const val WORK_NOW = "YtArchiveNow"
    private const val PERIOD_HOURS = 6L

    /** Own prefs file (excluded from backups like the folder): the throttle state and skipped ids. */
    private const val PREFS = "yt_archive"
    private const val KEY_THROTTLE = "throttle"
    private const val KEY_SKIPPED = "skipped"
    private const val MAX_SKIPPED = 600

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store: YtArchiveStore by lazy { YtArchiveStore(File(LibreTubeApp.instance.filesDir, YtArchiveStore.DIR)) }

    fun enabled(): Boolean = PreferenceHelper.getBoolean(YtArchivePrefs.KEY_ENABLED, YtArchivePrefs.DEFAULT_ENABLED)

    fun capacityMb(): Int = PreferenceHelper.getInt(YtArchivePrefs.KEY_CAPACITY_MB, YtArchiveRules.DEFAULT_CAPACITY_MB)

    fun budget(): Long = YtArchiveRules.budgetBytes(capacityMb())

    fun top(): YtArchiveTier = YtArchivePrefs.top(PreferenceHelper.getString(YtArchivePrefs.KEY_TOP, YtArchivePrefs.DEFAULT_TOP))

    fun notifyRisk(): Boolean = PreferenceHelper.getBoolean(YtArchivePrefs.KEY_NOTIFY_RISK, YtArchivePrefs.DEFAULT_NOTIFY_RISK)

    fun autoScript(): Boolean = PreferenceHelper.getBoolean(YtArchivePrefs.KEY_AUTO_SCRIPT, YtArchivePrefs.DEFAULT_AUTO_SCRIPT)

    // ---- throttle state ----

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun throttle(context: Context): YtArchiveThrottle.State = runCatching {
        prefs(context).getString(KEY_THROTTLE, null)?.let { json.decodeFromString(YtArchiveThrottle.State.serializer(), it) }
    }.getOrNull() ?: YtArchiveThrottle.State()

    private val throttleLock = Any()

    /** Reads, changes and saves the throttle state in one step, so concurrent writers never overwrite each other. */
    fun updateThrottle(context: Context, change: (YtArchiveThrottle.State) -> YtArchiveThrottle.State): YtArchiveThrottle.State =
        synchronized(throttleLock) { change(throttle(context)).also { saveThrottle(context, it) } }

    fun saveThrottle(context: Context, state: YtArchiveThrottle.State) =
        prefs(context).edit { putString(KEY_THROTTLE, json.encodeToString(YtArchiveThrottle.State.serializer(), state)) }

    /** Feed videos not to fetch again (Shorts or live found on fetching, gone before saved). */
    fun skipped(context: Context): Set<String> = prefs(context).getString(KEY_SKIPPED, null)
        ?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun skip(context: Context, videoId: String) {
        val list = prefs(context).getString(KEY_SKIPPED, null)?.split(',')?.filter { it.isNotEmpty() && it != videoId }.orEmpty()
        prefs(context).edit { putString(KEY_SKIPPED, (list + videoId).takeLast(MAX_SKIPPED).joinToString(",")) }
    }

    // ---- jobs ----

    /** Schedules the periodic jobs (kept as scheduled), or cancels every job when off. Every app start and switch change. */
    fun schedule(context: Context, on: Boolean = enabled()) {
        val app = context.applicationContext
        runCatching {
            val work = WorkManager.getInstance(app)
            if (!on) {
                listOf(WORK_TEXT, WORK_MEDIA, WORK_URGENT, WORK_CHECK, WORK_NOW).forEach { work.cancelUniqueWork(it) }
                return
            }
            val text = PeriodicWorkRequestBuilder<YtArchiveWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInputData(workDataOf(YtArchiveWorker.KEY_MODE to YtArchiveRunner.MODE_TEXT))
                .build()
            val media = PeriodicWorkRequestBuilder<YtArchiveWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInputData(workDataOf(YtArchiveWorker.KEY_MODE to YtArchiveRunner.MODE_MEDIA))
                .build()
            work.enqueueUniquePeriodicWork(WORK_TEXT, ExistingPeriodicWorkPolicy.KEEP, text)
            work.enqueueUniquePeriodicWork(WORK_MEDIA, ExistingPeriodicWorkPolicy.KEEP, media)
        }.onFailure { Log.w(TAG, "could not schedule the archive", it) }
    }

    private fun once(context: Context, name: String, mode: String, network: NetworkType, videoId: String? = null, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<YtArchiveWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(network)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInputData(workDataOf(YtArchiveWorker.KEY_MODE to mode, YtArchiveWorker.KEY_VIDEO to videoId))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy, request)
        }.onFailure { Log.w(TAG, "could not start $name", it) }
    }

    /** High-risk audio now: any unmetered network, no charger needed. */
    fun runUrgent(context: Context) = once(context, WORK_URGENT, YtArchiveRunner.MODE_URGENT, NetworkType.UNMETERED)

    /** Confirm vanished entries soon (one light request each). */
    fun runCheck(context: Context) = once(context, WORK_CHECK, YtArchiveRunner.MODE_CHECK, NetworkType.CONNECTED)

    /** "Im Archiv sichern": text, audio and (if allowed) 144p of [videoId], kept no matter the budget. */
    fun saveNow(context: Context, videoId: String) {
        if (!YtArchiveRules.validId(videoId)) return
        // one at a time per video id; another video replaces nothing (APPEND keeps the order)
        once(context, WORK_NOW, YtArchiveRunner.MODE_NOW, NetworkType.CONNECTED, videoId, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    // ---- gone videos ----

    /**
     * The player found [videoId] gone for everyone ([kind], YtStreamError.removesFromFeed). An
     * archived entry becomes vanished and protected now; the label for the risk estimator and the
     * automatic script follow once a light check confirms it ([YtArchiveRunner], so a passing
     * block never teaches the estimator a wrong label). Any thread.
     */
    fun onVanished(context: Context, videoId: String, kind: YtStreamError.Kind) {
        if (!YtStreamError.removesFromFeed(kind) || !YtArchiveRules.validId(videoId)) return
        val app = context.applicationContext
        scope.launch {
            val marked = runCatching { markVanished(videoId, kind, System.currentTimeMillis()) }.getOrNull()
            if (marked != null && enabled()) runCheck(app)
        }
    }

    /** Marks the entry vanished (keeps an earlier time); null without an entry. */
    internal fun markVanished(videoId: String, kind: YtStreamError.Kind, now: Long): YtArchiveMeta? =
        store.update(videoId) { meta ->
            if (meta.vanished) meta else meta.copy(vanishedAt = now, vanishedKind = kind.name)
        }

    // ---- scripts ----

    /**
     * Asks the script part: the audio analysis when [audio] (default: when audio is kept), else
     * the dialogue from the transcript. The entry remembers the outcome; true when a script came out.
     */
    suspend fun makeScript(meta: YtArchiveMeta, audio: Boolean? = null): Boolean = withContext(Dispatchers.IO) {
        val files = store.files(meta.videoId) ?: return@withContext false
        val withAudio = audio ?: (meta.tier.level >= YtArchiveTier.AUDIO.level && files.audio.isFile)
        val ok = runCatching {
            if (withAudio) YtArchiveParts.script.analyseAudio(meta.candidate(), files) else YtArchiveParts.script.dialogue(meta.candidate(), files)
        }.getOrDefault(false)
        val (text, _, _) = store.measure(meta.videoId)
        store.update(meta.videoId) {
            it.copy(scriptState = if (ok) YtArchiveMeta.SCRIPT_DONE else YtArchiveMeta.SCRIPT_FAILED, scriptAt = System.currentTimeMillis(), textBytes = text)
        }
        ok
    }

    // ---- what the watch page can offer ----

    /** A kept copy of a video: the archive's entry and/or a finished LibreTube download. */
    data class Kept(val meta: YtArchiveMeta?, val download: Boolean, val downloadHasVideo: Boolean = false) {
        /** Something to play (a download, or archived audio/video). */
        val playable: Boolean get() = download || (meta != null && meta.tier.level >= YtArchiveTier.AUDIO.level)
    }

    /** The kept copy of [videoId], or null when there is none. Reads the disk and the downloads DB. */
    suspend fun kept(videoId: String): Kept? = withContext(Dispatchers.IO) {
        val meta = runCatching { store[videoId] }.getOrNull()
        val files = runCatching {
            DatabaseHolder.Database.downloadDao().findById(videoId)?.downloadItems
                ?.filter { it.path.exists() }?.map { it.type }.orEmpty()
        }.getOrDefault(emptyList())
        val download = FileType.VIDEO in files || FileType.AUDIO in files
        if (meta == null && !download) null else Kept(meta, download, FileType.VIDEO in files)
    }

    // ---- notification ----

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return YtNotifyChannels.isBlocked(context, YtNotifyChannels.ARCHIVE) != true
    }

    /**
     * "„Titel“ könnte verschwinden – gesichert" with the estimator's reason, once per video (the
     * entry remembers it only when it was posted). Tapping it opens the video.
     */
    @SuppressLint("MissingPermission") // checked in canNotify()
    fun notifyHighRisk(context: Context, meta: YtArchiveMeta): Boolean {
        if (meta.notified || !notifyRisk()) return false
        YtNotifyChannels.ensureArchive(context)
        if (!canNotify(context)) return false
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(IntentData.videoId, meta.videoId)
        val id = NOTIFICATION_BASE + (meta.videoId.hashCode() and 0xFFFF)
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = meta.riskReason?.takeIf { it.isNotBlank() } ?: context.getString(R.string.yt_r31_archive_notify_text)
        val notification = NotificationCompat.Builder(context, YtNotifyChannels.ARCHIVE)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle(context.getString(R.string.yt_r31_archive_notify_title, meta.title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val posted = runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { Log.w(TAG, "could not post", it) }
            .isSuccess
        if (posted) store.update(meta.videoId) { it.copy(notified = true) }
        return posted
    }

    private const val NOTIFICATION_BASE = 0x7A000000

    // ---- diagnosis ----

    /** "Archiv": switch, entries per tier, MB used/budget, last run, pause, last error. */
    fun diagnosis(context: Context): String {
        val metas = runCatching { store.all() }.getOrDefault(emptyList())
        val tiers = YtArchiveTier.entries.joinToString("/") { tier -> metas.count { it.tier == tier }.toString() }
        val used = metas.sumOf { it.totalBytes } / YtArchiveRules.MB
        val vanished = metas.count { it.vanished }
        val state = throttle(context)
        val now = System.currentTimeMillis()
        val scheduled = runCatching {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_TEXT)
                .get(300, TimeUnit.MILLISECONDS)
                .any { it.state != WorkInfo.State.CANCELLED && !it.state.isFinished }
        }.getOrNull()
        return buildString {
            append(if (enabled()) "on" else "off")
            append(", text/sound/144p $tiers, vanished $vanished")
            append(", $used of ${capacityMb()} MB")
            append(", last run ${state.lastRun.takeIf { it > 0 }?.let(YtCrashLog::formatTime) ?: "never"}")
            state.lastRunKind?.let { append(" ($it)") }
            if (YtArchiveThrottle.paused(state, now)) append(", paused until ${YtCrashLog.formatTime(state.backoffUntil)}")
            state.lastError?.let { append(", last error ${YtCrashLog.formatTime(state.lastErrorAt)}: ${it.take(80)}") }
            if (enabled() && scheduled == false) append(", not scheduled")
        }
    }
}
