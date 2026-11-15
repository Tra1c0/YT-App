package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.github.libretube.LibreTubeApp
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.yt.archive.YtArchive
import com.github.libretube.ui.yt.archive.YtArchiveThrottle
import com.github.libretube.util.YtCrashLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * YT: SSI's Android glue: the index and its indexer, the vectors in memory, the throttle state
 * (its own, and it also waits while the archive is paused by a bot check), the background job
 * and the Diagnose line.
 */
object YtSsi {
    private const val TAG = "YtSsi"
    private const val PREFS = "yt_ssi"
    private const val KEY_THROTTLE = "throttle"
    private const val KEY_LAST = "last_run"
    private const val KEY_LEARN_STATE = "learn"
    private const val KEY_RERANK_DAY = "rerank_day"
    const val WORK_PERIODIC = "YtSsiIndex"
    const val WORK_NOW = "YtSsiIndexNow"
    private const val PERIOD_HOURS = 12L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val db: YtSsiDb by lazy { YtSsiDb(LibreTubeApp.instance) }
    val indexer: YtSsiIndexer by lazy { YtSsiIndexer(LibreTubeApp.instance, db) }

    private val vectorLock = Mutex()
    private var vectors: YtSsiVectorIndex? = null

    /** The passage vectors of the current model, loaded on first use, topped up with new ones. */
    suspend fun vectors(): YtSsiVectorIndex? = vectorLock.withLock {
        val spec = YtSsiModels.spec
        val index = vectors?.takeIf { it.model == spec.version } ?: YtSsiVectorIndex(spec.version, spec.dim)
        withContext(Dispatchers.IO) { db.loadVectors(index) }
        vectors = index
        index.takeIf { it.size > 0 }
    }

    /** Forgets the vectors in memory (after clearing the index or the model). */
    suspend fun dropVectors() = vectorLock.withLock { vectors = null }

    fun background(): Boolean = PreferenceHelper.getBoolean(YtSsiPrefs.KEY_BACKGROUND, YtSsiPrefs.DEFAULT_BACKGROUND)
    fun feed(): Boolean = PreferenceHelper.getBoolean(YtSsiPrefs.KEY_FEED, YtSsiPrefs.DEFAULT_FEED)
    fun deepBudget(): Int = YtSsiPrefs.budget(PreferenceHelper.getString(YtSsiPrefs.KEY_DEEP_BUDGET, YtSsiPrefs.DEFAULT_DEEP_BUDGET.toString()))
    fun allowAi(): Boolean = PreferenceHelper.getBoolean(YtSsiPrefs.KEY_ALLOW_AI, YtSsiPrefs.DEFAULT_ALLOW_AI)
    fun aiRerank(): Boolean = allowAi() && PreferenceHelper.getBoolean(YtSsiPrefs.KEY_AI_RERANK, YtSsiPrefs.DEFAULT_AI_RERANK)
    fun learnOn(): Boolean = PreferenceHelper.getBoolean(YtSsiPrefs.KEY_LEARN, YtSsiPrefs.DEFAULT_LEARN)

    // ---- learning from feedback (SSI-2) ----

    @Volatile
    private var learnCache: YtSsiLearnState? = null

    fun learnState(context: Context): YtSsiLearnState =
        learnCache ?: YtSsiLearning.decode(prefs(context).getString(KEY_LEARN_STATE, null)).also { learnCache = it }

    /** The fusion weights for [intent] (learnt ones when learning is on). */
    fun weights(context: Context, intent: YtSsiIntent, now: Long = System.currentTimeMillis()): YtSsiWeights =
        if (learnOn()) YtSsiLearning.weights(intent.weights, intent.kind, learnState(context), now) else intent.weights

    /** The meaning search's cut-off (learnt when learning is on). */
    fun minCosine(context: Context, now: Long = System.currentTimeMillis()): Float =
        if (learnOn()) YtSsiLearning.minCosine(learnState(context), now) else YtSsiVectorIndex.MIN_COSINE

    /**
     * Stores [verdict] on [hit] and, when learning is on, learns from it. Call off the main
     * thread (one DB insert).
     */
    @Synchronized
    fun feedback(context: Context, hit: YtSsiHit, intent: YtSsiIntent, verdict: YtSsiVerdict, semanticUsed: Boolean) {
        val now = System.currentTimeMillis()
        val event = YtSsiLearning.feedback(hit, intent, verdict, now, semanticUsed)
        runCatching { db.putFeedback(event) }.onFailure { Log.w(TAG, "feedback not stored", it) }
        if (!learnOn()) return
        val next = YtSsiLearning.learn(learnState(context), event)
        learnCache = next
        prefs(context).edit { putString(KEY_LEARN_STATE, YtSsiLearning.encode(next)) }
    }

    /** "Gelerntes zurücksetzen": the learnt values and the stored verdicts go. */
    @Synchronized
    fun resetLearning(context: Context) {
        learnCache = YtSsiLearnState()
        prefs(context).edit { remove(KEY_LEARN_STATE) }
        runCatching { db.clearFeedback() }
    }

