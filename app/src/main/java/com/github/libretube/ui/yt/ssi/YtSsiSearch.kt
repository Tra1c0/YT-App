package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.ui.yt.archive.script.YtScriptTextAis
import com.github.libretube.ui.yt.news.YtNewsProviderSetup
import com.github.libretube.ui.yt.news.YtNewsSummaryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/**
 * How long the last Instant search took: [totalMs] from the query to its hits (the archive's
 * new transcripts, the parse and the local search), [localMs] the local search alone, [scan]
 * the vector scan in it (null without the model).
 */
data class YtSsiLatency(val at: Long, val totalMs: Long, val localMs: Long, val scan: YtSsiSearch.Scan?) {
    fun describe(): String = buildString {
        append("last Instant $totalMs ms (local $localMs ms")
        if (scan != null) append(", vector scan ${scan.ms} ms over ${scan.rows} vectors on ${scan.threads} thread${if (scan.threads == 1) "" else "s"}")
        append(")")
    }
}

/** Instant: the local index only. Deep: also YouTube's search and fresh captions. */
enum class YtSsiDepth { INSTANT, DEEP }

/** Where a search is. */
enum class YtSsiPhase { IDLE, LOCAL, AI, DISCOVER, FETCH, RERANK, DONE }

/** What a search says about itself beyond its hits. */
enum class YtSsiRunNote {
    /** No embedding model: words only. */
    LEXICAL_ONLY,

    /** The runtime cannot run on this phone: words only. */
    SEMANTIC_UNSUPPORTED,

    /** The model is there, its vectors are not all made yet. */
    VECTORS_PENDING,
    AI_USED,
    AI_CACHED,
    AI_FAILED,

    /** A bot check or 429 stopped Deep. */
    BLOCKED,

    /** Asking YouTube is paused after an earlier bot check. */
    PAUSED,
    SEARCH_FAILED,
    EMPTY_INDEX,

    /** SSI-3: the hits of a saved research, as they were saved (nothing was searched). */
    RESTORED,

    /** SSI-2: the AI graded the top passages (now, or the same ones earlier). */
    RERANK_USED,
    RERANK_CACHED,

    /** The AI re-rank did not work: the local order stands. */
    RERANK_FAILED,

    /** Today's AI re-rank budget is used up: the local order stands. */
    RERANK_BUDGET,
}

/** One search as the screen shows it; every change is a new value. */
data class YtSsiRun(
    val id: Long = 0,
    val query: String = "",
    val depth: YtSsiDepth = YtSsiDepth.INSTANT,
    val intent: YtSsiIntent? = null,
    val phase: YtSsiPhase = YtSsiPhase.IDLE,
    val hits: List<YtSsiHit> = emptyList(),
    val notes: Set<YtSsiRunNote> = emptySet(),
    /** Deep: videos fetched so far and the budget. */
    val fetched: Int = 0,
    val budget: Int = 0,
    val indexedVideos: Int = 0,
)

/**
 * YT: runs SSI searches, one at a time: a new query cancels the old one. Instant searches the
 * local index (full text, and embeddings when the model is there) and is done. Deep first shows
 * the same, then (when allowed) asks the AI for search phrases (cached per query), searches
 * YouTube through the app's own search, fetches the captions of the best new videos within the
 * budget (1–3 s apart, stops at a bot check), indexes them and searches again after each one, so
 * results come in while it runs. SSI-2: at its end the AI may grade the top passages
 * ([rerank]); the learnt weights and cut-off apply to both depths.
 */
object YtSsiSearch {
    private const val TAG = "YtSsiSearch"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _run = MutableStateFlow(YtSsiRun())
    val run: StateFlow<YtSsiRun> = _run.asStateFlow()
    private var job: Job? = null
    private var counter = 0L

