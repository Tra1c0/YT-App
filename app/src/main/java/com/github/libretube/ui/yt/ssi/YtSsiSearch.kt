package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.util.Log
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toID
import com.github.libretube.ui.yt.archive.script.YtScriptTextAis
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

/** Instant: the local index only. Deep: also YouTube's search and fresh captions. */
enum class YtSsiDepth { INSTANT, DEEP }

/** Where a search is. */
enum class YtSsiPhase { IDLE, LOCAL, AI, DISCOVER, FETCH, DONE }

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
 * results come in while it runs.
 */
object YtSsiSearch {
    private const val TAG = "YtSsiSearch"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _run = MutableStateFlow(YtSsiRun())
    val run: StateFlow<YtSsiRun> = _run.asStateFlow()
    private var job: Job? = null
    private var counter = 0L

    fun start(context: Context, query: String, depth: YtSsiDepth) {
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
    }

    fun cancel() {
        job?.cancel()
        _run.update { if (it.phase == YtSsiPhase.DONE || it.phase == YtSsiPhase.IDLE) it else it.copy(phase = YtSsiPhase.DONE) }
    }

    fun clear() {
        job?.cancel()
        _run.value = YtSsiRun()
    }

    private fun set(id: Long, change: (YtSsiRun) -> YtSsiRun) {
        _run.update { if (it.id == id) change(it) else it }
    }

    private suspend fun search(context: Context, id: Long, query: String, depth: YtSsiDepth) {
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
            val (hits, semanticNotes) = retrieve(context, intent, extra, fromYouTube)
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
        emit(if (depth == YtSsiDepth.DEEP) YtSsiPhase.AI else YtSsiPhase.DONE)
        if (depth == YtSsiDepth.INSTANT) return

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
            set(id) { it.copy(phase = YtSsiPhase.DONE, notes = it.notes + YtSsiRunNote.PAUSED) }
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
        emit(YtSsiPhase.DONE, fetched, todo.size)
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

    /** Full text + embeddings over the index, fused and filtered. */
    private suspend fun retrieve(context: Context, intent: YtSsiIntent, extra: List<String>, fromYouTube: Set<String>): Pair<List<YtSsiHit>, Set<YtSsiRunNote>> {
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
                    semantic = YtSsiVectorIndex.cut(withContext(Dispatchers.Default) { index.search(query, SEMANTIC_K) }).map { it.first }
                    model = YtSsiModels.spec.version
                }
            }
            val pending = withContext(Dispatchers.IO) { db.unembeddedCount(YtSsiModels.spec.version) }
            if (pending > 0) notes += YtSsiRunNote.VECTORS_PENDING
        }
        val source = object : YtSsiRanker.Source {
            val docs = db.docs()
            override fun segment(id: Long) = db.segment(id)
            override fun neighbour(videoId: String, ord: Int) = db.neighbour(videoId, ord)
            override fun doc(videoId: String) = docs[videoId]
        }
        val lists = YtSsiRanker.Lists(
            lexical = lexical,
            semantic = semantic,
            titles = titles.map { it.first },
            inTitle = titles.filter { it.second }.map { it.first }.toSet(),
            model = model,
            fromYouTube = fromYouTube,
            aiWords = extra.isNotEmpty(),
        )
        val hits = withContext(Dispatchers.IO) { YtSsiRanker.rank(intent, lists, source) }
        return hits to notes
    }

    @Volatile
    private var queryVector: Pair<String, FloatArray>? = null

    private const val SEMANTIC_K = 200
    private const val AI_TIMEOUT_MS = 25_000L
    private const val EMBED_PER_VIDEO_MS = 20_000L
}
