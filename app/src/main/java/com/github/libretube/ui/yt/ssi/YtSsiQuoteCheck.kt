package com.github.libretube.ui.yt.ssi

import com.github.libretube.ui.yt.news.YtNewsQuotes

/**
 * YT (SSI-3): the check that an AI's quote really stands in a passage, pure. The same tolerance as
 * the news summaries ([YtNewsQuotes]): case, punctuation, umlaut spelling and accents do not
 * matter, and about one typo per eight letters is allowed (a recognition error the AI fixed). A
 * quote must have at least [MIN_WORDS] words and [MIN_CHARS] letters, so two common words prove
 * nothing. Unlike the news check it answers WHERE the quote is (a character range of the
 * original text), so its time can be read from the passage's marks.
 */
object YtSsiQuoteCheck {
    const val MIN_WORDS = 3
    const val MIN_CHARS = 12

    /** Longer quotes are compared on their first part only. */
    const val MAX_CHARS = 300

    /** Edits allowed per character of the quote (as [YtNewsQuotes]). */
    const val TOLERANCE = 0.125

    /**
     * The character range of [text] that holds [quote], or null when it is not there (or too
     * short to prove anything). An exact word match wins; otherwise the window of words (the
     * quote's length ± 2 words) with the fewest edits, when within the tolerance.
     */
    fun find(text: String, quote: String?): IntRange? {
        val needle = YtNewsQuotes.normalize(quote ?: return null).take(MAX_CHARS).trim()
        val needleWords = needle.split(' ').filter { it.isNotEmpty() }
        if (needle.length < MIN_CHARS || needleWords.size < MIN_WORDS) return null
        val ranges = YtSsiText.wordRanges(text)
        // each original word in normalised form; one word may become two ("don't" is two already)
        val words = ArrayList<String>(ranges.size)
        val at = ArrayList<IntRange>(ranges.size)
        for (range in ranges) {
            for (part in YtNewsQuotes.normalize(text.substring(range.first, range.last + 1)).split(' ')) {
                if (part.isEmpty()) continue
                words += part
                at += range
            }
        }
        val n = needleWords.size
        if (words.isEmpty()) return null
        // exact
        for (i in 0..words.size - n) {
            var same = true
            for (k in 0 until n) {
                if (words[i + k] != needleWords[k]) {
                    same = false
                    break
                }
            }
            if (same) return at[i].first..at[i + n - 1].last
        }
        // fuzzy: the closest window of about the same length
        val allowed = (needle.length * TOLERANCE).toInt()
        var best: IntRange? = null
        var bestDistance = allowed + 1
        var bestLengthGap = Int.MAX_VALUE
        for (i in words.indices) {
            for (len in maxOf(1, n - 2)..(n + 2)) {
                if (i + len > words.size) break
                val window = words.subList(i, i + len).joinToString(" ")
                if (kotlin.math.abs(window.length - needle.length) > allowed) continue
                val d = bounded(needle, window, allowed)
                if (d > allowed) continue
                val gap = kotlin.math.abs(len - n)
                if (d < bestDistance || (d == bestDistance && gap < bestLengthGap)) {
                    best = at[i].first..at[i + len - 1].last
                    bestDistance = d
                    bestLengthGap = gap
                }
            }
        }
        return best
    }

    /** Whether [quote] is in [text] ([find] finds it). */
    fun verified(text: String, quote: String?): Boolean = find(text, quote) != null

    /** Whether two quotes are the same words (normalised). */
    fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return YtNewsQuotes.normalize(a) == YtNewsQuotes.normalize(b)
    }

    /**
     * The edit distance of [a] and [b] when it is at most [max], else max + 1 (a band of width
     * [max] around the diagonal, so long texts stay cheap).
     */
    internal fun bounded(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val inf = max + 1
        var prev = IntArray(b.length + 1) { if (it <= max) it else inf }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            val from = maxOf(1, i - max)
            val to = minOf(b.length, i + max)
            cur.fill(inf)
            cur[0] = if (i <= max) i else inf
            var rowBest = cur[0]
            for (j in from..to) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                val v = minOf(prev[j - 1] + cost, prev[j] + 1, cur[j - 1] + 1)
                cur[j] = minOf(v, inf)
                if (cur[j] < rowBest) rowBest = cur[j]
            }
            if (rowBest > max) return inf
            val swap = prev
            prev = cur
            cur = swap
        }
        return minOf(prev[b.length], inf)
    }
}