    /** Starts a search; returns its id (the [YtSsiRun.id] its results carry). */
    fun start(context: Context, query: String, depth: YtSsiDepth): Long {
        val app = context.applicationContext
        job?.cancel()
        val id = ++counter
        _run.value = YtSsiRun(id = id, query = query, depth = depth, phase = YtSsiPhase.LOCAL)
        job = scope.launch {
            try {
                search(app, id, query, depth)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "search failed", e)
                set(id) { it.copy(phase = YtSsiPhase.DONE, notes = it.notes + YtSsiRunNote.SEARCH_FAILED) }
            }
        }
        return id
    }

    fun cancel() {
        job?.cancel()
        _run.update { if (it.phase == YtSsiPhase.DONE || it.phase == YtSsiPhase.IDLE) it else it.copy(phase = YtSsiPhase.DONE) }
    }

    fun clear() {
        job?.cancel()
        _run.value = YtSsiRun()
    }

    /** SSI-3: the query as SSI reads it, with the index's channel names known (off the main thread). */
    fun intentOf(query: String): YtSsiIntent {
        val known = YtSsi.db.docs().values.mapNotNull { it.channelName }.filter { it.isNotBlank() }.distinct()
        return YtSsiIntentParser.parse(query, System.currentTimeMillis(), ZoneId.systemDefault(), known)
    }

    /** SSI-3: shows a saved research's [hits] as a finished search of [query]; returns its id. */
    fun restore(query: String, intent: YtSsiIntent, hits: List<YtSsiHit>): Long {
        job?.cancel()
        val id = ++counter
        _run.value = YtSsiRun(
            id = id,
            query = query,
            depth = YtSsiDepth.INSTANT,
            intent = intent,
            phase = YtSsiPhase.DONE,
            hits = hits,
            notes = setOf(YtSsiRunNote.RESTORED),
            indexedVideos = runCatching { YtSsi.db.docs().size }.getOrDefault(0),
        )
        return id
    }

    private fun set(id: Long, change: (YtSsiRun) -> YtSsiRun) {
        _run.update { if (it.id == id) change(it) else it }
    }

    private suspend fun search(context: Context, id: Long, query: String, depth: YtSsiDepth) {
        val started = SystemClock.elapsedRealtime()
        val db = YtSsi.db
        // the archive's new transcripts first (local and quick when nothing changed)
        runCatching { YtSsi.indexer.indexArchive(System.currentTimeMillis() + 3_000) }
        val docs = withContext(Dispatchers.IO) { db.docs() }
        val known = docs.values.mapNotNull { it.channelName }.filter { it.isNotBlank() }.distinct()
        val intent = YtSsiIntentParser.parse(query, System.currentTimeMillis(), ZoneId.systemDefault(), known)
        val notes = HashSet<YtSsiRunNote>()
        YtSsiModels.refresh(context)
        when (YtSsiModels.state.value) {
            YtSsiModelState.Ready -> Unit
            YtSsiModelState.Unsupported -> notes += YtSsiRunNote.SEMANTIC_UNSUPPORTED
            else -> notes += YtSsiRunNote.LEXICAL_ONLY
        }
        if (docs.isEmpty()) notes += YtSsiRunNote.EMPTY_INDEX
        set(id) { it.copy(intent = intent, notes = notes, indexedVideos = docs.size) }

        var extra = emptyList<String>()
        val fromYouTube = HashSet<String>()
        suspend fun emit(phase: YtSsiPhase, fetched: Int = 0, budget: Int = 0) {
            val (hits, semanticNotes) = local(context, intent, extra, fromYouTube)
            set(id) {
                it.copy(
                    phase = phase,
                    hits = hits,
                    notes = it.notes + semanticNotes - if (YtSsi.db.docs().isNotEmpty()) setOf(YtSsiRunNote.EMPTY_INDEX) else emptySet(),
                    fetched = fetched,
                    budget = budget,
                    indexedVideos = YtSsi.db.docs().size,
                )
            }
        }
        if (intent.empty) {
            set(id) { it.copy(phase = YtSsiPhase.DONE) }
            return
        }
        lastScan = null
        val localStart = SystemClock.elapsedRealtime()
        emit(if (depth == YtSsiDepth.DEEP) YtSsiPhase.AI else YtSsiPhase.DONE)
        if (depth == YtSsiDepth.INSTANT) {
            val now = SystemClock.elapsedRealtime()
            lastInstant = YtSsiLatency(System.currentTimeMillis(), now - started, now - localStart, lastScan)
            return
        }

        // the AI's search phrases and words (opt-in, cached per query)
        var plan: YtSsiAiPlan? = null
        val setup = if (YtSsi.allowAi()) runCatching { YtNewsSummaryKey.setup() }.getOrNull() else null
        if (setup != null) {
            val now = System.currentTimeMillis()
            plan = withContext(Dispatchers.IO) { db.aiPlan(intent.cacheKey, now) }
            if (plan != null) {
                set(id) { it.copy(notes = it.notes + YtSsiRunNote.AI_CACHED) }
            } else {
                plan = try {
                    withTimeoutOrNull(AI_TIMEOUT_MS) {
                        YtSsiAiRules.parse(YtScriptTextAis.of(setup).complete(YtSsiAiRules.SYSTEM, YtSsiAiRules.user(intent), YtSsiAiRules.schema()))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "ai plan failed: $e")
                    null
                }
                if (plan != null) {
                    withContext(Dispatchers.IO) { db.putAiPlan(intent.cacheKey, plan, now) }
                    set(id) { it.copy(notes = it.notes + YtSsiRunNote.AI_USED) }
                } else {
                    set(id) { it.copy(notes = it.notes + YtSsiRunNote.AI_FAILED) }
                }
            }
            if (plan != null && !intent.exact) {
                extra = plan.keywords
                emit(YtSsiPhase.DISCOVER)
            }
        }

        if (!YtSsi.mayAsk(context)) {
            set(id) { it.copy(notes = it.notes + YtSsiRunNote.PAUSED) }
            finish(context, id, intent, setup)
            return
        }
        set(id) { it.copy(phase = YtSsiPhase.DISCOVER) }
        val budget = YtSsi.deepBudget()
        val candidates = discover(intent, plan, docs.keys)
        val todo = candidates.take(budget)
        set(id) { it.copy(phase = YtSsiPhase.FETCH, budget = todo.size) }
        var fetched = 0
        for ((i, video) in todo.withIndex()) {
            if (i > 0) delay(YtSsiPrefs.deepJitterMs())
            val videoId = video.url?.toID() ?: continue
            try {
                YtSsi.indexer.indexOnline(videoId)
            } catch (e: YtSsiIndexer.Blocked) {
                YtSsi.blocked(context, e.message ?: "blocked")
                set(id) { it.copy(notes = it.notes + YtSsiRunNote.BLOCKED) }
                break
            }
            fetched++
            fromYouTube += videoId
            // the new passages get their vectors now (a few seconds per video), then everything is searched again
            if (YtSsiModels.state.value == YtSsiModelState.Ready) {
                runCatching { YtSsi.indexer.embedPending(System.currentTimeMillis() + EMBED_PER_VIDEO_MS, only = setOf(videoId)) }
                    .onFailure { if (it is CancellationException) throw it }
            }
            emit(YtSsiPhase.FETCH, fetched, todo.size)
        }
        if (YtSsiRunNote.BLOCKED !in _run.value.notes) YtSsi.ranOk(context, "deep")
        emit(if (setup != null && YtSsi.aiRerank()) YtSsiPhase.RERANK else YtSsiPhase.DONE, fetched, todo.size)
        finish(context, id, intent, setup)
    }

    /** Deep's last step: the AI re-rank of the hits that are there (when on), then done. */
    private suspend fun finish(context: Context, id: Long, intent: YtSsiIntent, setup: YtNewsProviderSetup?) {
        val hits = _run.value.takeIf { it.id == id }?.hits.orEmpty()
        if (setup == null || !YtSsi.aiRerank() || hits.none { it.segmentId != null }) {
            set(id) { it.copy(phase = YtSsiPhase.DONE) }
            return
        }
        set(id) { it.copy(phase = YtSsiPhase.RERANK) }
        val (ranked, note) = rerank(context, intent, hits, setup)
        set(id) { it.copy(phase = YtSsiPhase.DONE, hits = ranked, notes = it.notes + listOfNotNull(note)) }
    }

    /**
     * The AI's grades of the first [YtSsiRerank.TOP] passages of [hits], fused with their local
     * order: from the cache when the same passages were graded for this query by this model,
     * else one request within today's budget. Any failure keeps [hits] as they are and says so.
     */
    internal suspend fun rerank(context: Context, intent: YtSsiIntent, hits: List<YtSsiHit>, setup: YtNewsProviderSetup): Pair<List<YtSsiHit>, YtSsiRunNote?> {
        val db = YtSsi.db
        val source = source()
        val passages = withContext(Dispatchers.IO) { YtSsiRerank.passages(hits, source::segment, source::neighbour) }
        if (passages.isEmpty()) return hits to null
        val now = System.currentTimeMillis()
        val key = YtSsiRerank.cacheKey(intent.cacheKey, passages.map { it.segmentId }, "${setup.kind.id}:${setup.model}")
        withContext(Dispatchers.IO) { db.rerank(key, now) }?.let { cached ->
            return withContext(Dispatchers.IO) { YtSsiRanker.applyAi(intent, hits, cached, source) } to YtSsiRunNote.RERANK_CACHED
        }
        if (!YtSsi.spendRerank(context, now)) return hits to YtSsiRunNote.RERANK_BUDGET
        val grades = try {
            withTimeoutOrNull(RERANK_TIMEOUT_MS) {
                YtSsiRerank.parse(YtScriptTextAis.of(setup).complete(YtSsiRerank.SYSTEM, YtSsiRerank.user(intent, passages), YtSsiRerank.schema()), passages)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "rerank failed: $e")
            null
        } ?: return hits to YtSsiRunNote.RERANK_FAILED
        withContext(Dispatchers.IO) { db.putRerank(key, grades, now) }
        return withContext(Dispatchers.IO) { YtSsiRanker.applyAi(intent, hits, grades, source) } to YtSsiRunNote.RERANK_USED
    }

    /** The index as the ranker reads it. */
    private fun source(): YtSsiRanker.Source {
        val db = YtSsi.db
        return object : YtSsiRanker.Source {
            val docs = db.docs()
            override fun segment(id: Long) = db.segment(id)
            override fun neighbour(videoId: String, ord: Int) = db.neighbour(videoId, ord)
            override fun doc(videoId: String) = docs[videoId]
        }
    }

    /**
     * YouTube's results for the query (and the AI's phrases): videos only, no Shorts or live,
     * not yet indexed, interleaved across the searches, those that already break a time or
     * channel filter left out (their meta is in the result).
     */
    private suspend fun discover(intent: YtSsiIntent, plan: YtSsiAiPlan?, indexed: Set<String>): List<StreamItem> {
        val searches = YtSsiAiRules.searches(intent, plan).take(YtSsiPrefs.MAX_SEARCHES)
        val lists = ArrayList<List<StreamItem>>()
        for ((i, search) in searches.withIndex()) {
            if (i > 0) delay(YtSsiPrefs.deepJitterMs())
            val items = try {
                withContext(Dispatchers.IO) { MediaServiceRepository.instance.getSearchResults(search, "videos") }.items
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "youtube search failed: $e")
                continue
            }
            lists += items.filter { it.type == StreamItem.TYPE_STREAM && it.isShort != true && it.duration > 0 }.map { it.toStreamItem() }
        }
        val out = LinkedHashMap<String, StreamItem>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (list in lists) {
                val item = list.getOrNull(i) ?: continue
                val videoId = item.url?.toID() ?: continue
                if (videoId in indexed || videoId in out) continue
                val uploaded = item.uploaded.takeIf { it > 0 }
                if (intent.time != null && uploaded != null && !intent.time.contains(uploaded)) continue
                val candidate = YtSsiFusion.Candidate(item.uploaderUrl?.toID(), item.uploaderName, uploaded, null, "")
                if (intent.channels.isNotEmpty() && intent.channels.none { YtSsiFusion.channelMatches(it, candidate) }) continue
                if (intent.excludeChannels.any { YtSsiFusion.channelMatches(it, candidate) }) continue
                out[videoId] = item
            }
        }
        return out.values.toList()
    }

    /**
     * Full text + embeddings over the index, fused and filtered. [learned] uses the weights and
     * the cut-off as feedback moved them (the benchmark's SSI-1 way passes false); [cutOff]
     * replaces the meaning cut-off (the calibration's sweep).
     */
    internal suspend fun local(
        context: Context,
        intent: YtSsiIntent,
        extra: List<String> = emptyList(),
        fromYouTube: Set<String> = emptySet(),
        learned: Boolean = true,
        cutOff: Float? = null,
    ): Pair<List<YtSsiHit>, Set<YtSsiRunNote>> {
        val db = YtSsi.db
        val notes = HashSet<YtSsiRunNote>()
        val match = YtSsiFusion.ftsQuery(intent, extra)
        val lexical = if (match == null) emptyList() else withContext(Dispatchers.IO) { db.lexicalSegments(match) }
        val titles = if (match == null) emptyList() else withContext(Dispatchers.IO) { db.lexicalDocs(match) }
        var semantic = emptyList<Long>()
        var model: String? = null
        if (YtSsiModels.state.value == YtSsiModelState.Ready && intent.semanticText.isNotBlank()) {
            val index = YtSsi.vectors()
            if (index != null) {
                // the same query is embedded once per search, not on every refresh of Deep
                val query = queryVector?.takeIf { it.first == intent.semanticText }?.second
                    ?: YtSsiModels.use(context) { it.embed(listOf(intent.semanticText), query = true).first() }
                        ?.also { queryVector = intent.semanticText to it }
                if (query != null) {
                    val floor = cutOff ?: if (learned) YtSsi.minCosine(context) else YtSsiVectorIndex.MIN_COSINE
                    val t = SystemClock.elapsedRealtime()
                    val found = withContext(Dispatchers.Default) { index.search(query, SEMANTIC_K) }
                    lastScan = Scan(SystemClock.elapsedRealtime() - t, index.size, index.lastThreads)
                    semantic = YtSsiVectorIndex.cut(found, floor).map { it.first }
                    model = YtSsiModels.spec.version
                }
            }
            val pending = withContext(Dispatchers.IO) { db.unembeddedCount(YtSsiModels.spec.version) }
            if (pending > 0) notes += YtSsiRunNote.VECTORS_PENDING
        }
        val source = source()
        val lists = YtSsiRanker.Lists(
            lexical = lexical,
            semantic = semantic,
            titles = titles.map { it.first },
            inTitle = titles.filter { it.second }.map { it.first }.toSet(),
            model = model,
            fromYouTube = fromYouTube,
            aiWords = extra.isNotEmpty(),
        )
        val weights = if (learned) YtSsi.weights(context, intent) else intent.weights
        val hits = withContext(Dispatchers.IO) { YtSsiRanker.rank(intent, lists, source, weights = weights) }
        return hits to notes
    }

    @Volatile
    private var queryVector: Pair<String, FloatArray>? = null

    /** One vector scan: its time, the rows it went over and the threads it used. */
    data class Scan(val ms: Long, val rows: Int, val threads: Int)

    @Volatile
    private var lastScan: Scan? = null

    /** The last Instant search's times (this session), for Diagnose. */
    @Volatile
    var lastInstant: YtSsiLatency? = null
        private set

    private const val SEMANTIC_K = 200
    private const val AI_TIMEOUT_MS = 25_000L
    private const val RERANK_TIMEOUT_MS = 40_000L
    private const val EMBED_PER_VIDEO_MS = 20_000L
}
