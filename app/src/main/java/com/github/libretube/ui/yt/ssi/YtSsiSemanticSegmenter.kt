package com.github.libretube.ui.yt.ssi

import kotlin.math.sqrt

/**
 * YT (SSI-2): cuts what is said where its meaning changes instead of after a fixed amount of
 * text. The units are first joined into sentences (punctuation, a pause, or at the latest
 * [SENTENCE_MAX_CHARS]), each sentence gets a vector from the embedding model, and the
 * cohesion of every gap is the cosine between the [WINDOW] sentences before and after it. A gap
 * whose cohesion is a clear dip (TextTiling's depth: how far it lies below the peaks on both
 * sides, at least [MIN_DEPTH] and not below the usual dip of the video) is a topic border.
 *
 * A passage then grows sentence by sentence while the talk stays on one topic (so short rule
 * passages merge) and closes at a border once it has [MIN_CHARS] (so a rule passage that
 * held two topics splits). The hard limits of the rules stay: no passage spans a chapter start,
 * [MAX_CHARS] or [MAX_SPAN_MS] at most (then it is cut at its weakest gap). Without vectors the
 * rules of [YtSsiSegmenter] cut, unchanged. Pure.
 */
object YtSsiSemanticSegmenter {
    /** A sentence without punctuation is cut here at the latest (at its longest pause). */
    const val SENTENCE_MAX_CHARS = 200

    /** A topic border closes a passage once it has this much text. */
    const val MIN_CHARS = 60
    const val MAX_CHARS = YtSsiSegmenter.MAX_CHARS
    const val MAX_SPAN_MS = YtSsiSegmenter.MAX_SPAN_MS

    /** Sentences on each side of a gap whose mean vectors are compared. */
    const val WINDOW = 2

    /** A dip smaller than this is noise between sentences of one topic. */
    const val MIN_DEPTH = 0.03

    /** Consecutive units that form one sentence. */
    data class Sentence(val units: List<YtSsiUnit>) {
        val text: String get() = units.joinToString(" ") { it.text }
        val startMs: Long get() = units.first().startMs
        val endMs: Long get() = maxOf(units.last().endMs, units.maxOf { it.startMs })
        val chars: Int get() = units.sumOf { it.text.length + 1 }
    }

    /** The sentences of [units]: closed at punctuation, at a pause, at a chapter start, or when too long. */
    fun sentences(units: List<YtSsiUnit>, chapters: List<YtSsiChapter> = emptyList()): List<Sentence> {
        val all = units.filter { it.text.isNotBlank() }
        val chapterStarts = chapters.map { it.startMs }.filter { it > 0 }.sorted()
        val out = ArrayList<Sentence>()
        val open = ArrayList<YtSsiUnit>()
        var nextChapter = 0
        fun close(count: Int = open.size) {
            if (count <= 0) return
            out += Sentence(open.subList(0, count).toList())
            open.subList(0, count).clear()
        }
        for ((i, unit) in all.withIndex()) {
            var crossed = false
            while (nextChapter < chapterStarts.size && chapterStarts[nextChapter] <= unit.startMs) {
                nextChapter++
                crossed = true
            }
            if (crossed) close()
            open += unit
            val next = all.getOrNull(i + 1)
            when {
                YtSsiSegmenter.ends(unit.text) -> close()
                next != null && YtSsiSegmenter.pause(unit, next) -> close()
                open.sumOf { it.text.length + 1 } >= SENTENCE_MAX_CHARS -> close(longestPause(open))
            }
        }
        close()
        return out
    }

    /** Where to cut a run-on sentence: after the unit followed by the longest pause in its second half. */
    private fun longestPause(units: List<YtSsiUnit>): Int {
        if (units.size < 2) return units.size
        var best = units.size
        var bestGap = -1L
        for (i in units.size / 2 until units.size - 1) {
            val gap = YtSsiSegmenter.gap(units[i], units[i + 1])
            if (gap > bestGap) {
                bestGap = gap
                best = i + 1
            }
        }
        return best
    }

    /** Cohesion of each gap (gap g lies between sentence g and g + 1): cosine of the window means. */
    fun cohesion(vectors: List<FloatArray>, window: Int = WINDOW): DoubleArray {
        val n = vectors.size
        if (n < 2) return DoubleArray(0)
        return DoubleArray(n - 1) { g ->
            val left = mean(vectors.subList(maxOf(0, g - window + 1), g + 1))
            val right = mean(vectors.subList(g + 1, minOf(n, g + 1 + window)))
            YtSsiVectors.cosine(left, right).toDouble()
        }
    }

    private fun mean(list: List<FloatArray>): FloatArray {
        val dim = list.first().size
        val out = FloatArray(dim)
        for (v in list) for (i in 0 until minOf(dim, v.size)) out[i] += v[i]
        return out
    }

    /**
     * The depth of each gap: how far its cohesion lies below the highest point reached by
     * climbing to the left and to the right. Zero for a gap that is no local dip.
     */
    fun depths(cohesion: DoubleArray): DoubleArray = DoubleArray(cohesion.size) { g ->
        val c = cohesion[g]
        val leftLower = g == 0 || c <= cohesion[g - 1]
        val rightLower = g == cohesion.lastIndex || c <= cohesion[g + 1]
        if (!leftLower || !rightLower) return@DoubleArray 0.0
        var left = c
        var j = g
        while (j > 0 && cohesion[j - 1] >= left) {
            left = cohesion[j - 1]
            j--
        }
        var right = c
        j = g
        while (j < cohesion.lastIndex && cohesion[j + 1] >= right) {
            right = cohesion[j + 1]
            j++
        }
        (left - c) + (right - c)
    }

