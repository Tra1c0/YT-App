package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.util.Log
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.Streams
import com.github.libretube.extensions.toID
import com.github.libretube.ui.yt.archive.YtArchive
import com.github.libretube.ui.yt.archive.YtArchiveMeta
import com.github.libretube.ui.yt.archive.YtArchiveText
import com.github.libretube.ui.yt.news.YtNewsCaptions
import com.github.libretube.ui.yt.news.YtNewsTrack
import com.github.libretube.util.YtStreamError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

/**
 * YT: fills SSI's index from two sources.
 * - The archive ([YtArchive.store]): its transcripts (gzip JSON lines, LINE timing), chapters
 *   and meta; a vanished video stays searchable from its copy and is marked so.
 * - Captions fetched on demand (Deep search, background runs over the feed): NewPipe's stream
 *   info, then the caption file of the best track. The spoken language's speech-recognition
 *   track is taken first, as json3 for its word starts (WORD timing); a manual track (LINE) when
 *   there is no recognised one; TTML when json3 gives nothing.
 * Every passage is cut by [YtSsiSegmenter] first; chapters are passages of their own. With the
 * model, [migrate] later re-cuts a video where its meaning changes ([YtSsiSemanticSegmenter]).
 */
class YtSsiIndexer(private val context: Context, private val db: YtSsiDb) {
    /** A bot check or HTTP 429: stop asking YouTube. */
    class Blocked(reason: String) : IOException(reason)

    enum class Outcome { INDEXED, NO_CAPTIONS, SKIPPED, GONE }

