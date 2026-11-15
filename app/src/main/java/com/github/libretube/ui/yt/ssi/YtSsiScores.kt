package com.github.libretube.ui.yt.ssi

/**
 * YT (SSI-2): the relevance band of a hit, kept apart from how exact its time is. A band is a
 * statement about the evidence, never a percentage:
 * - the AI's grade decides when Deep re-ranked the passage (3 high, 2 medium, else low);
 * - a quote found word for word is high;
 * - otherwise the best place a method gave the passage, whether full text and meaning agree,
 *   and how many of the query's words the passage holds ([coverage]);
 * - a match in the title or description alone is at most medium (no spoken moment).
 * Pure.
 */
object YtSsiScores {
    /** A method's place at or above this is strong evidence. */
    const val TOP = 3

    /** Both methods at or above this place: they agree. */
    const val AGREE = 10

    fun band(hit: YtSsiHit, intent: YtSsiIntent): YtSsiBand {
        hit.ai?.let {
            return when {
                it.relevant >= 3 -> YtSsiBand.HIGH
                it.relevant == 2 -> YtSsiBand.MEDIUM
                else -> YtSsiBand.LOW
            }
        }
        if (hit.segmentId == null) {
            val rank = hit.ranks[YtSsiMethod.FTS_BM25] ?: Int.MAX_VALUE
            return if (rank <= TOP) YtSsiBand.MEDIUM else YtSsiBand.LOW
        }
        if (intent.exact && YtSsiMatch.TRANSCRIPT_LEXICAL in hit.matches) return YtSsiBand.HIGH
        val textual = YtSsiMatch.TRANSCRIPT_LEXICAL in hit.matches || YtSsiMatch.CHAPTER in hit.matches
        val lexical = hit.ranks[YtSsiMethod.FTS_BM25]?.takeIf { textual }
        val semantic = hit.ranks[YtSsiMethod.EMBEDDING]
        val best = listOfNotNull(lexical, semantic).minOrNull() ?: return YtSsiBand.LOW
        val agree = lexical != null && semantic != null && lexical <= AGREE && semantic <= AGREE
        val covered = coverage(hit, intent)
        return when {
            best <= TOP && (agree || covered >= 0.75) -> YtSsiBand.HIGH
            best <= AGREE || covered >= 0.5 || agree -> YtSsiBand.MEDIUM
            else -> YtSsiBand.LOW
        }
    }

    /** The share of the query's keywords that occur in the hit's marked words (0 without keywords). */
    fun coverage(hit: YtSsiHit, intent: YtSsiIntent): Double {
        val terms = intent.terms.distinct()
        if (terms.isEmpty()) return 0.0
        val words = hit.highlights.flatMap { r ->
            if (r.first < 0 || r.last >= hit.text.length) emptyList() else YtSsiText.words(hit.text.substring(r.first, r.last + 1))
        }.toSet()
        val found = terms.count { term -> words.any { it == term || (term.length >= 4 && it.startsWith(term)) } }
        return found / terms.size.toDouble()
    }
}
