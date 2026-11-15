package com.github.libretube.ui.yt.ssi

/**
 * YT: cuts what is said into passages of about one to three sentences, not fixed windows. A
 * passage closes
 * - at a chapter start (a passage never spans two chapters),
 * - at the end of a sentence (punctuation) once it has [MIN_CHARS], or after [MAX_SENTENCES],
 * - at a pause ([WORD_PAUSE_MS], [LINE_PAUSE_MS]; speech recognition often has no punctuation) once it has [MIN_CHARS],
 * - at the latest at [MAX_CHARS] or [MAX_SPAN_MS]: then at the longest pause in its second half.
 * The passages before and after are its context (ord ± 1). Pure.
 */
object YtSsiSegmenter {
    const val MIN_CHARS = 110
    const val MAX_CHARS = 360
    const val MAX_SPAN_MS = 30_000L
    const val MAX_SENTENCES = 3

    /**
     * A pause that counts as a sentence break: for words the time from one word's start to the
     * next (about 0.3 s per word in normal speech; the json3 word ends are not reliable), for lines
     * the silence between a line's end and the next line.
     */
    const val WORD_PAUSE_MS = 1_000L
    const val LINE_PAUSE_MS = 1_500L

    private val SENTENCE_END = Regex("""[.!?…]["»“”'’)]*$""")

    fun segments(
        videoId: String,
        track: YtSsiTrack,
        chapters: List<YtSsiChapter> = emptyList(),
    ): List<YtSsiSegment> {
        val units = track.units.filter { it.text.isNotBlank() }
        if (units.isEmpty()) return emptyList()
        val chapterStarts = chapters.map { it.startMs }.filter { it > 0 }.sorted()
        val out = ArrayList<YtSsiSegment>()
        val open = ArrayList<YtSsiUnit>()
        var sentences = 0
        var chars = 0
        var nextChapter = 0

        fun emit(count: Int) {
            if (count <= 0) return
            val taken = open.subList(0, count).toList()
            open.subList(0, count).clear()
            out += build(videoId, out.size, taken, track.timing)
            sentences = open.count { ends(it.text) }
            chars = open.sumOf { it.text.length + 1 }
        }

        for ((i, unit) in units.withIndex()) {
            // a chapter starts at or before this unit: the passage before it closes
            var crossed = false
            while (nextChapter < chapterStarts.size && chapterStarts[nextChapter] <= unit.startMs) {
                nextChapter++
                crossed = true
            }
            if (crossed && open.isNotEmpty()) emit(open.size)
            open += unit
            chars += unit.text.length + 1
            val sentenceEnd = ends(unit.text)
            if (sentenceEnd) sentences++
            val next = units.getOrNull(i + 1)
            val pause = next != null && pause(unit, next)
            val span = unit.endMs - open.first().startMs
            when {
                sentenceEnd && (chars >= MIN_CHARS || sentences >= MAX_SENTENCES) -> emit(open.size)
                pause && chars >= MIN_CHARS -> emit(open.size)
                chars >= MAX_CHARS || span >= MAX_SPAN_MS -> emit(bestCut(open))
            }
        }
        emit(open.size)
        return out
    }

    private fun ends(text: String) = SENTENCE_END.containsMatchIn(text.trim())

    private fun gap(unit: YtSsiUnit, next: YtSsiUnit): Long =
        if (unit.word) next.startMs - unit.startMs else next.startMs - unit.endMs

    private fun pause(unit: YtSsiUnit, next: YtSsiUnit): Boolean =
        gap(unit, next) >= if (unit.word) WORD_PAUSE_MS else LINE_PAUSE_MS

    /**
     * Where to cut a passage that grew too long: after the unit followed by the longest pause in
     * its second half (a sentence end counts first), else everything.
     */
    private fun bestCut(units: List<YtSsiUnit>): Int {
        if (units.size < 2) return units.size
        var best = units.size
        var bestGap = -1L
        for (i in units.size / 2 until units.size - 1) {
            val gap = gap(units[i], units[i + 1]) + if (ends(units[i].text)) 100_000 else 0
            if (gap > bestGap) {
                bestGap = gap
                best = i + 1
            }
        }
        return best
    }

    private fun build(videoId: String, ord: Int, units: List<YtSsiUnit>, timing: YtSsiTiming): YtSsiSegment {
        val text = StringBuilder()
        val marks = ArrayList<YtSsiMark>(units.size)
        for (unit in units) {
            if (text.isNotEmpty()) text.append(' ')
            marks += YtSsiMark(text.length, unit.startMs)
            text.append(unit.text)
        }
        val start = units.first().startMs
        val end = maxOf(units.last().endMs, units.maxOf { it.startMs })
        return YtSsiSegment(videoId, ord, start, end, text.toString(), YtSsiSegmentKind.SPEECH, timing, marks)
    }

    /** Chapters as passages of their own (title only, the chapter's span; APPROX). */
    fun chapterSegments(videoId: String, chapters: List<YtSsiChapter>, durationMs: Long?, firstOrd: Int): List<YtSsiSegment> {
        val sorted = chapters.filter { it.title.isNotBlank() }.sortedBy { it.startMs }
        return sorted.mapIndexed { i, chapter ->
            val end = sorted.getOrNull(i + 1)?.startMs ?: durationMs ?: (chapter.startMs + 60_000)
            YtSsiSegment(videoId, firstOrd + i, chapter.startMs, maxOf(end, chapter.startMs), chapter.title.trim(), YtSsiSegmentKind.CHAPTER, YtSsiTiming.APPROX)
        }
    }

    // ---- marks as stored ----

    /** The marks as "char:ms" pairs relative to [startMs], comma separated (compact for the DB). */
    fun encodeMarks(marks: List<YtSsiMark>, startMs: Long): String =
        marks.joinToString(",") { "${it.char}:${it.ms - startMs}" }

    fun decodeMarks(text: String?, startMs: Long): List<YtSsiMark> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.split(',').mapNotNull { pair ->
            val colon = pair.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val char = pair.substring(0, colon).toIntOrNull() ?: return@mapNotNull null
            val ms = pair.substring(colon + 1).toLongOrNull() ?: return@mapNotNull null
            YtSsiMark(char, startMs + ms)
        }
    }

    /** The time of the unit that holds character [char] of a segment with [marks]; [fallback] without marks. */
    fun timeAt(marks: List<YtSsiMark>, char: Int, fallback: Long): Long {
        var at = fallback
        for (mark in marks) {
            if (mark.char <= char) at = mark.ms else break
        }
        return at
    }
}
