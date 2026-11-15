package com.github.libretube.ui.yt.ssi

/**
 * YT (SSI-2): checks where the statement of a hit begins and ends, so a clip never cuts its
 * meaning. Starting from the matched words ([focus], or the whole passage), the start goes back
 * to the beginning of their sentence (into the passage before when the passage starts mid
 * sentence) and the end forward to the end of the sentence (into the passage after). A next
 * sentence that qualifies the statement ("aber", "jedoch", "nicht", "but", "however", see
 * [QUALIFIERS]) and starts within [QUALIFIER_GAP_MS] is taken in as well.
 *
 * A sentence ends at punctuation, at a pause of speech recognition's words, or where a
 * passage ended at a pause. Times come only from the marks (word or line starts), so the span is
 * as exact as they are: WORD stays WORD, LINE stays LINE, a passage without marks is APPROX.
 * When no sentence border is in reach the span is cut there and not [YtSsiSpan.verified]. Pure.
 */
object YtSsiBoundary {
    /** How far back a sentence start may lie from the hit (beyond, playback starts [LEAD_MS] before it). */
    const val MAX_BACK_MS = YtSsiPlayRules.MAX_BACK_MS

    /** For a quote: playback starts at most this long before it (a sentence start further back is skipped). */
    const val QUOTE_MAX_LEAD_MS = 2_000L
    const val LEAD_MS = YtSsiPlayRules.LEAD_MS
    const val PRE_ROLL_MS = YtSsiPlayRules.PRE_ROLL_MS

    /** How far the end may move past the last matched word looking for a sentence end. */
    const val MAX_FORWARD_MS = 20_000L

    /** A qualifying sentence counts when it starts this soon after the statement ends. */
    const val QUALIFIER_GAP_MS = 8_000L

    /** ...and one of its first words is a qualifier. */
    const val QUALIFIER_WORDS = 3

    /** Folded words that turn or limit what was said before (German and English). */
    val QUALIFIERS = setOf(
        "aber", "jedoch", "allerdings", "doch", "nicht", "kein", "keine", "keinen", "keiner", "keinesfalls",
        "nie", "niemals", "trotzdem", "dennoch", "sondern", "ausser", "wobei", "obwohl", "andererseits", "einschrankend",
        "but", "however", "although", "though", "not", "no", "never", "except", "unless", "yet", "nevertheless",
        "isnt", "dont", "doesnt", "wasnt", "arent", "cant", "wont", "didnt",
    )

    /** One timed unit of the passage or a neighbour: its text and start. */
    private class Unit(val ms: Long, val text: String, val segment: Int, val word: Boolean) {
        var ends = false
        var endMs = ms
    }

