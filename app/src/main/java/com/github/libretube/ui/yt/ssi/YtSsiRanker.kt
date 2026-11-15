package com.github.libretube.ui.yt.ssi

/**
 * YT: turns the methods' ranked lists into SSI's hits, pure: RRF over passages (full text and
 * embeddings), title/description matches lifting the best passage of their video (or standing as
 * a hit of their own when no passage of it matched), the hard constraints, at most
 * [PER_VIDEO] hits per video, and the details every hit carries (highlight, hit time, context).
 */
object YtSsiRanker {
    const val PER_VIDEO = 3
    const val LIMIT = 60

    /** A hit's key: a passage, or a whole video (segId null) for a title-only match. */
    private data class Key(val segId: Long?, val videoId: String)

    /** What the ranker reads of the index. */
    interface Source {
        fun segment(id: Long): YtSsiSegment?
        fun neighbour(videoId: String, ord: Int): YtSsiSegment?
        fun doc(videoId: String): YtSsiDoc?
    }

    data class Lists(
        /** Passage ids by BM25, best first. */
        val lexical: List<Long>,
        /** Passage ids by cosine, best first; empty without the model. */
        val semantic: List<Long>,
        /** Video ids by BM25 over title, channel, description and chapters, best first. */
        val titles: List<String>,
        /** Which of [titles] matched in the title itself (else description or chapters). */
        val inTitle: Set<String> = emptySet(),
        val model: String? = null,
        /** Videos found by Deep's YouTube search. */
        val fromYouTube: Set<String> = emptySet(),
        /** Whether an AI rewrite added words to the full-text query. */
        val aiWords: Boolean = false,
    )

    fun rank(intent: YtSsiIntent, lists: Lists, source: Source, limit: Int = LIMIT): List<YtSsiHit> {
        val w = intent.weights
        val fused = YtSsiFusion.rrf(
            listOf(
                Triple(YtSsiMethod.FTS_BM25, w.lexical, lists.lexical),
                Triple(YtSsiMethod.EMBEDDING, w.semantic, lists.semantic),
            ),
        )
        val scores = LinkedHashMap<Key, Double>()
        val ranks = HashMap<Key, MutableMap<YtSsiMethod, Int>>()
        val matches = HashMap<Key, MutableSet<YtSsiMatch>>()
        val segments = HashMap<Long, YtSsiSegment>()
        for (id in fused.ordered()) {
            val segment = source.segment(id) ?: continue
            segments[id] = segment
            val key = Key(id, segment.videoId)
            scores[key] = fused.scores[id] ?: 0.0
            val r = fused.ranks[id].orEmpty()
            ranks[key] = r.toMutableMap()
            matches[key] = buildSet {
                if (YtSsiMethod.FTS_BM25 in r) add(if (segment.kind == YtSsiSegmentKind.CHAPTER) YtSsiMatch.CHAPTER else YtSsiMatch.TRANSCRIPT_LEXICAL)
                if (YtSsiMethod.EMBEDDING in r) add(if (segment.kind == YtSsiSegmentKind.CHAPTER) YtSsiMatch.CHAPTER else YtSsiMatch.TRANSCRIPT_SEMANTIC)
            }.toMutableSet()
        }
        // a title match lifts the best passage of its video, or is a hit of its own
        lists.titles.distinct().forEachIndexed { i, videoId ->
            val add = w.title / (YtSsiFusion.RRF_K + i + 1)
            val kind = if (videoId in lists.inTitle) YtSsiMatch.TITLE else YtSsiMatch.DESCRIPTION
            val best = scores.keys.filter { it.videoId == videoId }.maxByOrNull { scores[it] ?: 0.0 }
            val key = best ?: Key(null, videoId)
            scores[key] = (scores[key] ?: 0.0) + add
            ranks.getOrPut(key) { HashMap() }.putIfAbsent(YtSsiMethod.FTS_BM25, i + 1)
            matches.getOrPut(key) { HashSet() } += kind
        }
        // a known channel named plainly lifts its videos a little, never filters
        val guessed = HashSet<Key>()
        if (intent.channelGuesses.isNotEmpty()) {
            for (key in scores.keys.toList()) {
                val doc = source.doc(key.videoId) ?: continue
                val candidate = YtSsiFusion.Candidate(doc.channelId, doc.channelName, null, null, "")
                if (intent.channelGuesses.any { YtSsiFusion.channelMatches(it, candidate) }) {
                    scores[key] = (scores[key] ?: 0.0) + 0.5 / (YtSsiFusion.RRF_K + 1)
                    guessed += key
                }
            }
        }

        val out = ArrayList<YtSsiHit>()
        val perVideo = HashMap<String, Int>()
        for ((key, score) in scores.entries.sortedByDescending { it.value }) {
            if (out.size >= limit) break
            val doc = source.doc(key.videoId) ?: continue
            if ((perVideo[key.videoId] ?: 0) >= PER_VIDEO) continue
            val segment = key.segId?.let { segments[it] ?: source.segment(it) }
            val prev = segment?.let { source.neighbour(it.videoId, it.ord - 1) }
            val next = segment?.let { source.neighbour(it.videoId, it.ord + 1) }
            val own = YtSsiText.fold(listOfNotNull(segment?.text, doc.title).joinToString(" "))
            val candidate = YtSsiFusion.Candidate(doc.channelId, doc.channelName, doc.uploaded, doc.language, own, phraseText(intent, prev, segment, next))
            if (YtSsiFusion.passes(intent, candidate) != null) continue
            perVideo[key.videoId] = (perVideo[key.videoId] ?: 0) + 1
            val methods = ranks[key].orEmpty().keys.toMutableSet()
            if (lists.aiWords && YtSsiMethod.FTS_BM25 in methods) methods += YtSsiMethod.AI_REWRITE
            if (key.videoId in lists.fromYouTube) methods += YtSsiMethod.YOUTUBE_SEARCH
            val notes = buildList {
                if (key in guessed) add(YtSsiNote.CHANNEL_GUESSED)
                if (intent.language != null && doc.language == null) add(YtSsiNote.LANGUAGE_UNKNOWN)
                if (intent.later.isNotEmpty()) add(YtSsiNote.LATER_PHASE_STANCE)
            }
            val text = segment?.text ?: doc.title
            val highlights = highlights(text, intent)
            val hitMs = segment?.let { s ->
                val first = highlights.firstOrNull()
                if (first != null && s.timing != YtSsiTiming.APPROX) YtSsiSegmenter.timeAt(s.marks, first.first, s.startMs) else s.startMs
            } ?: 0L
            out += YtSsiHit(
                videoId = key.videoId,
                title = doc.title,
                channelName = doc.channelName,
                uploaded = doc.uploaded,
                language = doc.language,
                segmentId = segment?.id,
                startMs = segment?.startMs ?: 0L,
                endMs = segment?.endMs ?: 0L,
                hitMs = hitMs,
                contextStartMs = prev?.startMs ?: segment?.startMs ?: 0L,
                contextEndMs = next?.endMs ?: segment?.endMs ?: 0L,
                text = text,
                highlights = highlights,
                matches = matches[key].orEmpty(),
                methods = methods,
                timing = segment?.timing ?: YtSsiTiming.APPROX,
                availability = doc.availability,
                rank = out.size + 1,
                fused = score,
                ranks = ranks[key].orEmpty(),
                notes = notes,
                modelVersion = if (YtSsiMethod.EMBEDDING in methods) lists.model else null,
            )
        }
        return out
    }

