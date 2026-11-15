package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.github.libretube.ui.yt.news.YtNewsSummaryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/**
 * YT (SSI-2): the in-app benchmark ("Testbeispiele importieren", "Benchmark ausführen" in the
 * SSI settings). The user's example file ([YtSsiBenchmark]) is copied into app storage
 * (`files/yt_ssi/bench/`). A run first reads the example videos that are not in the index yet
 * (like Deep: 1–3 s apart, stops at a bot check), then answers every example against the live
 * index two ways:
 * - SSI-2 as it is: learnt weights and cut-off, checked play start, and the AI re-rank when it is
 *   on (cached; it counts against the day's budget);
 * - the SSI-1 way on the same index: the intent's own weights, the fixed cut-off, no AI and the
 *   SSI-1 play start. The passages are the same for both (the index has one cut).
 * The result is stored; the next run shows the change against it (on the same examples).
 */
object YtSsiBenchRunner {
    private const val TAG = "YtSsiBenchRunner"

    private fun dir(context: Context) = File(context.filesDir, "yt_ssi/bench").apply { mkdirs() }
    private fun examplesFile(context: Context) = File(dir(context), "examples.json")
    private fun lastFile(context: Context) = File(dir(context), "last.json")

    /** Reads, checks and keeps the file at [uri]; returns what it holds. Throws [YtSsiBenchmark.Invalid] when unusable. */
    suspend fun import(context: Context, uri: Uri): YtSsiBenchmark.Examples = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(YtSsiBenchmark.MAX_FILE_BYTES + 1)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            if (read > YtSsiBenchmark.MAX_FILE_BYTES) throw YtSsiBenchmark.Invalid("file larger than 1 MB")
            buffer.copyOf(read)
        } ?: throw YtSsiBenchmark.Invalid("file not readable")
        val text = String(bytes, Charsets.UTF_8)
        val examples = YtSsiBenchmark.parse(text)
        val target = examplesFile(context)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
        examples
    }

    fun examples(context: Context): YtSsiBenchmark.Examples? = runCatching {
        examplesFile(context).takeIf { it.isFile }?.readText()?.let { YtSsiBenchmark.parse(it) }
    }.getOrNull()

    fun last(context: Context): YtSsiBenchmark.Run? = runCatching { YtSsiBenchmark.decode(lastFile(context).takeIf { it.isFile }?.readText()) }.getOrNull()

    /** Progress of a run: [done] of [total] examples; [fetching] while example videos are read. */
    data class Progress(val done: Int, val total: Int, val fetching: Int = 0)

    data class Outcome(val run: YtSsiBenchmark.Run, val previous: YtSsiBenchmark.Run?, val blocked: Boolean)

    suspend fun run(context: Context, progress: (Progress) -> Unit): Outcome = withContext(Dispatchers.Default) { runOn(context, progress) }

    private suspend fun runOn(context: Context, progress: (Progress) -> Unit): Outcome {
        val set = examples(context) ?: throw YtSsiBenchmark.Invalid("no examples imported")
        val previous = last(context)
        val db = YtSsi.db
        runCatching { YtSsi.indexer.indexArchive(System.currentTimeMillis() + 3_000) }

        // the example videos the index does not have yet
        val known = withContext(Dispatchers.IO) { db.docs() }.keys
        val missing = set.examples.mapNotNull { it.videoId }.distinct().filter { it !in known }
        var fetched = 0
        var blocked = false
        if (missing.isNotEmpty() && YtSsi.mayAsk(context)) {
            for ((i, videoId) in missing.withIndex()) {
                progress(Progress(0, set.examples.size, fetching = i + 1))
                if (i > 0) delay(YtSsiPrefs.deepJitterMs())
                try {
                    if (YtSsi.indexer.indexOnline(videoId) != YtSsiIndexer.Outcome.SKIPPED) fetched++
                } catch (e: YtSsiIndexer.Blocked) {
                    YtSsi.blocked(context, e.message ?: "blocked")
                    blocked = true
                    break
                }
                if (YtSsiModels.state.value == YtSsiModelState.Ready) {
                    runCatching { YtSsi.indexer.embedPending(System.currentTimeMillis() + 20_000, only = setOf(videoId)) }
                        .onFailure { if (it is CancellationException) throw it }
                }
            }
        }
        YtSsiModels.refresh(context)
        val docs = withContext(Dispatchers.IO) { db.docs() }
        val channels = docs.values.mapNotNull { it.channelName }.filter { it.isNotBlank() }.distinct()
        val setup = if (YtSsi.aiRerank()) runCatching { YtNewsSummaryKey.setup() }.getOrNull() else null
        val current = ArrayList<YtSsiBenchmark.Answer>()
        val baseline = ArrayList<YtSsiBenchmark.Answer>()
        val zone = ZoneId.systemDefault()
        for ((i, example) in set.examples.withIndex()) {
            progress(Progress(i, set.examples.size))
            val intent = YtSsiIntentParser.parse(example.query, System.currentTimeMillis(), zone, channels)
            // SSI-2
            var t = SystemClock.elapsedRealtime()
            var hits = if (intent.empty) emptyList() else YtSsiSearch.local(context, intent).first
            val latency = SystemClock.elapsedRealtime() - t
            var aiMs: Long? = null
            if (setup != null && hits.any { it.segmentId != null }) {
                t = SystemClock.elapsedRealtime()
                val (ranked, note) = try {
                    YtSsiSearch.rerank(context, intent, hits, setup)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "rerank failed: $e")
                    hits to YtSsiRunNote.RERANK_FAILED
                }
                if (note == YtSsiRunNote.RERANK_USED || note == YtSsiRunNote.RERANK_CACHED) {
                    hits = ranked
                    aiMs = SystemClock.elapsedRealtime() - t
                }
            }
            current += YtSsiBenchmark.Answer(hits.take(5).map { found(it, YtSsiPlayRules.playStart(it, null), it.span) }, latency, aiMs)
            // the SSI-1 way
            t = SystemClock.elapsedRealtime()
            val old = if (intent.empty) emptyList() else YtSsiSearch.local(context, intent, learned = false).first
            val oldLatency = SystemClock.elapsedRealtime() - t
            val oldFound = withContext(Dispatchers.IO) {
                old.take(5).map { hit -> found(hit, YtSsiPlayRules.start(hit.segmentId?.let { db.segment(it) }, hit.hitMs), null) }
            }
            baseline += YtSsiBenchmark.Answer(oldFound, oldLatency)
        }
        progress(Progress(set.examples.size, set.examples.size))
        val now = System.currentTimeMillis()
        val run = YtSsiBenchmark.Run(
            current = YtSsiBenchmark.evaluate(set, current, now),
            baseline = YtSsiBenchmark.evaluate(set, baseline, now),
            fetched = fetched,
            missing = set.examples.mapNotNull { it.videoId }.distinct().count { it !in docs },
            name = set.name,
        )
        withContext(Dispatchers.IO) { runCatching { lastFile(context).writeText(YtSsiBenchmark.encode(run)) } }
        return Outcome(run, previous, blocked)
    }

    /** A hit as the benchmark measures it: its time, its checked span (or its passage) and its play start. */
    private fun found(hit: YtSsiHit, playStart: Long, span: YtSsiSpan?): YtSsiBenchmark.Found = YtSsiBenchmark.Found(
        videoId = hit.videoId,
        hitMs = hit.hitMs,
        spanStartMs = if (hit.segmentId == null) null else span?.startMs ?: hit.startMs,
        spanEndMs = if (hit.segmentId == null) null else span?.endMs ?: hit.endMs,
        playStartMs = playStart,
    )
}
