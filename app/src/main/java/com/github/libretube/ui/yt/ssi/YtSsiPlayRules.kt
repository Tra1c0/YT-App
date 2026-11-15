package com.github.libretube.ui.yt.ssi

import com.github.libretube.ui.yt.news.YtNewsTranscriptDoc

/** One clip of a clip sequence: [videoId] from [startMs] to [endMs]. */
data class YtSsiClip(val videoId: String, val startMs: Long, val endMs: Long, val title: String = "", val channelName: String? = null)

/** How a hit's time is shown: the badge and whether the time gets an "≈". */
enum class YtSsiPrecisionLabel(val approximate: Boolean) {
    /** Word starts from speech recognition. */
    WORD(false),

    /** Caption line starts: right to a line, a few seconds. */
    LINE(true),

    /** A chapter, a title or a description: the span, not the moment. */
    SPAN(true),
}

/** Why a hit is in the list, as the screen spells it out. */
sealed interface YtSsiReason {
    data class Lexical(val rank: Int, val words: List<String>) : YtSsiReason
    data class Semantic(val rank: Int, val model: String?) : YtSsiReason
    data class Title(val rank: Int) : YtSsiReason
    data object Chapter : YtSsiReason
    data object ExactPhrase : YtSsiReason
    data object TimeFilter : YtSsiReason
    data object ChannelFilter : YtSsiReason
    data object ChannelGuess : YtSsiReason
    data object LanguageUnknown : YtSsiReason
    data object Vanished : YtSsiReason
    data object AiRewrite : YtSsiReason
    data object FromYouTubeSearch : YtSsiReason
    data class Later(val asks: Set<YtSsiLater>) : YtSsiReason
    data class Timing(val label: YtSsiPrecisionLabel) : YtSsiReason
}

/**
 * YT: where SSI starts playing, how clips are cut and what is said about a hit. Pure.
 */
object YtSsiPlayRules {
    /** About this much is played before the hit... */
    const val LEAD_MS = 1_500L

    /** ...from the sentence start nearest to that point, but not further back than this. */
    const val MAX_BACK_MS = 8_000L

    /** And a moment before the sentence's first word, so it is not clipped. */
    const val PRE_ROLL_MS = 300L

    /** A little after the passage's end, so the last word is not cut. */
    const val TAIL_MS = 1_000L

    const val CONTEXT_STEP_MS = 30_000L

    /** Clips of one video closer than this are played as one. */
    const val JOIN_GAP_MS = 2_000L

    fun label(timing: YtSsiTiming): YtSsiPrecisionLabel = when (timing) {
        YtSsiTiming.WORD -> YtSsiPrecisionLabel.WORD
        YtSsiTiming.LINE -> YtSsiPrecisionLabel.LINE
        YtSsiTiming.APPROX -> YtSsiPrecisionLabel.SPAN
    }

    /** "≈ 1:02" or "1:02:05": the hit's time as the row shows it. */
    fun stamp(ms: Long, timing: YtSsiTiming): String {
        val text = YtNewsTranscriptDoc.stamp(ms / 1000)
        return if (label(timing).approximate) "≈ $text" else text
    }

    /**
     * Where playback of a hit starts: about [LEAD_MS] before [hitMs], moved to the sentence start
     * (a passage start, or a unit after a sentence end, up to the hit's own sentence) nearest to
     * that point within [MAX_BACK_MS], less [PRE_ROLL_MS]; with no sentence start in reach,
     * [LEAD_MS] before the hit. A span (chapter, title) starts at its start.
     */
    fun start(segment: YtSsiSegment?, hitMs: Long): Long {
        if (segment == null) return hitMs.coerceAtLeast(0)
        if (segment.timing == YtSsiTiming.APPROX) return segment.startMs.coerceAtLeast(0)
        val target = hitMs - LEAD_MS
        val pick = sentenceStarts(segment)
            .filter { it in (hitMs - MAX_BACK_MS)..hitMs }
            .minByOrNull { kotlin.math.abs(it - target) }
        return (pick?.minus(PRE_ROLL_MS) ?: target).coerceAtLeast(0)
    }