    /**
     * Where a quote of k words may be for [segment]: its text with the last k-1 words of the
     * passage before and the first k-1 of the one after, so a quote across a passage border is
     * found but one entirely in a neighbour is not. Null without a passage (the title counts).
     */
    private fun phraseText(intent: YtSsiIntent, prev: YtSsiSegment?, segment: YtSsiSegment?, next: YtSsiSegment?): String? {
        if (!intent.exact || segment == null) return null
        val k = intent.phrases.maxOfOrNull { YtSsiText.words(it).size } ?: return null
        val before = prev?.let { YtSsiText.words(it.text).takeLast(k - 1) }.orEmpty()
        val after = next?.let { YtSsiText.words(it.text).take(k - 1) }.orEmpty()
        return (before + YtSsiText.words(segment.text) + after).joinToString(" ")
    }

    /**
     * The character ranges of [text] that match the query: a whole quoted phrase, else each
     * keyword (words of 4+ letters also as the start of a longer word).
     */
    fun highlights(text: String, intent: YtSsiIntent): List<IntRange> {
        val ranges = YtSsiText.wordRanges(text)
        val words = ranges.map { YtSsiText.fold(text.substring(it.first, it.last + 1)).replace(" ", "") }
        val out = ArrayList<IntRange>()
        for (phrase in intent.phrases) {
            val p = YtSsiText.words(phrase)
            if (p.isEmpty()) continue
            for (i in 0..words.size - p.size) {
                if ((p.indices).all { words[i + it] == p[it] }) out += ranges[i].first..ranges[i + p.size - 1].last
            }
        }
        if (!intent.exact) {
            for ((i, word) in words.withIndex()) {
                val hit = intent.terms.any { term -> word == term || (term.length >= 4 && word.startsWith(term)) }
                if (hit) out += ranges[i]
            }
        }
        return merge(out)
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }

    /** The folded words of [hit]'s text that matched (for "Warum dieser Treffer?"). */
    fun matchedWords(hit: YtSsiHit): List<String> =
        hit.highlights.map { YtSsiText.fold(hit.text.substring(it.first, it.last + 1)) }.distinct().take(6)
}
