package com.github.libretube.ui.yt.ssi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ln

/**
 * YT: SSI's benchmark harness for JVM tests. A fixture (JSON, `src/test/resources/ssi/`) holds
 * videos with timed speech and cases (query, the moments a person verified as relevant). The
 * harness indexes the videos with the real segmenter, answers each query with the real intent
 * parser, BM25 ([YtSsiFusion.bm25] over a matchinfo built here, the same numbers SQLite FTS4
 * gives) and ranker, and measures:
 * - Recall@K: share of relevant moments with a hit in the first K (same video, hit time within
 *   the moment ± [Fixture.toleranceMs]);
 * - nDCG@K with binary relevance (each moment counts once);
 * - timestamp deviation: |play start − moment start| of the first hit on each found moment;
 * - no-match: a case without relevant moments passes only with zero hits.
 * The semantic part needs the model and the phone, so it is not measured here.
 *
 * The fixtures in this repository are synthetic. Real quality needs real, verified examples
 * (video, query, start/end checked by hand) that the user has to supply.
 */
object YtSsiBench {
    @Serializable
    data class Fixture(
        val name: String,
        val synthetic: Boolean = true,
        /** "now" for relative times in queries, as yyyy-mm-dd (UTC). */
        val now: String = "2026-11-15",
        val toleranceMs: Long = 3_000,
        val videos: List<Video>,
        val cases: List<Case>,
    )

    @Serializable
    data class Video(
        val id: String,
        val title: String,
        val channel: String? = null,
        val uploaded: String? = null,
        val language: String? = null,
        val vanished: Boolean = false,
        /** Sentences spoken word by word (WORD timing): each word [wordMs] after the one before. */
        val speech: List<Speech> = emptyList(),
        /** Caption lines (LINE timing). */
        val lines: List<Line> = emptyList(),
        val chapters: List<Chapter> = emptyList(),
    )

    @Serializable
    data class Speech(val start: Long, val text: String, val wordMs: Long = 350)

    @Serializable
    data class Line(val start: Long, val end: Long, val text: String)

    @Serializable
    data class Chapter(val start: Long, val title: String)

    @Serializable
    data class Case(val query: String, val relevant: List<Moment> = emptyList(), val k: Int = 5, val note: String? = null)

    @Serializable
    data class Moment(val video: String, val startMs: Long, val endMs: Long)

    data class CaseResult(
        val case: Case,
        val hits: List<YtSsiHit>,
        val recall: Double,
        val ndcg: Double,
        /** Deviation of each found moment's play start, ms. */
        val deviations: List<Long>,
        val noMatchOk: Boolean?,
    )