    // ---- AI re-rank budget (SSI-2) ----

    private fun today(now: Long) = Math.floorDiv(now + offsetMs(now), 24L * 60 * 60 * 1000)

    private fun rerankDay(context: Context): YtSsiRerank.Day = runCatching {
        prefs(context).getString(KEY_RERANK_DAY, null)?.split(':')?.let { YtSsiRerank.Day(it[0].toLong(), it[1].toInt()) }
    }.getOrNull() ?: YtSsiRerank.Day()

    /** Takes one AI re-rank request from today's budget; false when it is used up. */
    @Synchronized
    fun spendRerank(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val next = YtSsiRerank.spend(rerankDay(context), today(now)) ?: return false
        prefs(context).edit { putString(KEY_RERANK_DAY, "${next.day}:${next.count}") }
        return true
    }

    fun rerankUsedToday(context: Context, now: Long = System.currentTimeMillis()): Int = YtSsiRerank.used(rerankDay(context), today(now))

    // ---- throttle ----

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun throttle(context: Context): YtArchiveThrottle.State = runCatching {
        prefs(context).getString(KEY_THROTTLE, null)?.let { json.decodeFromString(YtArchiveThrottle.State.serializer(), it) }
    }.getOrNull() ?: YtArchiveThrottle.State()

    private fun saveThrottle(context: Context, state: YtArchiveThrottle.State) =
        prefs(context).edit { putString(KEY_THROTTLE, json.encodeToString(YtArchiveThrottle.State.serializer(), state)) }

