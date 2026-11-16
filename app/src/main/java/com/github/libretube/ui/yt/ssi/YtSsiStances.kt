package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.util.Log
import com.github.libretube.ui.yt.archive.script.YtScriptTextAis
import com.github.libretube.ui.yt.news.YtNewsSummaryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where a relation analysis is. */
enum class YtSsiStancePhase { IDLE, RUNNING, DONE }

/** What an analysis says about itself. */
enum class YtSsiStanceNote {
    /** The AI is not allowed or not set up: no relations, plain hits. */
    NO_AI,
    USED,
    CACHED,
    FAILED,

    /** This analysis' requests are used up; the rest is "nicht geprüft". */
    BUDGET_SEARCH,

    /** Today's requests are used up. */
    BUDGET_DAY,

    /** No passage to place (only title matches). */
    NOTHING,
}

/**
 * One analysis as the screen shows it. [stances] by passage id (every one checked by
 * [YtSsiStanceRules.verify]); [pending] the passages of the request on its way; [origins] the
 * independence clusters of [originsFor] (the video ids they were made for).
 */
data class YtSsiStanceRun(
    val runId: Long = 0,
    val claim: String = "",
    val phase: YtSsiStancePhase = YtSsiStancePhase.IDLE,
    val stances: Map<Long, YtSsiStance> = emptyMap(),
    val pending: Set<Long> = emptySet(),
    val notes: Set<YtSsiStanceNote> = emptySet(),
    val requests: Int = 0,
    val origins: List<YtSsiOrigin> = emptyList(),
    val originsFor: List<String> = emptyList(),
)

/** The last analysis in numbers, for Diagnose. */
data class YtSsiStanceTally(val at: Long, val passages: Int, val verified: Int, val forced: Int, val aiUnsure: Int, val cached: Int, val requests: Int, val unchecked: Int) {
    fun describe(): String =
        "last analysis $passages passages: $verified with verified quote, $forced forced to uncertain (quote not found), $aiUnsure unsure by AI, $cached cached, $requests requests, $unchecked unchecked"
}

/**
 * YT (SSI-3): runs the relation analysis of a search's hits, one at a time (a new one, or a new
 * search, cancels the old). The passages of the best hits go to the AI in batches
 * ([YtSsiStanceRules.BATCH]) within the per-analysis and per-day budget; answers are cached per
 * claim, passage and model and verified again on every read, so the hard rule holds for cached
 * relations too. Results come in batch by batch. The independence clusters ([YtSsiSources]) need
 * no AI and are made for any hits.
 */
object YtSsiStances {
    private const val TAG = "YtSsiStances"
    private const val TIMEOUT_MS = 60_000L

    /** Passages considered at most (cached ones are free; uncached ones within the budget). */
    private const val MAX_UNITS = 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _run = MutableStateFlow(YtSsiStanceRun())
    val run: StateFlow<YtSsiStanceRun> = _run.asStateFlow()
    private var job: Job? = null
    private var originJob: Job? = null

    @Volatile
    var last: YtSsiStanceTally? = null
        private set

    /** Whether relations can be asked now (switches on and a provider set up). */
    fun available(): Boolean = YtSsi.stanceOn() && runCatching { YtNewsSummaryKey.setup() }.getOrNull() != null

    /** Forgets the analysis (a new search). */
    fun reset(runId: Long = 0) {
        job?.cancel()
        originJob?.cancel()
        _run.value = YtSsiStanceRun(runId = runId)
    }

    fun cancel() {
        job?.cancel()
        _run.update { if (it.phase == YtSsiStancePhase.RUNNING) it.copy(phase = YtSsiStancePhase.DONE, pending = emptySet()) else it }
    }

    /** Shows saved relations (a reopened research) without asking anyone. */
    fun restore(runId: Long, claim: String, stances: Map<Long, YtSsiStance>) {
        reset(runId)
        _run.value = YtSsiStanceRun(runId = runId, claim = claim, phase = YtSsiStancePhase.DONE, stances = stances)
    }

