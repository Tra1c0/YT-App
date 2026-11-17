package com.github.libretube.ui.yt.news

import com.github.libretube.LibreTubeApp
import com.github.libretube.api.MediaServiceRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

/**
 * YT: the "Kurzfassung" of any video, on demand (watch page and card menu). It reuses the News
 * summary parts: the user's provider and key ([YtNewsSummaryKey]), the captions, the cited
 * statements with their quote check ([YtNewsProviders]) and the disk cache of [YtNewsSummary].
 * Nothing is requested before [request] (a tap): it costs the user money. A transcript longer
 * than one request takes is read in at most [YtKurzSlicing.MAX_SLICES] slices (by chapters when
 * there are some) whose statements are merged; the digest says how many ([YtNewsDigest.parts]).
 * Several videos may run at once; a running request is not cancelled when its block goes away
 * (the answer is cached and shows next time).
 */
object YtKurzSummary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _states = MutableStateFlow<Map<String, YtNewsSummaryState>>(emptyMap())
    val states: StateFlow<Map<String, YtNewsSummaryState>> = _states.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** The state key of [videoId]; names the provider and model, so a summary never shows as another one's. */
    fun keyOf(videoId: String): String = YtNewsSummaryCacheRules.scoped(YtNewsSummaryKey.cacheScope(), "kurz-$videoId")

    /** Shows a cached summary of [key] at once, if there is one (disk read). */
    suspend fun loadCached(key: String) {
        if (_states.value[key] is YtNewsSummaryState.Ready) return
        YtNewsSummary.cached(key)?.let { set(key, YtNewsSummaryState.Ready(it)) }
    }

    /** Asks for the summary of [videoId] (the tap). A request already running for it is kept. */
    fun request(videoId: String, title: String, channel: String?) {
        val key = keyOf(videoId)
        if (jobs[key]?.isActive == true) return
        set(key, YtNewsSummaryState.Loading)
        jobs[key] = scope.launch {
            val state = try {
                run(key, videoId, listOfNotNull(channel?.takeIf { it.isNotBlank() }, title.takeIf { it.isNotBlank() }).joinToString(": ").ifEmpty { videoId })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                YtNewsSummaryState.Failed(YtNewsHttp.failure(e))
            }
            set(key, state)
        }
    }

    private suspend fun run(key: String, videoId: String, title: String): YtNewsSummaryState {
        YtNewsSummary.cached(key)?.let { return YtNewsSummaryState.Ready(it) }
        val setup = YtNewsSummaryKey.setup() ?: return YtNewsSummaryState.Failed(YtNewsSummaryFailure.NO_KEY)
        val streams = runCatching { MediaServiceRepository.instance.getStreams(videoId) }.getOrNull()
            ?: return YtNewsSummaryState.Failed(YtNewsSummaryFailure.OFFLINE)
        val cues = YtNewsSummary.cuesOf(streams) ?: return YtNewsSummaryState.Failed(YtNewsSummaryFailure.NO_TRANSCRIPT)
        val whole = YtNewsTranscriptDoc.build(cues)
        if (whole.lines == 0) return YtNewsSummaryState.Failed(YtNewsSummaryFailure.NO_TRANSCRIPT)
        val slices = if (whole.cut) YtKurzSlicing.slices(cues, streams.chapters.map { it.start }) else listOf(cues)
        val provider = YtNewsProviders.of(setup.kind)
        val results = ArrayList<YtNewsSummaryResult>()
        var cut = false
        for (slice in slices) {
            val doc = YtNewsTranscriptDoc.build(slice)
            if (doc.lines == 0) continue
            cut = cut || doc.cut
            results += try {
                provider.summarize(setup, listOf(YtNewsSource(videoId, title, doc)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                YtNewsSummaryResult.Failed(provider.failure(e))
            }
        }
        val done = results.filterIsInstance<YtNewsSummaryResult.Done>()
        if (done.isEmpty()) {
            val reason = results.filterIsInstance<YtNewsSummaryResult.Failed>().firstOrNull()?.reason ?: YtNewsSummaryFailure.NO_TRANSCRIPT
            return YtNewsSummaryState.Failed(reason)
        }
        val statements = if (done.size == 1) done[0].statements else YtKurzSlicing.merge(done.map { it.statements })
        val digest = YtNewsDigest(key, statements, System.currentTimeMillis(), cut, parts = slices.size)
        store(digest)
        return YtNewsSummaryState.Ready(digest)
    }

    private fun set(key: String, state: YtNewsSummaryState) {
        _states.update { it + (key to state) }
    }

    /** Same folder and file naming as [YtNewsSummary], so its read and weekly pruning apply. */
    private fun store(digest: YtNewsDigest) {
        runCatching {
            val dir = File(LibreTubeApp.instance.cacheDir, "yt_newssum").apply { mkdirs() }
            File(dir, YtNewsSummaryCacheRules.fileName(digest.key)).writeText(YtNewsSummaryCacheRules.encode(digest))
        }
    }
}
