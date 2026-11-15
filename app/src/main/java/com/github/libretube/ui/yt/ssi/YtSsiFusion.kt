package com.github.libretube.ui.yt.ssi

import kotlin.math.ln

/**
 * YT: the ranking parts of SSI's hybrid retrieval, pure.
 * - [bm25]: Okapi BM25 from SQLite FTS4's matchinfo('pcnalx') (FTS4 has no rank function of its
 *   own; Android's framework SQLite is not guaranteed to have FTS5 on API 26).
 * - [rrf]: Reciprocal Rank Fusion of the methods' lists. Only ranks are fused, never the raw
 *   scores of different methods (a BM25 value and a cosine are not on one scale).
 * - [ftsQuery]: the MATCH expression in FTS's standard syntax (terms joined by OR, phrases in
 *   quotes, prefixes with *), with nothing a user can type breaking it.
 * - [passes]: the hard constraints.
 */
object YtSsiFusion {
    /** The usual RRF constant: a hit at place 1 counts 1/61, at place 10 1/70. */
    const val RRF_K = 60.0

    private const val K1 = 1.2
    private const val B = 0.75

    /**
     * BM25 of one row from matchinfo('pcnalx') as 32-bit integers: p phrases, c columns, n rows,
     * a[c] average tokens, l[c] tokens of this row, then x[p][c] = (hits in this row, hits in all
     * rows, rows with a hit). [columnWeights] weight the columns (default 1 each).
     */
    fun bm25(info: IntArray, columnWeights: DoubleArray? = null): Double {
        if (info.size < 3) return 0.0
        val p = info[0]
        val c = info[1]
        val n = info[2].toDouble()
        if (info.size < 3 + 2 * c + 3 * p * c) return 0.0
        var score = 0.0
        for (phrase in 0 until p) {
            for (col in 0 until c) {
                val x = 3 + 2 * c + 3 * (phrase * c + col)
                val tf = info[x].toDouble()
                if (tf <= 0) continue
                val docs = info[x + 2].toDouble()
                val avg = info[3 + col].toDouble().coerceAtLeast(1.0)
                val len = info[3 + c + col].toDouble()
                val idf = ln(1.0 + (n - docs + 0.5) / (docs + 0.5))
                val norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * len / avg))
                score += (columnWeights?.getOrNull(col) ?: 1.0) * idf * norm
            }
        }
        return score
    }

    /** The result of a fusion: score per key and each method's 1-based place of it. */
    data class Fused<K>(val scores: Map<K, Double>, val ranks: Map<K, Map<YtSsiMethod, Int>>) {
        /** The keys from best to worst; ties keep the order of the first list they appear in. */
        fun ordered(): List<K> = scores.entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * Reciprocal Rank Fusion: score(k) = Σ weight(m) / (RRF_K + rank_m(k)). A method with weight 0
     * or an empty list adds nothing.
     */
    fun <K> rrf(lists: List<Triple<YtSsiMethod, Double, List<K>>>): Fused<K> {
        val scores = LinkedHashMap<K, Double>()
        val ranks = LinkedHashMap<K, MutableMap<YtSsiMethod, Int>>()
        for ((method, weight, list) in lists) {
            if (weight <= 0) continue
            val seen = HashSet<K>()
            var rank = 0
            for (key in list) {
                if (!seen.add(key)) continue
                rank++
                scores[key] = (scores[key] ?: 0.0) + weight / (RRF_K + rank)
                ranks.getOrPut(key) { HashMap() }[method] = rank
            }
        }
        return Fused(scores, ranks)
    }

    /** One FTS token: folded, only letters and digits (no operator can come through). */
    private fun token(word: String): String? = YtSsiText.fold(word).replace(" ", "").takeIf { it.isNotEmpty() }

    /**
     * The MATCH expression of [intent]: for an exact quote only its phrases (all must be there);
     * otherwise every keyword (words of 4+ letters also as prefix, "erklärt" finds "erklärte")
     * and the phrases, joined by OR, so BM25 ranks the rows that have more of them. Null when
     * nothing is left to match. [extra] are more keywords (an AI rewrite), folded like the rest.
     */
    fun ftsQuery(intent: YtSsiIntent, extra: List<String> = emptyList()): String? {
        val phrases = intent.phrases.mapNotNull { phrase ->
            val words = YtSsiText.words(phrase)
            if (words.isEmpty()) null else "\"" + words.joinToString(" ") + "\""
        }
        if (intent.exact) return phrases.takeIf { it.isNotEmpty() }?.joinToString(" ")
        val words = (intent.terms + extra.flatMap { YtSsiText.keywords(it) }).mapNotNull(::token).distinct().take(MAX_TERMS)
        val parts = words.map { if (it.length >= 4) "$it*" else it } + phrases
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" OR ")
    }

    private const val MAX_TERMS = 24

    /** What the constraint check needs of a candidate. */
    data class Candidate(
        val channelId: String?,
        val channelName: String?,
        val uploaded: Long?,
        val language: String?,
        /** Folded text the exclusions are checked in (the passage and the title). */
        val folded: String,
        /** Folded text a quote is looked for in (the passage, reaching into its neighbours); null = [folded]. */
        val phraseText: String? = null,
    )

    enum class Fail { TIME, TIME_UNKNOWN, CHANNEL, EXCLUDED_CHANNEL, EXCLUDED_WORD, PHRASE, LANGUAGE }

    /**
     * Null when [candidate] keeps every hard constraint of [intent], else the first one it breaks.
     * A video without an upload date fails a time range (it cannot be shown to be in it); one of
     * unknown language passes a language hint (marked LANGUAGE_UNKNOWN on the hit).
     */
    fun passes(intent: YtSsiIntent, candidate: Candidate): Fail? {
        intent.time?.let { range ->
            val at = candidate.uploaded ?: return Fail.TIME_UNKNOWN
            if (!range.contains(at)) return Fail.TIME
        }
        if (intent.channels.isNotEmpty() && intent.channels.none { channelMatches(it, candidate) }) return Fail.CHANNEL
        if (intent.excludeChannels.any { channelMatches(it, candidate) }) return Fail.EXCLUDED_CHANNEL
        val text = " " + candidate.folded + " "
        if (intent.excludeTerms.any { term -> containsWords(text, term) }) return Fail.EXCLUDED_WORD
        // the quote word for word (folded: case, accents and punctuation do not count)
        val phraseText = " " + (candidate.phraseText ?: candidate.folded) + " "
        if (intent.exact && intent.phrases.any { !phraseText.contains(" " + YtSsiText.fold(it) + " ") }) return Fail.PHRASE
        val language = intent.language
        if (language != null && candidate.language != null && base(candidate.language) != language) return Fail.LANGUAGE
        return null
    }

    /** Whether folded [text] (padded with spaces) holds the folded words [words] in a row, the last one as a prefix. */
    fun containsWords(text: String, words: String): Boolean = words.isNotBlank() && text.contains(" $words")

    /** "@handle", a name or an id against the candidate's channel (folded, whole name). */
    fun channelMatches(wanted: String, candidate: Candidate): Boolean {
        val w = YtSsiText.fold(wanted.removePrefix("@")).replace(" ", "")
        if (w.isEmpty()) return false
        val name = candidate.channelName?.let { YtSsiText.fold(it).replace(" ", "") }
        val id = candidate.channelId?.lowercase()
        return name == w || id == w || id?.endsWith("/$w") == true || id?.endsWith("@$w") == true
    }

    fun base(code: String?): String? = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotEmpty() }
}
