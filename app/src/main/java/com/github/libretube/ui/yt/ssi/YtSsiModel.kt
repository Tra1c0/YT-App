package com.github.libretube.ui.yt.ssi

/*
 * YT: the data of SSI ("Super Search Intelligence"), free of Android so the rules run on the JVM.
 * A document is a video, a segment one passage of what is said in it (1–3 sentences) with its
 * time span. A hit is a passage (or a whole video, for a match in its title) with everything the
 * screen needs to say honestly where it comes from and how sure its time is.
 */

/** How exact a segment's times are. Never more than the source gives. */
enum class YtSsiTiming {
    /** Every word has its own start (YouTube's speech recognition, json3). */
    WORD,

    /** Times per caption line (manual captions, TTML/VTT, the archive's transcript). */
    LINE,

    /** A span, not a spoken moment: a chapter, or a match in the title or description. */
    APPROX,
}

/** What a segment holds. */
enum class YtSsiSegmentKind { SPEECH, CHAPTER }

/** Where a video's text came from. */
enum class YtSsiSource { ARCHIVE, CAPTIONS }

/** Whether the original can still be played. */
enum class YtSsiAvailability {
    PUBLIC,

    /** Gone from YouTube; only the archive's copy (text, maybe sound or 144p) is left. */
    VANISHED_ARCHIVED,
}

/** What part of a video matched. */
enum class YtSsiMatch { TITLE, DESCRIPTION, CHAPTER, TRANSCRIPT_LEXICAL, TRANSCRIPT_SEMANTIC }

/** Which retrieval found it. AI_RERANK: the optional AI grading of the top passages (Deep). */
enum class YtSsiMethod { FTS_BM25, EMBEDDING, AI_REWRITE, YOUTUBE_SEARCH, AI_RERANK }

/** How a video's passages were cut: by the rules of SSI-1, or at drops of meaning between sentences. */
enum class YtSsiSegmentation { RULES, SEMANTIC }

/** A video as SSI keeps it. [uploaded] epoch ms or null; [language] a base code ("de") or null. */
data class YtSsiDoc(
    val videoId: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val description: String?,
    val uploaded: Long?,
    val durationSec: Long?,
    val language: String?,
    /** The captions were made by speech recognition. */
    val autoCaptions: Boolean?,
    val timing: YtSsiTiming,
    val source: YtSsiSource,
    val vanished: Boolean,
    val chapters: List<YtSsiChapter> = emptyList(),
    val indexedAt: Long = 0,
    val version: Int = YtSsiVersions.ANALYSIS,
    val segmentation: YtSsiSegmentation = YtSsiSegmentation.RULES,
) {
    val availability: YtSsiAvailability get() = if (vanished) YtSsiAvailability.VANISHED_ARCHIVED else YtSsiAvailability.PUBLIC
}

data class YtSsiChapter(val startMs: Long, val title: String)

/**
 * One passage. [ord] is its place in the video (the context passages are ord ± 1). [marks] are
 * the times inside the text: for each timed unit (a word for WORD, a caption line for LINE) the
 * character offset in [text] where it starts and its start in ms.
 */
data class YtSsiSegment(
    val videoId: String,
    val ord: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val kind: YtSsiSegmentKind,
    val timing: YtSsiTiming,
    val marks: List<YtSsiMark> = emptyList(),
    val id: Long = 0,
)

/** A timed unit inside a segment's text: it starts at character [char] and at [ms]. */
data class YtSsiMark(val char: Int, val ms: Long)

/** Versions stored with the index and every hit: a change re-indexes or re-embeds. */
object YtSsiVersions {
    /**
     * Segmentation and folding. 2 (SSI-2): passages are cut where the meaning changes when the
     * model is there ([YtSsiSemanticSegmenter]); the rules of 1 stay the fallback.
     */
    const val ANALYSIS = 2

    /**
     * The oldest analysis whose passages are still valid rule passages: such a video is not read
     * again but re-cut in the background ([YtSsiIndexer.migrate]), one at a time.
     */
    const val MIN_KEPT = 1

    /**
     * Stored with every vector (in the model's version string). Apart from [ANALYSIS] so a passage
     * that stays the same keeps its vector when the analysis changes elsewhere.
     */
    const val VECTORS = 1
}

/** Relevance as a band of evidence, never a percentage. */
enum class YtSsiBand { HIGH, MEDIUM, LOW }

/**
 * The statement a hit stands for, with checked borders: [startMs]..[endMs] holds the whole
 * sentence(s) of the match, plus a following sentence that qualifies it ("aber", "nicht", "but")
 * when [qualifier]. [playStartMs] is where playback begins (a moment before [startMs], or before
 * the quote when the sentence starts long before it). [timing] is how exact these times are:
 * never more than the marks they come from. [verified] is false when a border had to be cut
 * (no sentence end in reach, or no marks).
 */
data class YtSsiSpan(
    val startMs: Long,
    val endMs: Long,
    val playStartMs: Long,
    val timing: YtSsiTiming,
    val verified: Boolean,
    val qualifier: Boolean = false,
)

/**
 * The AI's grade of a passage (Deep re-rank): [relevant] 0 (does not fit) to 3 (exactly it),
 * [words] the statement inside the passage as word offsets, when it named one.
 */
data class YtSsiAiGrade(val relevant: Int, val words: IntRange? = null, val cached: Boolean = false)

/**
 * One result. Relevance and timing precision are separate on purpose: there is no single
 * percentage. [relevance] is only the place in the fused list (1 = best) and the fused RRF score,
 * which compares hits of the same search and nothing else.
 */
data class YtSsiHit(
    val videoId: String,
    val title: String,
    val channelName: String?,
    val uploaded: Long?,
    val language: String?,
    /** The passage that matched; null for a match in the title or description only. */
    val segmentId: Long?,
    val startMs: Long,
    val endMs: Long,
    /** The hit inside the passage (a matched word for WORD timing), else [startMs]. */
    val hitMs: Long,
    /** The context around it: the passages before and after. */
    val contextStartMs: Long,
    val contextEndMs: Long,
    val text: String,
    /** Character ranges in [text] that matched the query's words (for the highlight). */
    val highlights: List<IntRange>,
    val matches: Set<YtSsiMatch>,
    val methods: Set<YtSsiMethod>,
    val timing: YtSsiTiming,
    val availability: YtSsiAvailability,
    val rank: Int,
    val fused: Double,
    /** The place in each method's own list (1-based), for "Warum dieser Treffer?". */
    val ranks: Map<YtSsiMethod, Int>,
    /** Soft signals that applied (a channel name named without "von"). */
    val notes: List<YtSsiNote> = emptyList(),
    val analysisVersion: Int = YtSsiVersions.ANALYSIS,
    /** The embedding model of the semantic part, null when it was not used. */
    val modelVersion: String? = null,
    /** Relevance band (rank evidence and the AI's grade), shown apart from the timing. */
    val band: YtSsiBand = YtSsiBand.LOW,
    /** The checked statement borders; null for a title-only hit. */
    val span: YtSsiSpan? = null,
    /** The AI's grade when Deep re-ranked the top passages. */
    val ai: YtSsiAiGrade? = null,
    /** The place before the AI re-rank (equal to [rank] without it). */
    val localRank: Int = rank,
)

/** Things to say about a hit beyond its matches. */
enum class YtSsiNote { CHANNEL_GUESSED, LANGUAGE_UNKNOWN, LATER_PHASE_STANCE }