    /** Starts placing [hits] against [claim] (for search [runId]); the relations already known stay. */
    fun start(context: Context, runId: Long, claim: String, query: String, hits: List<YtSsiHit>) {
        val app = context.applicationContext
        job?.cancel()
        val keep = _run.value.takeIf { it.runId == runId && YtSsiText.fold(it.claim) == YtSsiText.fold(claim) }?.stances.orEmpty()
        _run.update { it.copy(runId = runId, claim = claim, phase = YtSsiStancePhase.RUNNING, stances = keep, pending = emptySet(), notes = emptySet(), requests = 0) }
        job = scope.launch {
            try {
                analyse(app, runId, claim, query, hits)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "analysis failed", e)
                set(runId) { it.copy(phase = YtSsiStancePhase.DONE, pending = emptySet(), notes = it.notes + YtSsiStanceNote.FAILED) }
            }
        }
    }

    private fun set(runId: Long, change: (YtSsiStanceRun) -> YtSsiStanceRun) {
        _run.update { if (it.runId == runId) change(it) else it }
    }

    private suspend fun analyse(context: Context, runId: Long, claim: String, query: String, hits: List<YtSsiHit>) {
        val setup = if (YtSsi.stanceOn()) runCatching { YtNewsSummaryKey.setup() }.getOrNull() else null
        if (setup == null) {
            set(runId) { it.copy(phase = YtSsiStancePhase.DONE, notes = it.notes + YtSsiStanceNote.NO_AI) }
            return
        }
        val db = YtSsi.db
        val units = withContext(Dispatchers.IO) {
            val out = ArrayList<YtSsiStanceRules.Unit>()
            val seen = HashSet<Long>()
            for (hit in hits) {
                if (out.size >= MAX_UNITS) break
                val id = hit.segmentId ?: continue
                if (!seen.add(id)) continue
                val seg = db.segment(id) ?: continue
                YtSsiStanceRules.unit("p${out.size + 1}", seg, hit.title, hit.channelName, db.neighbour(seg.videoId, seg.ord - 1), db.neighbour(seg.videoId, seg.ord + 1))
                    ?.let { out += it }
            }
            out
        }
        if (units.isEmpty()) {
            set(runId) { it.copy(phase = YtSsiStancePhase.DONE, notes = it.notes + YtSsiStanceNote.NOTHING) }
            return
        }
        val model = "${setup.kind.id}:${setup.model}"
        val now = System.currentTimeMillis()
        val stances = LinkedHashMap<Long, YtSsiStance>()
        var cachedCount = 0
        withContext(Dispatchers.IO) {
            for (unit in units) {
                val raw = db.stance(YtSsiStanceRules.cacheKey(claim, unit, model), now) ?: continue
                stances[unit.segmentId] = YtSsiStanceRules.verify(unit, raw, cached = true)
                cachedCount++
            }
        }
        if (cachedCount > 0) set(runId) { it.copy(stances = it.stances + stances, notes = it.notes + YtSsiStanceNote.CACHED) }

        val perSearch = YtSsi.stancePerSearch()
        val batches = YtSsiStanceRules.batches(units, stances.keys, perSearch)
        val left = units.count { it.segmentId !in stances } - batches.sumOf { it.size }
        var requests = 0
        var failed = false
        var dayOut = false
        val ai = YtScriptTextAis.of(setup)
        for (batch in batches) {
            if (!YtSsi.spendStance(context)) {
                dayOut = true
                break
            }
            requests++
            set(runId) { it.copy(pending = batch.map { u -> u.segmentId }.toSet(), requests = requests) }
            val answer = try {
                withTimeoutOrNull(TIMEOUT_MS) { ai.complete(YtSsiStanceRules.SYSTEM, YtSsiStanceRules.user(claim, query, batch), YtSsiStanceRules.schema()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "stance request failed: $e")
                null
            }
            val raws = YtSsiStanceRules.parse(answer, batch)
            if (raws == null) {
                failed = true
                set(runId) { it.copy(pending = emptySet(), notes = it.notes + YtSsiStanceNote.FAILED) }
                continue
            }
            val placed = LinkedHashMap<Long, YtSsiStance>()
            val stored = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                for (unit in batch) {
                    val raw = raws[unit.segmentId] ?: continue
                    runCatching { db.putStance(YtSsiStanceRules.cacheKey(claim, unit, model), raw, stored) }
                    placed[unit.segmentId] = YtSsiStanceRules.verify(unit, raw)
                }
            }
            stances += placed
            set(runId) { it.copy(stances = it.stances + placed, pending = emptySet(), notes = it.notes + YtSsiStanceNote.USED) }
        }
        val notes = buildSet {
            if (left > 0) add(YtSsiStanceNote.BUDGET_SEARCH)
            if (dayOut) add(YtSsiStanceNote.BUDGET_DAY)
            if (failed) add(YtSsiStanceNote.FAILED)
        }
        set(runId) { it.copy(phase = YtSsiStancePhase.DONE, pending = emptySet(), notes = it.notes + notes) }
        val all = stances.values
        last = YtSsiStanceTally(
            at = System.currentTimeMillis(),
            passages = units.size,
            verified = all.count { it.verified },
            forced = all.count { it.unsure == YtSsiUnsure.NO_QUOTE || it.unsure == YtSsiUnsure.QUOTE_NOT_FOUND },
            aiUnsure = all.count { it.unsure == YtSsiUnsure.AI_SAID },
            cached = cachedCount,
            requests = requests,
            unchecked = units.size - all.size,
        )
    }

    /**
     * Makes the independence clusters of [hits] (search [runId]) unless they are there for the
     * same videos: passages, descriptions, lengths and (with the model) passage vectors.
     */
    fun origins(runId: Long, hits: List<YtSsiHit>) {
        val videos = hits.map { it.videoId }.distinct()
        val current = _run.value
        if (current.runId == runId && current.originsFor == videos) return
        originJob?.cancel()
        originJob = scope.launch {
            val origins = runCatching {
                withContext(Dispatchers.IO) {
                    val db = YtSsi.db
                    val docs = db.docs()
                    val vectors = if (YtSsiModels.state.value == YtSsiModelState.Ready) {
                        runCatching { db.vectorsOf(YtSsiModels.spec.version, hits.mapNotNull { it.segmentId }) }.getOrDefault(emptyMap())
                    } else {
                        emptyMap()
                    }
                    YtSsiSources.cluster(
                        hits.map { hit ->
                            val doc = docs[hit.videoId]
                            YtSsiSources.Item(
                                videoId = hit.videoId,
                                channelId = doc?.channelId,
                                channelName = hit.channelName ?: doc?.channelName,
                                title = hit.title,
                                description = doc?.description,
                                uploaded = hit.uploaded ?: doc?.uploaded,
                                durationSec = doc?.durationSec,
                                text = if (hit.segmentId != null) hit.text else "",
                                vector = hit.segmentId?.let { vectors[it] },
                            )
                        },
                    )
                }
            }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "origins failed", it) }.getOrNull() ?: return@launch
            _run.update {
                when {
                    it.runId == runId -> it.copy(origins = origins, originsFor = videos)
                    // nothing analysed yet for any search: the clusters need no analysis
                    it.phase == YtSsiStancePhase.IDLE && it.stances.isEmpty() -> YtSsiStanceRun(runId = runId, origins = origins, originsFor = videos)
                    else -> it
                }
            }
        }
    }
}
