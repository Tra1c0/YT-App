package com.github.libretube.ui.yt.news

import com.github.libretube.LibreTubeApp
import com.github.libretube.api.MediaServiceRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe

/**
 * YT: the news summaries ("Kurzfassung"), on demand only. A card asks with [request] (one video)
 * or a story page with [summarizeStory] (several, "what does each source say"); the answer
 * shows up in [states] under the key ([keyOf] / [YtNewsSummaryCacheRules.storyKey]).
 *
 * Nothing happens without a provider the user chose and set up ([YtNewsSummaryKey],
 * [YtNewsProviders]). One request runs at a time: a new one cancels the one before, and [cancel]
 * (the overlay closes) stops it. Finished summaries are kept on disk per key for a week
 * ([YtNewsSummaryCacheRules]), so a card seen again costs nothing; the key names the provider and
 * model, so a summary is never shown as another one's.
 */
object YtNewsSummary {
    private const val CACHE_DIR = "yt_newssum"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _states = MutableStateFlow<Map<String, YtNewsSummaryState>>(emptyMap())
    val states: StateFlow<Map<String, YtNewsSummaryState>> = _states.asStateFlow()

    private var running: Job? = null
    private var runningKey: String? = null
    private var pruned = false

    fun keyOf(item: YtNewsItem): String = YtNewsSummaryCacheRules.scoped(YtNewsSummaryKey.cacheScope(), item.id)

    fun stateOf(key: String): YtNewsSummaryState = _states.value[key] ?: YtNewsSummaryState.Idle

    /** A cached summary of [key], if there is one (disk read; call off the main thread). */
    suspend fun cached(key: String): YtNewsDigest? = withContext(Dispatchers.IO) {
        pruneOnce()
        val file = File(dir(), YtNewsSummaryCacheRules.fileName(key))
        if (!file.isFile) return@withContext null
        val digest = runCatching { YtNewsSummaryCacheRules.decode(file.readText(), key, System.currentTimeMillis()) }.getOrNull()
        if (digest != null) set(key, YtNewsSummaryState.Ready(digest))
        digest
    }

    /** Summarises one video (the card's "Kurzfassung" button). */
    fun request(item: YtNewsItem) = start(keyOf(item), listOf(item))

    /**
     * Summarises a story told by several videos: which source says what. The statements carry
     * their video id; the state is under [YtNewsSummaryCacheRules.storyKey] of the items' ids.
     */
    fun summarizeStory(items: List<YtNewsItem>): String {
        val distinct = items.distinctBy { it.id }
        val key = if (distinct.size == 1) {
            keyOf(distinct[0])
        } else {
            YtNewsSummaryCacheRules.scoped(YtNewsSummaryKey.cacheScope(), YtNewsSummaryCacheRules.storyKey(distinct.map { it.id }))
        }
        start(key, distinct)
        return key
    }

    /** Stops the running request of [key] (or any, when null) and forgets its loading state. */
    fun cancel(key: String? = null) {
        val stopped = synchronized(this) {
            val current = runningKey ?: return
            if (key != null && key != current) return
            running?.cancel()
            running = null
            runningKey = null
            current
        }
        if (stateOf(stopped) == YtNewsSummaryState.Loading) set(stopped, YtNewsSummaryState.Idle)
    }

    private fun start(key: String, items: List<YtNewsItem>) {
        if (items.isEmpty()) return
        synchronized(this) {
            if (runningKey == key && running?.isActive == true) return
        }
        cancel()
        set(key, YtNewsSummaryState.Loading)
        synchronized(this) {
            runningKey = key
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                val state = try {
                    run(key, items)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    YtNewsSummaryState.Failed(YtNewsHttp.failure(e))
                }
                synchronized(this@YtNewsSummary) {
                    // a cancelled request leaves the state to whoever cancelled it
                    if (running !== job) return@launch
                    running = null
                    runningKey = null
                }
                set(key, state)
            }
            running = job
            job.start()
        }
    }

    private suspend fun run(key: String, items: List<YtNewsItem>): YtNewsSummaryState {
        cached(key)?.let { return YtNewsSummaryState.Ready(it) }
        val setup = YtNewsSummaryKey.setup() ?: return YtNewsSummaryState.Failed(YtNewsSummaryFailure.NO_KEY)
        val sources = items.mapNotNull { item ->
            val doc = transcript(item) ?: return@mapNotNull null
            YtNewsSource(item.id, title(item), doc)
        }
        if (sources.isEmpty()) return YtNewsSummaryState.Failed(YtNewsSummaryFailure.NO_TRANSCRIPT)
        val provider = YtNewsProviders.of(setup.kind)
        val result = try {
            provider.summarize(setup, sources)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtNewsSummaryResult.Failed(provider.failure(e))
        }
        return when (result) {
            is YtNewsSummaryResult.Failed -> YtNewsSummaryState.Failed(result.reason)
            is YtNewsSummaryResult.Done -> {
                val digest = YtNewsDigest(key, result.statements, System.currentTimeMillis(), sources.any { it.doc.cut })
                store(digest)
                YtNewsSummaryState.Ready(digest)
            }
        }
    }

    /** The document title: the channel first, so the story answer can name its sources. */
    private fun title(item: YtNewsItem): String =
        listOfNotNull(item.channelName?.takeIf { it.isNotBlank() }, item.title.takeIf { it.isNotBlank() })
            .joinToString(": ").ifEmpty { item.id }

    /**
     * The transcript of [item] from the captions the stream extractor lists (the same tracks the
     * player shows); null when the video has none or none could be read.
     */
    private suspend fun transcript(item: YtNewsItem): YtNewsTranscriptDoc? {
        val streams = runCatching { MediaServiceRepository.instance.getStreams(item.id) }.getOrNull() ?: return null
        val tracks = streams.subtitles.filter { !it.url.isNullOrBlank() }
        val pick = YtNewsCaptions.pick(tracks.map { YtNewsTrack(it.code, it.autoGenerated == true) }) ?: return null
        val url = tracks[pick].url ?: return null
        val body = runCatching {
            runInterruptible(Dispatchers.IO) { NewPipe.getDownloader().get(url).responseBody() }
        }.getOrNull() ?: return null
        val cues = YtNewsCaptions.parse(body)
        if (cues.isEmpty()) return null
        return YtNewsTranscriptDoc.build(cues).takeIf { it.lines > 0 }
    }

    private fun set(key: String, state: YtNewsSummaryState) {
        _states.update { it + (key to state) }
    }

    private fun dir(): File = File(LibreTubeApp.instance.cacheDir, CACHE_DIR)

    private fun store(digest: YtNewsDigest) {
        runCatching {
            val dir = dir().apply { mkdirs() }
            File(dir, YtNewsSummaryCacheRules.fileName(digest.key)).writeText(YtNewsSummaryCacheRules.encode(digest))
        }
    }

    /** Deletes summaries older than a week, once per process. */
    private fun pruneOnce() {
        if (pruned) return
        pruned = true
        runCatching {
            val files = dir().listFiles()?.toList().orEmpty()
            val old = YtNewsSummaryCacheRules.toDelete(files.map { it.name to it.lastModified() }, System.currentTimeMillis()).toSet()
            files.filter { it.name in old }.forEach { it.delete() }
        }
    }
}