    data class Report(val fixture: Fixture, val results: List<CaseResult>) {
        private val scored = results.filter { it.case.relevant.isNotEmpty() }
        val recallAtK: Double get() = scored.map { it.recall }.average().takeIf { !it.isNaN() } ?: 1.0
        val ndcgAtK: Double get() = scored.map { it.ndcg }.average().takeIf { !it.isNaN() } ?: 1.0
        val meanDeviationMs: Double get() = results.flatMap { it.deviations }.map { it.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0
        val maxDeviationMs: Long get() = results.flatMap { it.deviations }.maxOrNull() ?: 0
        val noMatchRate: Double get() = results.mapNotNull { it.noMatchOk }.let { list -> if (list.isEmpty()) 1.0 else list.count { it } / list.size.toDouble() }

        fun summary(): String = buildString {
            append("${fixture.name}${if (fixture.synthetic) " (synthetic)" else ""}: ")
            append("Recall@K %.2f, nDCG@K %.2f, deviation mean %.0f ms max %d ms, no-match %.2f".format(recallAtK, ndcgAtK, meanDeviationMs, maxDeviationMs, noMatchRate))
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun load(resource: String): Fixture {
        val text = YtSsiBench::class.java.classLoader!!.getResourceAsStream(resource)!!.bufferedReader().use { it.readText() }
        return json.decodeFromString(Fixture.serializer(), text)
    }

    private val UTC = ZoneId.of("UTC")
    private fun date(text: String?): Long? = text?.let { LocalDate.parse(it).atStartOfDay(UTC).toInstant().toEpochMilli() }

    /** The index of a fixture, in memory. */
    class Index(fixture: Fixture) : YtSsiRanker.Source {
        val docs = HashMap<String, YtSsiDoc>()
        val segments = LinkedHashMap<Long, YtSsiSegment>()

        init {
            var id = 1L
            for (video in fixture.videos) {
                val units = video.speech.flatMap { sentence ->
                    sentence.text.split(' ').filter { it.isNotBlank() }.mapIndexed { i, word ->
                        val at = sentence.start + i * sentence.wordMs
                        YtSsiUnit(at, at + sentence.wordMs, word, word = true)
                    }
                } + video.lines.map { YtSsiUnit(it.start, it.end, it.text, word = false) }
                val timing = if (video.speech.isNotEmpty()) YtSsiTiming.WORD else YtSsiTiming.LINE
                val chapters = video.chapters.map { YtSsiChapter(it.start, it.title) }
                val speech = YtSsiSegmenter.segments(video.id, YtSsiTrack(units.sortedBy { it.startMs }, timing), chapters)
                val all = speech + YtSsiSegmenter.chapterSegments(video.id, chapters, units.maxOfOrNull { it.endMs }, speech.size)
                for (segment in all) {
                    segments[id] = segment.copy(id = id)
                    id++
                }
                docs[video.id] = YtSsiDoc(
                    videoId = video.id, title = video.title, channelId = null, channelName = video.channel, description = null,
                    uploaded = date(video.uploaded), durationSec = null, language = video.language, autoCaptions = null, timing = timing,
                    source = YtSsiSource.CAPTIONS, vanished = video.vanished, chapters = chapters,
                )
            }
        }

        override fun segment(id: Long) = segments[id]
        override fun neighbour(videoId: String, ord: Int) = segments.values.firstOrNull { it.videoId == videoId && it.ord == ord }
        override fun doc(videoId: String) = docs[videoId]

        /** BM25 over [rows] for an FTS query as [YtSsiFusion.ftsQuery] writes it (OR terms, or ANDed phrases). */
        fun <K> search(match: String?, rows: Map<K, String>): List<K> {
            if (match == null) return emptyList()
            val all = match.contains(" OR ").not() && match.startsWith("\"")
            val parts = if (all) Regex("\"[^\"]+\"").findAll(match).map { it.value }.toList() else match.split(" OR ")
            val folded = rows.mapValues { YtSsiText.words(it.value) }
            val n = folded.size
            val avg = (folded.values.sumOf { it.size }.toDouble() / n.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            fun hits(words: List<String>, part: String): Int = when {
                part.startsWith("\"") -> {
                    val p = part.trim('"').split(' ')
                    (0..words.size - p.size).count { i -> p.indices.all { words[i + it] == p[it] } }
                }
                part.endsWith("*") -> words.count { it.startsWith(part.dropLast(1)) }
                else -> words.count { it == part }
            }
            val counts = folded.mapValues { (_, words) -> parts.map { hits(words, it) } }
            val docsWith = parts.indices.map { p -> counts.values.count { it[p] > 0 } }
            val totals = parts.indices.map { p -> counts.values.sumOf { it[p] } }
            return counts.filter { (_, c) -> if (all) c.all { it > 0 } else c.any { it > 0 } }
                .map { (key, c) ->
                    val info = intArrayOf(parts.size, 1, n, avg, folded[key]!!.size) +
                        parts.indices.flatMap { listOf(c[it], totals[it], docsWith[it]) }.toIntArray()
                    key to YtSsiFusion.bm25(info)
                }
                .sortedByDescending { it.second }
                .map { it.first }
        }
    }

    fun run(fixture: Fixture): Report {
        val index = Index(fixture)
        val now = date(fixture.now)!! + 12 * 3600_000L
        val known = fixture.videos.mapNotNull { it.channel }.distinct()
        val results = fixture.cases.map { case ->
            val intent = YtSsiIntentParser.parse(case.query, now, UTC, known)
            val match = YtSsiFusion.ftsQuery(intent)
            val lexical = index.search(match, index.segments.mapValues { it.value.text })
            val titles = index.search(match, index.docs.mapValues { it.value.title + " " + it.value.channelName.orEmpty() })
            val hits = YtSsiRanker.rank(intent, YtSsiRanker.Lists(lexical, emptyList(), titles, inTitle = titles.toSet()), index)
            score(case, hits.take(case.k), fixture.toleranceMs, index)
        }
        return Report(fixture, results)
    }

    private fun score(case: Case, hits: List<YtSsiHit>, tolerance: Long, index: Index): CaseResult {
        if (case.relevant.isEmpty()) return CaseResult(case, hits, 1.0, 1.0, emptyList(), noMatchOk = hits.isEmpty())
        fun matches(hit: YtSsiHit, moment: Moment) =
            hit.videoId == moment.video && hit.segmentId != null && hit.hitMs in (moment.startMs - tolerance)..(moment.endMs + tolerance)
        val found = HashSet<Moment>()
        var dcg = 0.0
        val deviations = ArrayList<Long>()
        hits.forEachIndexed { i, hit ->
            val moment = case.relevant.firstOrNull { it !in found && matches(hit, it) } ?: return@forEachIndexed
            found += moment
            dcg += 1.0 / log2(i + 2.0)
            val start = YtSsiPlayRules.start(hit.segmentId?.let { index.segment(it) }, hit.hitMs)
            deviations += abs(start - moment.startMs)
        }
        val ideal = (0 until minOf(case.relevant.size, case.k)).sumOf { 1.0 / log2(it + 2.0) }
        return CaseResult(case, hits, found.size / case.relevant.size.toDouble(), if (ideal > 0) dcg / ideal else 0.0, deviations, noMatchOk = null)
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