    /** Whether YouTube may be asked now: neither SSI nor the archive was told off recently. */
    fun mayAsk(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        !YtArchiveThrottle.paused(throttle(context), now) && !YtArchiveThrottle.paused(YtArchive.throttle(context), now)

    /** The time until which asking is paused, or null. */
    fun pausedUntil(context: Context, now: Long = System.currentTimeMillis()): Long? =
        listOf(throttle(context).backoffUntil, YtArchive.throttle(context).backoffUntil).filter { it > now }.maxOrNull()

    fun blocked(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        saveThrottle(context, YtArchiveThrottle.afterBlock(throttle(context), now, reason))
    }

    fun ranOk(context: Context, kind: String) {
        val now = System.currentTimeMillis()
        saveThrottle(context, YtArchiveThrottle.afterRun(throttle(context), now, kind, blocked = false))
    }

    // ---- background job ----

    /** The periodic job (charging, battery not low; any network for the feed's captions), or none when off. */
    fun schedule(context: Context, on: Boolean = background()) {
        runCatching {
            val work = WorkManager.getInstance(context.applicationContext)
            if (!on) {
                work.cancelUniqueWork(WORK_PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<YtSsiWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build(),
                )
                .build()
            work.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "could not schedule", it) }
    }

    /** "Jetzt indexieren": one run now, without the charger. */
    fun runNow(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<YtSsiWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInputData(workDataOf(YtSsiWorker.KEY_NOW to true))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "could not start", it) }
    }

    @Serializable
    data class LastRun(
        val at: Long = 0,
        val archive: Int = 0,
        val online: Int = 0,
        val embedded: Int = 0,
        val note: String? = null,
        /** SSI-2: videos re-cut by meaning and checked unchanged in this run. */
        val recut: Int = 0,
        val unchanged: Int = 0,
    )

    fun lastRun(context: Context): LastRun? = runCatching {
        prefs(context).getString(KEY_LAST, null)?.let { json.decodeFromString(LastRun.serializer(), it) }
    }.getOrNull()

    fun saveLastRun(context: Context, run: LastRun) = prefs(context).edit { putString(KEY_LAST, json.encodeToString(LastRun.serializer(), run)) }

    /**
     * One background run: the archive's transcripts (local), then captions of recent feed videos
     * (at most [YtSsiPrefs.FEED_PER_RUN], 2–8 s apart like the archive, stops at a bot check),
     * then the SSI-2 re-cut by meaning ([YtSsiIndexer.migrate], at most half of what is left of
     * the budget, so vectors still get made), then vectors for passages without one, until
     * [YtSsiPrefs.RUN_BUDGET_MS].
     */
    suspend fun run(context: Context, stopped: () -> Boolean): LastRun {
        val start = System.currentTimeMillis()
        val deadline = start + YtSsiPrefs.RUN_BUDGET_MS
        var note: String? = null
        val archive = runCatching { indexer.indexArchive(deadline) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(0)
        var online = 0
        if (feed() && mayAsk(context) && !stopped()) {
            val known = db.docs()
            val since = start - YtSsiPrefs.FEED_MAX_AGE_MS
            val videos = withContext(Dispatchers.IO) {
                runCatching { DatabaseHolder.Database.feedDao().getAll() }.getOrDefault(emptyList())
            }.filter { !it.isShort && (it.duration ?: 0) > 0 && it.uploaded >= since && it.videoId !in known }
                .take(YtSsiPrefs.FEED_PER_RUN)
            for ((i, item) in videos.withIndex()) {
                if (stopped() || System.currentTimeMillis() > deadline) break
                if (i > 0) delay(YtArchiveThrottle.jitterMs())
                try {
                    if (indexer.indexOnline(item.videoId) == YtSsiIndexer.Outcome.INDEXED) online++
                } catch (e: YtSsiIndexer.Blocked) {
                    blocked(context, e.message ?: "blocked")
                    note = "blocked"
                    break
                }
            }
            if (note == null) ranOk(context, "background")
        }
        var migration = YtSsiIndexer.Migration()
        if (!stopped()) {
            val now = System.currentTimeMillis()
            val until = now + (deadline - now).coerceAtLeast(0) / 2
            migration = runCatching { indexer.migrate(until) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(migration)
        }
        var embedded = 0
        if (!stopped() && YtSsiModels.installed(context)) {
            embedded = runCatching { indexer.embedPending(deadline) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(0)
        }
        return LastRun(System.currentTimeMillis(), archive, online, embedded, note, migration.recut, migration.kept).also { saveLastRun(context, it) }
    }

    // ---- diagnosis ----

    /** "SSI": videos, passages, vectors, model state, last run. */
    fun diagnosis(context: Context): String {
        val spec = YtSsiModels.spec
        val stats = runCatching { db.stats(context, spec.version) }.getOrNull()
        YtSsiModels.refresh(context)
        val model = when (val state = YtSsiModels.state.value) {
            YtSsiModelState.Missing -> "model not downloaded"
            is YtSsiModelState.Downloading -> "model downloading ${state.done / 1_000_000} MB"
            YtSsiModelState.Ready -> "model ${spec.id} (${if (YtSsiModels.verified(context)?.pinned == true) "pinned sha" else "hub-listed sha"})"
            YtSsiModelState.Unsupported -> "model unsupported on this ABI"
            is YtSsiModelState.Failed -> "model failed: ${state.reason.take(60)}"
        } + (YtSsiModels.lastCheck?.let { " [$it]" } ?: "")
        val last = lastRun(context)
        val paused = pausedUntil(context)
        return buildString {
            if (stats == null) append("index unreadable") else append("${stats.videos} videos, ${stats.segments} passages, ${stats.vectors} vectors, ${stats.bytes / 1_000_000} MB")
            append(", ").append(model)
            append(", background ").append(if (background()) "on" else "off")
            append(", last run ")
            if (last == null || last.at == 0L) append("never") else {
                append(YtCrashLog.formatTime(last.at)).append(" (archive ${last.archive}, online ${last.online}, vectors ${last.embedded}, re-cut ${last.recut}, unchanged ${last.unchanged}")
                last.note?.let { append(", $it") }
                append(")")
            }
            paused?.let { append(", paused until ${YtCrashLog.formatTime(it)}") }
            // SSI-2
            runCatching { db.segmentationCounts() }.getOrNull()?.let { counts ->
                append(", segmentation meaning ${counts[YtSsiSegmentation.SEMANTIC] ?: 0} / rules ${counts[YtSsiSegmentation.RULES] ?: 0}")
            }
            append(", AI re-rank ").append(if (aiRerank()) "on" else "off")
            append(" (${rerankUsedToday(context)}/${YtSsiRerank.CALLS_PER_DAY} today)")
            append(", learning ").append(if (learnOn()) "on" else "off").append(": ")
            append(YtSsiLearning.summary(learnState(context), System.currentTimeMillis()))
            runCatching { db.feedbackCount() }.getOrNull()?.let { append(", $it verdicts stored") }
            YtSsiBenchRunner.last(context)?.let { run ->
                val m = run.current
                append(", benchmark ${YtCrashLog.formatTime(m.at)}: R@1 %.2f R@3 %.2f R@5 %.2f nDCG@5 %.2f dev P50 %s P95 %s ms, no-match %.2f, latency P50 %d P95 %d ms (SSI-1 way: R@3 %.2f)".format(
                    java.util.Locale.ROOT, m.recall1, m.recall3, m.recall5, m.ndcg5, m.deviationP50Ms?.toString() ?: "–", m.deviationP95Ms?.toString() ?: "–",
                    m.noMatch, m.latencyP50Ms, m.latencyP95Ms, run.baseline.recall3,
                ))
            }
        }
    }

    /** The phone's time zone offset now (for the throttle's day). */
    fun offsetMs(now: Long = System.currentTimeMillis()): Long = TimeZone.getDefault().getOffset(now).toLong()
}

/** YT: SSI's background indexing (periodic, charging; or once from the settings). */
class YtSsiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        YtSsi.run(applicationContext) { isStopped }
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("YtSsiWorker", "run failed", e)
        Result.success()
    }

    companion object {
        const val KEY_NOW = "now"
    }
}