    /**
     * Indexes every archived video not yet in the index (or of an analysis older than
     * [YtSsiVersions.MIN_KEPT]; newer ones are re-cut by [migrate]). Local only.
     */
    suspend fun indexArchive(deadline: Long = Long.MAX_VALUE): Int = withContext(Dispatchers.IO) {
        if (!YtArchive.enabled() && YtArchive.store.all().isEmpty()) return@withContext 0
        val docs = db.docs()
        var count = 0
        for (meta in YtArchive.store.all()) {
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() > deadline) break
            val doc = docs[meta.videoId]
            if (doc != null && doc.version >= YtSsiVersions.MIN_KEPT) {
                // already indexed (from the archive or from captions): only the vanish mark follows
                if (doc.vanished != meta.vanished) db.setVanished(meta.videoId, meta.vanished)
                continue
            }
            if (indexArchived(meta)) count++
        }
        count
    }

    /** One archived video into the index; false without a transcript and without a doc worth keeping. */
    fun indexArchived(meta: YtArchiveMeta): Boolean {
        val files = YtArchive.store.files(meta.videoId) ?: return false
        val lines = YtArchiveText.read(files.transcript)
        val track = YtSsiCaptions.archiveLines(lines.map { Triple(it.start, it.end, it.text) })
        val chapters = meta.chapters.map { YtSsiChapter(it.start, it.title) }
        val doc = YtSsiDoc(
            videoId = meta.videoId,
            title = meta.title,
            channelId = meta.channelId,
            channelName = meta.channelName,
            description = meta.description,
            uploaded = meta.uploaded,
            durationSec = meta.durationSec,
            language = YtSsiFusion.base(meta.transcriptLanguage),
            autoCaptions = meta.transcriptAuto,
            timing = if (track.units.isEmpty()) YtSsiTiming.APPROX else YtSsiTiming.LINE,
            source = YtSsiSource.ARCHIVE,
            vanished = meta.vanished,
            chapters = chapters,
            indexedAt = System.currentTimeMillis(),
        )
        put(doc, track)
        return true
    }

    /**
     * Fetches and indexes [videoId] (two requests: stream info and one caption file, a third
     * when json3 is empty). A video without captions is kept with its title, description and
     * chapters (APPROX). Throws [Blocked] on a bot check or 429.
     */
    suspend fun indexOnline(videoId: String): Outcome = withContext(Dispatchers.IO) {
        val streams = try {
            MediaServiceRepository.instance.getStreams(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val kind = YtStreamError.classify(e)
            if (kind == YtStreamError.Kind.BOT_CHECK || e is ReCaptchaException) throw Blocked(e.message ?: "bot check")
            if (YtStreamError.removesFromFeed(kind)) return@withContext Outcome.GONE
            Log.i(TAG, "streams failed for $videoId: $e")
            return@withContext Outcome.SKIPPED
        }
        if (streams.isLive || streams.isShort) return@withContext Outcome.SKIPPED
        val (track, picked) = captions(streams) ?: (YtSsiTrack(emptyList(), YtSsiTiming.APPROX) to null)
        val chapters = streams.chapters.map { YtSsiChapter(it.start * 1000, it.title) }
        val archived = YtArchive.store[videoId]
        val doc = YtSsiDoc(
            videoId = videoId,
            title = streams.title,
            channelId = streams.uploaderUrl?.toID(),
            channelName = streams.uploader,
            description = streams.description.take(MAX_DESCRIPTION),
            uploaded = streams.uploaded?.takeIf { it > 0 } ?: streams.uploadTimestamp?.toEpochMilliseconds(),
            durationSec = streams.duration.takeIf { it > 0 },
            language = YtSsiFusion.base(picked?.code),
            autoCaptions = picked?.autoGenerated,
            timing = if (track.units.isEmpty()) YtSsiTiming.APPROX else track.timing,
            source = YtSsiSource.CAPTIONS,
            vanished = archived?.vanished == true,
            chapters = chapters,
            indexedAt = System.currentTimeMillis(),
        )
        put(doc, track)
        if (track.units.isEmpty()) Outcome.NO_CAPTIONS else Outcome.INDEXED
    }

    private fun put(doc: YtSsiDoc, track: YtSsiTrack) {
        val speech = YtSsiSegmenter.segments(doc.videoId, track, doc.chapters)
        val chapters = YtSsiSegmenter.chapterSegments(doc.videoId, doc.chapters, doc.durationSec?.times(1000), speech.size)
        db.putVideo(doc, speech + chapters)
    }

    /**
     * The best track's units and the track. The recognised track in the spoken language goes
     * first (its json3 has word starts); otherwise [YtNewsCaptions.pick]'s choice.
     */
    private suspend fun captions(streams: Streams): Pair<YtSsiTrack, YtNewsTrack>? {
        val tracks = streams.subtitles.filter { !it.url.isNullOrBlank() }
        val described = tracks.map { YtNewsTrack(it.code, it.autoGenerated == true) }
        val index = described.indexOfFirst { it.autoGenerated }.takeIf { it >= 0 } ?: YtNewsCaptions.pick(described) ?: return null
        val url = tracks[index].url ?: return null
        val json3 = get(YtSsiCaptions.json3Url(url))?.let { YtSsiCaptions.parseJson3(it) }
        if (json3 != null && json3.units.isNotEmpty()) return json3 to described[index]
        val body = get(url) ?: return null
        val lines = YtSsiCaptions.lines(YtNewsCaptions.parse(body))
        return if (lines.units.isEmpty()) null else lines to described[index]
    }

    private suspend fun get(url: String): String? = try {
        runInterruptible(Dispatchers.IO) { NewPipe.getDownloader().get(url).responseBody() }
    } catch (e: ReCaptchaException) {
        throw Blocked("429 captions")
    } catch (e: IOException) {
        Log.i(TAG, "captions failed: $e")
        null
    }

    /** What [migrate] did: videos raised to the new analysis as they were, re-cut, unchanged, failed. */
    data class Migration(val raised: Int = 0, val recut: Int = 0, val kept: Int = 0, val failed: Int = 0)

    /**
     * SSI-2's re-index, incremental: every video of an older analysis is raised to
     * [YtSsiVersions.ANALYSIS] as it is (its rule passages are valid). With the model ready, the
     * videos still cut by the rules are then re-cut by meaning, newest first, one at a time
     * until [deadline]: their units come from the archive's transcript when there is one, else
     * from the stored passages (nothing is fetched). A video whose passages come out the same
     * keeps them and their vectors; otherwise its new passages replace the old (their vectors
     * are made again by [embedPending]).
     */
    suspend fun migrate(deadline: Long): Migration {
        val raised = withContext(Dispatchers.IO) { db.raiseVersion(YtSsiVersions.MIN_KEPT, YtSsiVersions.ANALYSIS) }
        if (!YtSsiModels.installed(context) || YtSsiModels.state.value == YtSsiModelState.Unsupported) return Migration(raised = raised)
        val todo = withContext(Dispatchers.IO) { db.docs().values }.filter { it.segmentation == YtSsiSegmentation.RULES }.sortedByDescending { it.indexedAt }
        var recut = 0
        var kept = 0
        var failed = 0
        for (doc in todo) {
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() > deadline) break
            when (recut(doc, deadline)) {
                true -> recut++
                false -> kept++
                null -> {
                    failed++
                    break
                }
            }
        }
        return Migration(raised, recut, kept, failed)
    }

    /** Re-cuts [doc] by meaning: true when its passages changed, false when they stayed, null when the model or the deadline stopped it. */
    private suspend fun recut(doc: YtSsiDoc, deadline: Long): Boolean? {
        val stored = withContext(Dispatchers.IO) { db.segments(doc.videoId) }
        val speech = stored.filter { it.kind == YtSsiSegmentKind.SPEECH }.sortedBy { it.ord }
        if (speech.isEmpty()) {
            withContext(Dispatchers.IO) { db.setSegmentation(doc.videoId, YtSsiSegmentation.SEMANTIC, YtSsiVersions.ANALYSIS) }
            return false
        }
        val track = (if (doc.source == YtSsiSource.ARCHIVE) withContext(Dispatchers.IO) { archiveTrack(doc.videoId) } else null)
            ?.takeIf { it.units.isNotEmpty() } ?: YtSsiSemanticSegmenter.fromPassages(speech)
        val sentences = YtSsiSemanticSegmenter.sentences(track.units, doc.chapters)
        val vectors = ArrayList<FloatArray>(sentences.size)
        for (batch in sentences.chunked(SENTENCE_BATCH)) {
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() > deadline) return null
            val got = withContext(Dispatchers.Default) { YtSsiModels.use(context) { it.embed(batch.map { s -> s.text }, query = false) } } ?: return null
            if (got.size != batch.size) return null
            vectors += got
        }
        val cut = YtSsiSemanticSegmenter.segments(doc.videoId, track, sentences, vectors, doc.chapters)
        val semantic = doc.copy(segmentation = YtSsiSegmentation.SEMANTIC, version = YtSsiVersions.ANALYSIS)
        return withContext(Dispatchers.IO) {
            if (YtSsiSemanticSegmenter.same(cut, speech)) {
                db.setSegmentation(doc.videoId, YtSsiSegmentation.SEMANTIC, YtSsiVersions.ANALYSIS)
                false
            } else {
                val chapters = YtSsiSegmenter.chapterSegments(doc.videoId, doc.chapters, doc.durationSec?.times(1000), cut.size)
                db.putVideo(semantic, cut + chapters)
                true
            }
        }
    }

    /** The archive's transcript of [videoId] as units, or null. */
    private fun archiveTrack(videoId: String): YtSsiTrack? = runCatching {
        val files = YtArchive.store.files(videoId) ?: return null
        YtSsiCaptions.archiveLines(YtArchiveText.read(files.transcript).map { Triple(it.start, it.end, it.text) })
    }.getOrNull()

    /** Embeds passages without a vector, [batch] at a time, until [deadline]; returns how many. */
    suspend fun embedPending(deadline: Long, batch: Int = 16, only: Set<String>? = null): Int {
        var done = 0
        val model = YtSsiModels.spec.version
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val rows = withContext(Dispatchers.IO) { if (only == null) db.unembedded(model, batch) else db.unembeddedOf(model, only, batch) }
            if (rows.isEmpty()) break
            val vectors = withContext(Dispatchers.Default) {
                YtSsiModels.use(context) { it.embed(rows.map { (_, text) -> text }, query = false) }
            } ?: break
            withContext(Dispatchers.IO) {
                db.putVectors(model, rows.mapIndexed { i, (id, _) ->
                    val (q, scale) = YtSsiVectors.quantize(vectors[i])
                    Triple(id, scale, q)
                })
            }
            done += rows.size
        }
        return done
    }

    private companion object {
        const val TAG = "YtSsiIndexer"
        const val MAX_DESCRIPTION = 5_000
        const val SENTENCE_BATCH = 16
    }
}