    /**
     * The gaps that are topic borders: depth at least [MIN_DEPTH] and at least the usual dip of
     * this video (mean − σ/2 over its dips, the TextTiling cut-off).
     */
    fun borders(cohesion: DoubleArray): Set<Int> {
        val depth = depths(cohesion)
        val dips = depth.filter { it > 0 }
        if (dips.isEmpty()) return emptySet()
        val mean = dips.average()
        val sd = sqrt(dips.sumOf { (it - mean) * (it - mean) } / dips.size)
        val floor = maxOf(MIN_DEPTH, mean - sd / 2)
        return depth.indices.filter { depth[it] >= floor }.toSet()
    }

    /**
     * The passages of [track]. [sentences] are [sentences] of the track, [vectors] one per
     * sentence; without them (or when they do not fit) the rules of [YtSsiSegmenter] cut.
     */
    fun segments(
        videoId: String,
        track: YtSsiTrack,
        sentences: List<Sentence>,
        vectors: List<FloatArray>?,
        chapters: List<YtSsiChapter> = emptyList(),
    ): List<YtSsiSegment> {
        if (vectors == null || vectors.size != sentences.size || sentences.size < 2) {
            return YtSsiSegmenter.segments(videoId, track, chapters)
        }
        val cohesion = cohesion(vectors)
        val borders = borders(cohesion)
        val chapterStarts = chapters.map { it.startMs }.filter { it > 0 }.sorted()
        val groups = group(sentences, cohesion, borders, chapterStarts)
        return groups.mapIndexed { ord, group -> YtSsiSegmenter.build(videoId, ord, group.flatMap { it.units }, track.timing) }
    }

    /** Sentences into passages (indices of [sentences] per passage, in order). */
    internal fun group(sentences: List<Sentence>, cohesion: DoubleArray, borders: Set<Int>, chapterStarts: List<Long>): List<List<Sentence>> {
        val out = ArrayList<List<Sentence>>()
        // the open passage as indices into sentences
        var first = 0
        var count = 0
        fun chars(from: Int, n: Int) = (from until from + n).sumOf { sentences[it].chars }
        fun emit(n: Int) {
            out += sentences.subList(first, first + n).toList()
            first += n
            count -= n
        }

        /**
         * The weakest gap inside the open passage that leaves at least [MIN_CHARS] before it (of
         * equal ones the latest, so passages stay as long as they may); all when none.
         */
        fun weakest(): Int {
            var best = count
            var bestCohesion = Double.MAX_VALUE
            for (k in 1 until count) {
                if (chars(first, k) < MIN_CHARS) continue
                val c = cohesion[first + k - 1]
                if (c <= bestCohesion) {
                    bestCohesion = c
                    best = k
                }
            }
            return best
        }
        for (i in sentences.indices) {
            val sentence = sentences[i]
            if (count > 0) {
                val last = sentences[i - 1]
                val chapter = chapterStarts.any { it > last.startMs && it <= sentence.startMs }
                if (chapter) {
                    emit(count)
                } else {
                    while (count > 0 && (chars(first, count) + sentence.chars > MAX_CHARS || sentence.endMs - sentences[first].startMs > MAX_SPAN_MS)) {
                        emit(weakest())
                    }
                    if (count > 0 && (i - 1) in borders && chars(first, count) >= MIN_CHARS) emit(count)
                }
            }
            count++
        }
        if (count > 0) emit(count)
        return out
    }

    /**
     * The units of passages already in the index (a video indexed from captions is not fetched
     * again): each mark starts a unit that runs to the next mark or the passage's end. Line
     * ends are not stored, so the pauses between lines are lost; punctuation still cuts.
     */
    fun fromPassages(passages: List<YtSsiSegment>): YtSsiTrack {
        val speech = passages.filter { it.kind == YtSsiSegmentKind.SPEECH }.sortedBy { it.ord }
        val timing = speech.firstOrNull()?.timing ?: YtSsiTiming.APPROX
        val units = ArrayList<YtSsiUnit>()
        for (passage in speech) {
            val marks = passage.marks
            for ((i, mark) in marks.withIndex()) {
                val endChar = marks.getOrNull(i + 1)?.char ?: passage.text.length
                if (mark.char !in 0..endChar || endChar > passage.text.length) continue
                val text = passage.text.substring(mark.char, endChar).trim()
                if (text.isEmpty()) continue
                val end = marks.getOrNull(i + 1)?.ms ?: passage.endMs
                units += YtSsiUnit(mark.ms, maxOf(end, mark.ms), text, word = timing == YtSsiTiming.WORD)
            }
        }
        return YtSsiTrack(units, timing)
    }

    /** Whether [a] and [b] are the same passages (times and text): then nothing needs storing again. */
    fun same(a: List<YtSsiSegment>, b: List<YtSsiSegment>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) -> x.startMs == y.startMs && x.endMs == y.endMs && x.text == y.text }
}