    /** The passage start and every unit that follows a sentence end. */
    fun sentenceStarts(segment: YtSsiSegment): List<Long> {
        val out = arrayListOf(segment.startMs)
        val marks = segment.marks
        for (i in 1 until marks.size) {
            val before = segment.text.substring(0, marks[i].char).trimEnd()
            if (before.isNotEmpty() && before.last() in ".!?…") out += marks[i].ms
        }
        return out
    }

    /**
     * The clips of [hits] as a sequence: per video in the order its first hit came, inside a
     * video by time; overlapping or close clips of one video become one. [before]/[after] widen a
     * hit's clip (the "−30 s" / "+30 s" buttons) by its index in [hits].
     */
    fun clips(hits: List<YtSsiHit>, segments: (YtSsiHit) -> YtSsiSegment?, before: Map<Int, Long> = emptyMap(), after: Map<Int, Long> = emptyMap()): List<YtSsiClip> {
        val order = LinkedHashMap<String, MutableList<YtSsiClip>>()
        hits.forEachIndexed { i, hit ->
            val start = (start(segments(hit), hit.hitMs) - (before[i] ?: 0L)).coerceAtLeast(0)
            val end = maxOf(hit.endMs, hit.hitMs) + TAIL_MS + (after[i] ?: 0L)
            order.getOrPut(hit.videoId) { ArrayList() } += YtSsiClip(hit.videoId, start, maxOf(end, start + 3_000), hit.title, hit.channelName)
        }
        val out = ArrayList<YtSsiClip>()
        for ((_, clips) in order) {
            val sorted = clips.sortedBy { it.startMs }
            var open = sorted.first()
            for (clip in sorted.drop(1)) {
                open = if (clip.startMs <= open.endMs + JOIN_GAP_MS) open.copy(endMs = maxOf(open.endMs, clip.endMs)) else {
                    out += open
                    clip
                }
            }
            out += open
        }
        return out
    }

    /** "Warum dieser Treffer?": every signal that put [hit] in the list, strongest first. */
    fun reasons(hit: YtSsiHit, intent: YtSsiIntent, matchedWords: List<String>): List<YtSsiReason> {
        val out = ArrayList<YtSsiReason>()
        if (intent.exact && YtSsiMatch.TRANSCRIPT_LEXICAL in hit.matches) out += YtSsiReason.ExactPhrase
        hit.ranks[YtSsiMethod.FTS_BM25]?.let { rank ->
            if (YtSsiMatch.TRANSCRIPT_LEXICAL in hit.matches || YtSsiMatch.CHAPTER in hit.matches) out += YtSsiReason.Lexical(rank, matchedWords)
        }
        hit.ranks[YtSsiMethod.EMBEDDING]?.let { out += YtSsiReason.Semantic(it, hit.modelVersion) }
        if (YtSsiMatch.TITLE in hit.matches || YtSsiMatch.DESCRIPTION in hit.matches) {
            out += YtSsiReason.Title(hit.ranks[YtSsiMethod.FTS_BM25] ?: 0)
        }
        if (YtSsiMatch.CHAPTER in hit.matches) out += YtSsiReason.Chapter
        if (YtSsiMethod.AI_REWRITE in hit.methods) out += YtSsiReason.AiRewrite
        if (YtSsiMethod.YOUTUBE_SEARCH in hit.methods) out += YtSsiReason.FromYouTubeSearch
        if (intent.time != null) out += YtSsiReason.TimeFilter
        if (intent.channels.isNotEmpty()) out += YtSsiReason.ChannelFilter
        if (YtSsiNote.CHANNEL_GUESSED in hit.notes) out += YtSsiReason.ChannelGuess
        if (YtSsiNote.LANGUAGE_UNKNOWN in hit.notes) out += YtSsiReason.LanguageUnknown
        if (hit.availability == YtSsiAvailability.VANISHED_ARCHIVED) out += YtSsiReason.Vanished
        if (intent.later.isNotEmpty()) out += YtSsiReason.Later(intent.later)
        out += YtSsiReason.Timing(label(hit.timing))
        return out
    }
}