    /**
     * The checked span of a hit in [segment]. [focus] is the character range of the matched
     * words in its text (null: the whole passage); [quote] limits how far back the start may go.
     */
    fun verify(prev: YtSsiSegment?, segment: YtSsiSegment, next: YtSsiSegment?, focus: IntRange?, quote: Boolean): YtSsiSpan {
        if (segment.timing == YtSsiTiming.APPROX || segment.marks.isEmpty() || segment.kind != YtSsiSegmentKind.SPEECH) {
            return YtSsiSpan(segment.startMs, segment.endMs, segment.startMs, YtSsiTiming.APPROX, verified = false)
        }
        fun usable(other: YtSsiSegment?) = other != null && other.kind == YtSsiSegmentKind.SPEECH && other.timing == segment.timing && other.marks.isNotEmpty()
        val parts = listOfNotNull(prev?.takeIf { usable(it) }, segment, next?.takeIf { usable(it) })
        val own = parts.indexOf(segment)
        val units = ArrayList<Unit>()
        // where each part's units begin in the list
        val firstUnit = IntArray(parts.size)
        for ((p, part) in parts.withIndex()) {
            firstUnit[p] = units.size
            for ((i, mark) in part.marks.withIndex()) {
                val endChar = part.marks.getOrNull(i + 1)?.char ?: part.text.length
                val text = if (mark.char in 0..endChar && endChar <= part.text.length) part.text.substring(mark.char, endChar).trim() else ""
                units += Unit(mark.ms, text, p, segment.timing == YtSsiTiming.WORD)
            }
        }
        // ends and end times
        for ((i, unit) in units.withIndex()) {
            val following = units.getOrNull(i + 1)
            val part = parts[unit.segment]
            val lastOfPart = following == null || following.segment != unit.segment
            unit.endMs = if (lastOfPart) maxOf(part.endMs, unit.ms) else following!!.ms
            unit.ends = YtSsiSegmenter.ends(unit.text) ||
                (following != null && unit.word && following.ms - unit.ms >= YtSsiSegmenter.WORD_PAUSE_MS) ||
                (following != null && lastOfPart && !unit.word && following.ms - part.endMs >= YtSsiSegmenter.LINE_PAUSE_MS)
        }
        val ownFirst = firstUnit[own]
        val ownLast = ownFirst + segment.marks.size - 1
        fun unitAt(char: Int): Int {
            var at = ownFirst
            for (i in ownFirst..ownLast) if (segment.marks[i - ownFirst].char <= char) at = i else break
            return at
        }
        val fi = focus?.let { unitAt(it.first) } ?: ownFirst
        val li = focus?.let { maxOf(fi, unitAt(it.last)) } ?: ownLast
        val hitMs = units[fi].ms

        // start: back to the sentence start
        // a quote's play start (sentence start less the pre-roll) stays within QUOTE_MAX_LEAD_MS of it
        val maxBack = if (quote) QUOTE_MAX_LEAD_MS - PRE_ROLL_MS else MAX_BACK_MS
        var s = fi
        while (s > 0 && !units[s - 1].ends && hitMs - units[s - 1].ms <= maxBack) s--
        val atVideoStart = s == 0 && parts[0].ord == 0
        var startVerified = (s > 0 && units[s - 1].ends) || atVideoStart
        var start = units[s].ms
        if (!startVerified || hitMs - start > maxBack) {
            // no sentence start in reach: the word start nearest before the lead
            var w = fi
            while (w > 0 && hitMs - units[w - 1].ms <= LEAD_MS) w--
            start = units[w].ms
            startVerified = false
        }

        // end: forward to the sentence end
        var e = li
        while (!units[e].ends && e + 1 < units.size && units[e + 1].ms - units[li].ms <= MAX_FORWARD_MS) e++
        val endVerified = units[e].ends || (e == units.lastIndex && next == null)
        var end = units[e].endMs

        // a qualifying sentence right after it
        var qualifier = false
        if (endVerified && e + 1 < units.size && units[e + 1].ms - end <= QUALIFIER_GAP_MS) {
            val q = e + 1
            val words = ArrayList<String>()
            var k = q
            while (k < units.size && words.size < QUALIFIER_WORDS) {
                words += YtSsiText.words(units[k].text)
                if (units[k].ends) break
                k++
            }
            if (words.take(QUALIFIER_WORDS).any { it in QUALIFIERS }) {
                var m = q
                while (!units[m].ends && m + 1 < units.size && units[m + 1].ms - units[q].ms <= MAX_FORWARD_MS) m++
                if (units[m].ends || m == units.lastIndex) {
                    end = units[m].endMs
                    qualifier = true
                }
            }
        }
        val verified = startVerified && endVerified
        return YtSsiSpan(
            startMs = start,
            endMs = maxOf(end, start),
            playStartMs = (start - if (startVerified) PRE_ROLL_MS else 0L).coerceAtLeast(0),
            timing = segment.timing,
            verified = verified,
            qualifier = qualifier,
        )
    }

    /** The character range of words [words] (offsets as [YtSsiText.wordRanges] counts them) in [text], or null when out of range. */
    fun charsOfWords(text: String, words: IntRange): IntRange? {
        val ranges = YtSsiText.wordRanges(text)
        if (words.first < 0 || words.first > words.last || words.last >= ranges.size) return null
        return ranges[words.first].first..ranges[words.last].last
    }
}
